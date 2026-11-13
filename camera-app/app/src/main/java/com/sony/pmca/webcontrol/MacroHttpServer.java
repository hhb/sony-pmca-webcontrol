package com.sony.pmca.webcontrol;

import com.sony.scalar.hardware.CameraEx;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeoutException;

import fi.iki.elonen.NanoHTTPD;

public class MacroHttpServer extends NanoHTTPD {
    public static final int DEFAULT_PORT = 8080;

    private static final Response.IStatus STATUS_GATEWAY_TIMEOUT = new Response.IStatus() {
        @Override
        public int getRequestStatus() {
            return 504;
        }

        @Override
        public String getDescription() {
            return "504 Gateway Timeout";
        }
    };

    private final MacroServerActivity m_activity;

    public MacroHttpServer(MacroServerActivity activity) {
        this(activity, DEFAULT_PORT);
    }

    public MacroHttpServer(MacroServerActivity activity, int port) {
        super(port);
        m_activity = activity;
    }

    @Override
    public Response serve(IHTTPSession session) {
        String uri = session.getUri();
        Method method = session.getMethod();
        Map<String, String> params = session.getParms();

        if (Method.OPTIONS.equals(method)) {
            return addCors(new Response(Response.Status.OK, MIME_PLAINTEXT, "OK"));
        }

        try {
            if ("/".equals(uri) || "/index.html".equals(uri)) {
                return serveAssetHtml("index.html");
            }

            if ("/api/describe".equals(uri)) {
                JSONObject desc = m_activity.getRpcDispatcher().describe(params.get("target"), params.get("q"));
                return jsonOk(desc.toString());
            }

            if ("/api/call".equals(uri)) {
                if (!Method.POST.equals(method)) {
                    return jsonError(Response.Status.METHOD_NOT_ALLOWED, "Use POST /api/call with JSON body");
                }
                final CameraEx cam = m_activity.getCameraEx();
                if (cam == null) {
                    return jsonError(Response.Status.INTERNAL_ERROR, "Camera not initialized");
                }
                final JSONObject body = parseJsonBody(session);
                JSONObject res = m_activity.runOnMainSync(new Callable<JSONObject>() {
                    @Override
                    public JSONObject call() throws Exception {
                        return m_activity.getRpcDispatcher().executeBatchOrSingle(m_activity, cam, body);
                    }
                }, MacroServerActivity.MAIN_THREAD_TIMEOUT_MS);
                return jsonOk(res.toString());
            }

            if ("/api/state".equals(uri)) {
                return jsonOk(m_activity.getEventHub().getStateSnapshot().toString());
            }

            if ("/api/events".equals(uri)) {
                long since = parseLong(params.get("since"), 0L);
                int timeout = parseInt(params.get("timeout"), 10);
                JSONObject res = m_activity.getEventHub().waitForEvents(since, timeout);
                return jsonOk(res.toString());
            }

            if ("/api/liveview.jpg".equals(uri)) {
                byte[] jpeg = m_activity.getLatestLiveViewJpeg();
                if (jpeg == null) {
                    return addCors(new Response(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "No preview frame yet"));
                }
                Response res = new Response(Response.Status.OK, "image/jpeg", new ByteArrayInputStream(jpeg));
                res.addHeader("Cache-Control", "no-store, no-cache, must-revalidate");
                return addCors(res);
            }

            if ("/api/photos/latest".equals(uri) || "/api/capture/last.jpg".equals(uri)) {
                byte[] inMem = m_activity.getLastCapturedJpeg();
                if (inMem != null && inMem.length > 0) {
                    Response res = new Response(Response.Status.OK, "image/jpeg", new ByteArrayInputStream(inMem));
                    res.addHeader("Content-Disposition", "attachment; filename=\"latest_capture.jpg\"");
                    return addCors(res);
                }
                File latest = m_activity.getLatestPhotoFile();
                if (latest != null && latest.exists() && latest.isFile()) {
                    return serveFileDownload(latest);
                }
                return jsonError(Response.Status.NOT_FOUND, "No captured photo available yet");
            }

            if ("/api/photos".equals(uri)) {
                int limit = parseInt(params.get("limit"), 50);
                List<File> list = m_activity.listPhotosOnSdCard(limit);
                JSONArray arr = new JSONArray();
                for (File f : list) {
                    JSONObject item = new JSONObject();
                    item.put("name", f.getName());
                    item.put("path", f.getAbsolutePath().replace("\\", "/"));
                    item.put("size", f.length());
                    item.put("modified", f.lastModified());
                    arr.put(item);
                }
                JSONObject root = new JSONObject();
                root.put("ok", true);
                root.put("count", arr.length());
                root.put("photos", arr);
                return jsonOk(root.toString());
            }

            if ("/api/photos/download".equals(uri)) {
                String path = params.get("path");
                if (path == null || path.trim().isEmpty()) {
                    path = params.get("file");
                }
                File target = (path != null && !path.trim().isEmpty()) ? new File(path.trim()) : m_activity.getLatestPhotoFile();
                if (target == null || !target.exists() || !target.isFile()) {
                    return jsonError(Response.Status.NOT_FOUND, "Photo file not found");
                }
                return serveFileDownload(target);
            }

            if ("/api/log".equals(uri)) {
                return addCors(new Response(Response.Status.OK, MIME_PLAINTEXT, m_activity.getCrashLogText()));
            }

            return jsonError(Response.Status.NOT_FOUND, "Unknown endpoint: " + uri);
        } catch (TimeoutException te) {
            return jsonError(STATUS_GATEWAY_TIMEOUT, te.getMessage());
        } catch (Throwable t) {
            Throwable cause = t.getCause() != null ? t.getCause() : t;
            return jsonError(Response.Status.INTERNAL_ERROR, cause.toString());
        }
    }

    private JSONObject parseJsonBody(IHTTPSession session) throws Exception {
        Map<String, String> files = new HashMap<String, String>();
        session.parseBody(files);
        String postData = files.get("postData");
        if (postData != null && !postData.trim().isEmpty()) {
            return new JSONObject(postData.trim());
        }
        return new JSONObject();
    }

    private Response serveAssetHtml(String assetName) {
        try {
            InputStream is = m_activity.getAssets().open(assetName);
            Response res = new Response(Response.Status.OK, "text/html; charset=utf-8", is);
            res.addHeader("Cache-Control", "no-store, no-cache");
            return addCors(res);
        } catch (Throwable t) {
            return addCors(new Response(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "Failed to load asset: " + t.toString()));
        }
    }

    private Response serveFileDownload(File file) {
        try {
            String lower = file.getName().toLowerCase();
            String mime = lower.endsWith(".arw") ? "application/octet-stream" : "image/jpeg";
            FileInputStream fis = new FileInputStream(file);
            Response res = new Response(Response.Status.OK, mime, fis);
            res.addHeader("Content-Disposition", "attachment; filename=\"" + file.getName() + "\"");
            res.addHeader("Content-Length", String.valueOf(file.length()));
            return addCors(res);
        } catch (FileNotFoundException e) {
            return jsonError(Response.Status.NOT_FOUND, "File not found");
        }
    }

    private Response jsonOk(String json) {
        return addCors(new Response(Response.Status.OK, "application/json", json));
    }

    private Response jsonError(Response.IStatus status, String msg) {
        JSONObject err = new JSONObject();
        try {
            err.put("ok", false);
            err.put("error", msg != null ? msg : "unknown error");
        } catch (Throwable ignored) {
        }
        return addCors(new Response(status, "application/json", err.toString()));
    }

    private Response addCors(Response res) {
        res.addHeader("Access-Control-Allow-Origin", "*");
        res.addHeader("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
        res.addHeader("Access-Control-Allow-Headers", "Content-Type");
        return res;
    }

    private static int parseInt(String s, int def) {
        if (s == null) return def;
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static long parseLong(String s, long def) {
        if (s == null) return def;
        try {
            return Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }
}

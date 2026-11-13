package com.sony.pmca.webcontrol;

import com.sony.scalar.hardware.CameraEx;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
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

    private static final Response.IStatus STATUS_SERVICE_UNAVAILABLE = new Response.IStatus() {
        @Override
        public int getRequestStatus() {
            return 503;
        }

        @Override
        public String getDescription() {
            return "503 Service Unavailable";
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
                if (!body.has("calls") && "highlevel".equals(body.optString("target", ""))) {
                    String rpcMethod = body.optString("method", "");
                    JSONArray args = body.optJSONArray("args");
                    JSONObject result = m_activity.getGoalController().execute(rpcMethod, args);
                    JSONObject out = new JSONObject();
                    out.put("ok", true);
                    out.put("target", "highlevel");
                    out.put("method", rpcMethod);
                    out.put("result", result);
                    return jsonOk(out.toString());
                }
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

            if ("/api/liveview.mjpeg".equals(uri)) {
                String cid = params.get("cid");
                LiveViewManager.MjpegClient client = m_activity.getLiveViewManager().acquireMjpegClient(cid);
                return new MjpegStreamResponse(client);
            }

            if ("/api/liveview/close".equals(uri)) {
                String cid = params.get("cid");
                boolean closed = m_activity.getLiveViewManager().closeMjpegClient(cid);
                JSONObject res = new JSONObject();
                res.put("closed", closed);
                return jsonOk(res.toString());
            }

            if ("/api/liveview.jpg".equals(uri)) {
                byte[] jpeg = m_activity.getLiveViewManager().getLatestLiveViewJpeg();
                if (jpeg == null) {
                    return addCors(new Response(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "No preview frame yet"));
                }
                Response res = new Response(Response.Status.OK, "image/jpeg", new ByteArrayInputStream(jpeg));
                res.addHeader("Cache-Control", "no-store, no-cache, must-revalidate");
                return addCors(res);
            }

            if ("/api/photos/latest".equals(uri) || "/api/capture/last.jpg".equals(uri)) {
                byte[] inMem = m_activity.getLiveViewManager().getLastCapturedJpeg();
                if (inMem != null && inMem.length > 0) {
                    Response res = new Response(Response.Status.OK, "image/jpeg", new ByteArrayInputStream(inMem));
                    res.addHeader("Content-Disposition", "attachment; filename=\"latest_capture.jpg\"");
                    return addCors(res);
                }
                File latest = PhotoStorageHelper.getLatestPhotoFile();
                if (latest != null && latest.exists() && latest.isFile()) {
                    return serveFileDownload(latest);
                }
                return jsonError(Response.Status.NOT_FOUND, "No captured photo available yet");
            }

            if ("/api/photos".equals(uri)) {
                int limit = parseInt(params.get("limit"), 100);
                String dir = params.get("dir");
                JSONObject root = PhotoStorageHelper.listPhotoDirectory(dir, limit);
                return jsonOk(root.toString());
            }

            if ("/api/photos/download".equals(uri)) {
                String path = params.get("path");
                if (path == null || path.trim().isEmpty()) {
                    path = params.get("file");
                }
                File target = (path != null && !path.trim().isEmpty()) ? new File(path.trim()) : PhotoStorageHelper.getLatestPhotoFile();
                if (target == null || !target.exists() || !target.isFile()) {
                    return jsonError(Response.Status.NOT_FOUND, "Photo file not found");
                }
                return serveFileDownload(target);
            }

            if ("/api/log".equals(uri)) {
                return addCors(new Response(Response.Status.OK, MIME_PLAINTEXT, PhotoStorageHelper.getCrashLogText()));
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
        Map<String, String> headers = session.getHeaders();
        String lenStr = headers.get("content-length");
        if (lenStr == null) {
            lenStr = headers.get("Content-Length");
        }
        int contentLength = parseInt(lenStr, 0);
        if (contentLength <= 0) {
            return new JSONObject();
        }
        InputStream is = session.getInputStream();
        byte[] buf = new byte[contentLength];
        int offset = 0;
        while (offset < contentLength) {
            int n = is.read(buf, offset, contentLength - offset);
            if (n < 0) break;
            offset += n;
        }
        String postData = new String(buf, 0, offset, "UTF-8").trim();
        if (!postData.isEmpty()) {
            return new JSONObject(postData);
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

    private class MjpegStreamResponse extends Response {
        private final LiveViewManager.MjpegClient m_client;

        MjpegStreamResponse(LiveViewManager.MjpegClient client) {
            super(Status.OK, "multipart/x-mixed-replace; boundary=frame", "");
            m_client = client;
        }

        @Override
        protected void send(OutputStream outputStream) {
            try {
                String headers = "HTTP/1.1 200 OK\r\n"
                        + "Content-Type: multipart/x-mixed-replace; boundary=frame\r\n"
                        + "Cache-Control: no-store, no-cache, must-revalidate\r\n"
                        + "Connection: close\r\n"
                        + "Access-Control-Allow-Origin: *\r\n"
                        + "Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n"
                        + "Access-Control-Allow-Headers: Content-Type\r\n\r\n";
                outputStream.write(headers.getBytes("UTF-8"));
                outputStream.flush();

                long lastSeq = -1;
                LiveViewManager liveView = m_activity.getLiveViewManager();
                while (true) {
                    LiveViewManager.LiveViewFrame frame = liveView.waitForNextFrame(m_client, lastSeq, 2000);
                    if (frame == null) {
                        break;
                    }
                    if (frame.jpeg == null || frame.seq == lastSeq) {
                        continue;
                    }
                    lastSeq = frame.seq;
                    String partHeader = "--frame\r\n"
                            + "Content-Type: image/jpeg\r\n"
                            + "Content-Length: " + frame.jpeg.length + "\r\n\r\n";
                    outputStream.write(partHeader.getBytes("UTF-8"));
                    outputStream.write(frame.jpeg);
                    outputStream.write("\r\n".getBytes("UTF-8"));
                    outputStream.flush();
                }
            } catch (IOException ignored) {
                // Client disconnected
            } finally {
                m_activity.getLiveViewManager().releaseMjpegClient(m_client);
            }
        }
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

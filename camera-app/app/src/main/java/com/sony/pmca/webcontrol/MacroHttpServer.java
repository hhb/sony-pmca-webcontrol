package com.sony.pmca.webcontrol;

import android.util.Pair;

import com.sony.scalar.hardware.CameraEx;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
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
                File target = null;
                if (path != null && !path.trim().isEmpty()) {
                    target = new File(path.trim());
                } else {
                    target = m_activity.getLatestPhotoFile();
                }
                if (target == null || !target.exists() || !target.isFile()) {
                    return jsonError(Response.Status.NOT_FOUND, "Photo file not found");
                }
                return serveFileDownload(target);
            }

            if ("/api/log".equals(uri)) {
                return addCors(new Response(Response.Status.OK, MIME_PLAINTEXT, m_activity.getCrashLogText()));
            }

            if (uri.startsWith("/api/camera/")) {
                String action = uri.substring("/api/camera/".length());
                return handleCameraAction(action, params);
            }

            if ("/api/modifier".equals(uri) || uri.startsWith("/api/modifier/")) {
                return handleModifier(uri, method, params, session);
            }

            if ("/api/params".equals(uri) || uri.startsWith("/api/params/")) {
                return handleRawParams(uri, method, params, session);
            }

            return jsonError(Response.Status.NOT_FOUND, "Unknown endpoint: " + uri);
        } catch (TimeoutException te) {
            return jsonError(STATUS_GATEWAY_TIMEOUT, te.getMessage());
        } catch (Throwable t) {
            Throwable cause = t.getCause() != null ? t.getCause() : t;
            return jsonError(Response.Status.INTERNAL_ERROR, cause.toString());
        }
    }

    private Response handleCameraAction(final String action, final Map<String, String> params) throws Exception {
        final CameraEx cam = m_activity.getCameraEx();
        if (cam == null) {
            return jsonError(Response.Status.INTERNAL_ERROR, "Camera not initialized");
        }

        if ("capabilities".equals(action)) {
            JSONObject res = m_activity.runOnMainSync(new Callable<JSONObject>() {
                @Override
                public JSONObject call() {
                    JSONObject lens = m_activity.buildLensInfoJson();
                    return m_activity.getModifierProxy().getCapabilities(cam, lens);
                }
            }, MacroServerActivity.MAIN_THREAD_TIMEOUT_MS);
            return jsonOk(res.toString());
        }

        if ("getLensInfo".equals(action)) {
            JSONObject res = m_activity.runOnMainSync(new Callable<JSONObject>() {
                @Override
                public JSONObject call() throws Exception {
                    JSONObject out = new JSONObject();
                    out.put("ok", true);
                    out.put("lens", m_activity.buildLensInfoJson());
                    return out;
                }
            }, MacroServerActivity.MAIN_THREAD_TIMEOUT_MS);
            return jsonOk(res.toString());
        }

        if ("getInhibitionInfo".equals(action)) {
            JSONObject res = m_activity.runOnMainSync(new Callable<JSONObject>() {
                @Override
                public JSONObject call() throws Exception {
                    JSONObject out = new JSONObject();
                    out.put("ok", true);
                    out.put("inhibitionInfo", cam.getInhibitionInfo());
                    return out;
                }
            }, MacroServerActivity.MAIN_THREAD_TIMEOUT_MS);
            return jsonOk(res.toString());
        }

        JSONObject res = m_activity.runOnMainSync(new Callable<JSONObject>() {
            @Override
            public JSONObject call() throws Exception {
                JSONObject out = new JSONObject();
                out.put("ok", true);
                out.put("action", action);

                if ("incrementAperture".equals(action)) {
                    cam.incrementAperture();
                } else if ("decrementAperture".equals(action)) {
                    cam.decrementAperture();
                } else if ("adjustAperture".equals(action)) {
                    int n = parseInt(firstNonNull(params.get("n"), params.get("steps")), 0);
                    cam.adjustAperture(n);
                } else if ("incrementShutterSpeed".equals(action)) {
                    cam.incrementShutterSpeed();
                } else if ("decrementShutterSpeed".equals(action)) {
                    cam.decrementShutterSpeed();
                } else if ("adjustShutterSpeed".equals(action)) {
                    int n = parseInt(firstNonNull(params.get("n"), params.get("steps")), 0);
                    cam.adjustShutterSpeed(n);
                } else if ("adjustProgramLine".equals(action)) {
                    int n = parseInt(firstNonNull(params.get("n"), params.get("steps")), 0);
                    cam.adjustProgramLine(n);
                } else if ("startOneShotFocusDrive".equals(action)) {
                    int dir = parseInt(params.get("dir"), CameraEx.FOCUS_DRIVE_DIRECTION_FAR);
                    int speed = parseInt(params.get("speed"), 2);
                    cam.startOneShotFocusDrive(dir, speed);
                } else if ("shiftFocusPosition".equals(action)) {
                    int n = parseInt(params.get("n"), 0);
                    boolean moved = cam.shiftFocusPosition(n);
                    out.put("result", moved);
                } else if ("startFocusHold".equals(action)) {
                    cam.startFocusHold();
                } else if ("stopFocusHold".equals(action)) {
                    cam.stopFocusHold();
                } else if ("executeAutoFocusStartTrigger".equals(action)) {
                    boolean halfPress = !"false".equalsIgnoreCase(params.get("halfPress"));
                    String mode = params.get("mode");
                    cam.executeAutoFocusStartTrigger(halfPress, mode);
                } else if ("startZoom".equals(action)) {
                    int dir = parseInt(params.get("dir"), CameraEx.ZOOM_DIRECTION_TELE);
                    int speed = parseInt(params.get("speed"), 2);
                    cam.startZoom(dir, speed);
                } else if ("stopZoom".equals(action)) {
                    cam.stopZoom();
                } else if ("setDigitalZoom".equals(action)) {
                    int v = parseInt(params.get("v"), 100);
                    cam.setDigitalZoom(v);
                } else if ("resetDigitalZoom".equals(action)) {
                    cam.resetDigitalZoom();
                } else if ("setPreviewMagnification".equals(action)) {
                    int ratio = parseInt(params.get("ratio"), 0);
                    int x = parseInt(params.get("x"), 0);
                    int y = parseInt(params.get("y"), 0);
                    cam.setPreviewMagnification(ratio, new Pair<Integer, Integer>(x, y));
                } else if ("stopPreviewMagnification".equals(action)) {
                    cam.stopPreviewMagnification();
                } else if ("burstableTakePicture".equals(action)) {
                    cam.burstableTakePicture();
                } else if ("cancelTakePicture".equals(action)) {
                    cam.cancelTakePicture();
                } else if ("cancelExposure".equals(action)) {
                    cam.cancelExposure();
                } else {
                    throw new IllegalArgumentException("Unknown camera action: " + action);
                }
                return out;
            }
        }, MacroServerActivity.MAIN_THREAD_TIMEOUT_MS);

        return jsonOk(res.toString());
    }

    private Response handleModifier(String uri, Method method, final Map<String, String> params, IHTTPSession session) throws Exception {
        final CameraEx cam = m_activity.getCameraEx();
        if (cam == null) {
            return jsonError(Response.Status.INTERNAL_ERROR, "Camera not initialized");
        }
        final ModifierProxy proxy = m_activity.getModifierProxy();

        if ("/api/modifier".equals(uri)) {
            if (Method.GET.equals(method)) {
                final String getList = params.get("get");
                if (getList == null || getList.trim().isEmpty()) {
                    return jsonOk(proxy.listMethodsJson().toString());
                }
                final List<String> names = Arrays.asList(getList.split(","));
                JSONObject res = m_activity.runOnMainSync(new Callable<JSONObject>() {
                    @Override
                    public JSONObject call() throws Exception {
                        return proxy.invokeBatchGet(cam, names);
                    }
                }, MacroServerActivity.MAIN_THREAD_TIMEOUT_MS);
                return jsonOk(res.toString());
            } else if (Method.POST.equals(method)) {
                final JSONObject body = parseJsonBody(session);
                JSONObject res = m_activity.runOnMainSync(new Callable<JSONObject>() {
                    @Override
                    public JSONObject call() throws Exception {
                        return proxy.invokeBatchSet(cam, body);
                    }
                }, MacroServerActivity.MAIN_THREAD_TIMEOUT_MS);
                return jsonOk(res.toString());
            }
            return jsonError(Response.Status.METHOD_NOT_ALLOWED, "Unsupported method");
        }

        final String methodName = uri.substring("/api/modifier/".length());
        if (!proxy.hasMethod(methodName)) {
            return jsonError(Response.Status.NOT_FOUND, "Unknown modifier method: " + methodName);
        }

        final List<String> rawArgs = extractArgs(params, Method.POST.equals(method) ? session : null);
        JSONObject res = m_activity.runOnMainSync(new Callable<JSONObject>() {
            @Override
            public JSONObject call() throws Exception {
                if (methodName.startsWith("set")) {
                    return proxy.invokeSet(cam, methodName, rawArgs);
                }
                return proxy.invokeGet(cam, methodName, rawArgs);
            }
        }, MacroServerActivity.MAIN_THREAD_TIMEOUT_MS);
        return jsonOk(res.toString());
    }

    private Response handleRawParams(String uri, Method method, final Map<String, String> params, IHTTPSession session) throws Exception {
        final CameraEx cam = m_activity.getCameraEx();
        if (cam == null) {
            return jsonError(Response.Status.INTERNAL_ERROR, "Camera not initialized");
        }

        if (Method.GET.equals(method)) {
            final String key = uri.length() > "/api/params/".length() ? uri.substring("/api/params/".length()) : null;
            JSONObject res = m_activity.runOnMainSync(new Callable<JSONObject>() {
                @Override
                public JSONObject call() throws Exception {
                    JSONObject all = m_activity.getRawParamsJson();
                    JSONObject out = new JSONObject();
                    out.put("ok", true);
                    if (key != null && !key.isEmpty()) {
                        out.put("key", key);
                        out.put("value", all.has(key) ? all.get(key) : JSONObject.NULL);
                    } else {
                        out.put("params", all);
                    }
                    return out;
                }
            }, MacroServerActivity.MAIN_THREAD_TIMEOUT_MS);
            return jsonOk(res.toString());
        }

        if (Method.POST.equals(method)) {
            final JSONObject updates = parseJsonBody(session);
            for (Map.Entry<String, String> e : params.entrySet()) {
                if (!"NanoHttpd.QUERY_STRING".equals(e.getKey()) && !updates.has(e.getKey())) {
                    updates.put(e.getKey(), e.getValue());
                }
            }
            JSONObject res = m_activity.runOnMainSync(new Callable<JSONObject>() {
                @Override
                public JSONObject call() throws Exception {
                    m_activity.setRawParams(updates);
                    JSONObject out = new JSONObject();
                    out.put("ok", true);
                    return out;
                }
            }, MacroServerActivity.MAIN_THREAD_TIMEOUT_MS);
            return jsonOk(res.toString());
        }

        return jsonError(Response.Status.METHOD_NOT_ALLOWED, "Unsupported method");
    }

    private List<String> extractArgs(Map<String, String> params, IHTTPSession postSession) throws Exception {
        List<String> args = new ArrayList<String>();
        if (params.containsKey("v")) {
            args.add(params.get("v"));
            return args;
        }
        if (params.containsKey("arg0")) {
            for (int i = 0; i < 5; i++) {
                String k = "arg" + i;
                if (!params.containsKey(k)) break;
                args.add(params.get(k));
            }
            return args;
        }
        if (postSession != null) {
            JSONObject body = parseJsonBody(postSession);
            if (body.has("v")) {
                args.add(String.valueOf(body.get("v")));
            } else if (body.has("args") && body.get("args") instanceof JSONArray) {
                JSONArray ja = body.getJSONArray("args");
                for (int i = 0; i < ja.length(); i++) {
                    args.add(String.valueOf(ja.get(i)));
                }
            }
        }
        return args;
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

    private static String firstNonNull(String a, String b) {
        return a != null ? a : b;
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

package com.a6000.aimacro;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.util.List;
import java.util.Map;

import fi.iki.elonen.NanoHTTPD;

public class MacroHttpServer extends NanoHTTPD {
    public static final int DEFAULT_PORT = 8080;

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

        if ("/".equals(uri) || "/index.html".equals(uri)) {
            return addCors(new Response(Response.Status.OK, "text/html; charset=utf-8", WEB_UI_HTML));
        }

        if ("/api/status".equals(uri)) {
            return jsonOk(m_activity.getStatusJson());
        }

        if ("/api/log".equals(uri)) {
            return addCors(new Response(Response.Status.OK, MIME_PLAINTEXT, m_activity.getCrashLogText()));
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
            // First check memory buffer from last shutter event
            byte[] inMem = m_activity.getLastCapturedJpeg();
            if (inMem != null && inMem.length > 0) {
                Response res = new Response(Response.Status.OK, "image/jpeg", new ByteArrayInputStream(inMem));
                res.addHeader("Content-Disposition", "attachment; filename=\"latest_capture.jpg\"");
                return addCors(res);
            }
            // Fallback to SD card file
            File latest = m_activity.getLatestPhotoFile();
            if (latest != null && latest.exists() && latest.isFile()) {
                return serveFileDownload(latest);
            }
            return jsonError(Response.Status.NOT_FOUND, "No captured photo available yet");
        }

        if ("/api/photos".equals(uri)) {
            int limit = parseInt(params.get("limit"), 50);
            List<File> list = m_activity.listPhotosOnSdCard(limit);
            StringBuilder sb = new StringBuilder();
            sb.append("{\"ok\":true,\"count\":").append(list.size()).append(",\"photos\":[");
            for (int i = 0; i < list.size(); i++) {
                File f = list.get(i);
                if (i > 0) sb.append(",");
                sb.append("{");
                sb.append("\"name\":\"").append(f.getName()).append("\",");
                sb.append("\"path\":\"").append(f.getAbsolutePath().replace("\\", "/")).append("\",");
                sb.append("\"size\":").append(f.length()).append(",");
                sb.append("\"modified\":").append(f.lastModified());
                sb.append("}");
            }
            sb.append("]}");
            return jsonOk(sb.toString());
        }

        if ("/api/photos/download".equals(uri)) {
            String path = params.get("path");
            File target = null;
            if (path != null && !path.trim().isEmpty()) {
                target = new File(path.trim());
            } else {
                target = m_activity.getLatestPhotoFile();
            }
            if (target == null || !target.exists() || !target.isFile()) {
                return jsonError(Response.Status.NOT_FOUND, "Photo file not found: " + path);
            }
            return serveFileDownload(target);
        }

        if ("/api/focus/drive".equals(uri)) {
            String dir = params.get("dir");
            boolean far = !"near".equalsIgnoreCase(dir);
            int speed = parseInt(params.get("speed"), 1);
            m_activity.driveFocusStep(far, speed);
            return jsonOk("{\"ok\":true,\"action\":\"focus_drive\"}");
        }

        if ("/api/focus/goto".equals(uri)) {
            int pos = parseInt(params.get("pos"), -1);
            if (pos < 0) {
                return jsonError(Response.Status.BAD_REQUEST, "Missing or invalid 'pos'");
            }
            m_activity.gotoFocusPosition(pos);
            return jsonOk("{\"ok\":true,\"action\":\"focus_goto\",\"target\":" + pos + "}");
        }

        if ("/api/zoom".equals(uri)) {
            String dir = params.get("dir");
            String action = params.get("action");
            Integer mag = params.containsKey("mag") ? parseIntObj(params.get("mag")) : null;
            Integer previewMag = params.containsKey("magnify") ? parseIntObj(params.get("magnify")) : null;
            m_activity.controlZoom(dir, action, mag, previewMag);
            return jsonOk("{\"ok\":true,\"action\":\"zoom\"}");
        }

        if ("/api/bracket".equals(uri)) {
            int min = parseInt(params.get("min"), -1);
            int max = parseInt(params.get("max"), -1);
            int shots = parseInt(params.get("shots"), 10);
            if (min < 0 || max < 0) {
                return jsonError(Response.Status.BAD_REQUEST, "Missing 'min' or 'max'");
            }
            m_activity.startBracket(min, max, shots);
            return jsonOk("{\"ok\":true,\"action\":\"bracket_started\"}");
        }

        if ("/api/bracket/abort".equals(uri)) {
            m_activity.abortBracket();
            return jsonOk("{\"ok\":true,\"action\":\"bracket_aborted\"}");
        }

        if ("/api/capture".equals(uri)) {
            m_activity.captureSingle();
            return jsonOk("{\"ok\":true,\"action\":\"capture\"}");
        }

        if ("/api/exposure".equals(uri)) {
            String ap = params.get("aperture");
            String ss = params.get("shutter");
            Integer iso = params.containsKey("iso") ? parseIntObj(params.get("iso")) : null;
            m_activity.adjustExposure(ap, ss, iso);
            return jsonOk("{\"ok\":true,\"action\":\"exposure_updated\"}");
        }

        return jsonError(Response.Status.NOT_FOUND, "Unknown endpoint: " + uri);
    }

    private Response serveFileDownload(File file) {
        try {
            FileInputStream fis = new FileInputStream(file);
            String nameLower = file.getName().toLowerCase();
            String mime = "application/octet-stream";
            if (nameLower.endsWith(".jpg") || nameLower.endsWith(".jpeg")) {
                mime = "image/jpeg";
            } else if (nameLower.endsWith(".arw")) {
                mime = "image/x-sony-arw";
            }
            Response res = new Response(Response.Status.OK, mime, fis);
            res.addHeader("Content-Disposition", "attachment; filename=\"" + file.getName() + "\"");
            res.addHeader("Content-Length", String.valueOf(file.length()));
            return addCors(res);
        } catch (FileNotFoundException e) {
            return jsonError(Response.Status.NOT_FOUND, "File not found: " + e.getMessage());
        }
    }

    private Response jsonOk(String json) {
        return addCors(new Response(Response.Status.OK, "application/json", json));
    }

    private Response jsonError(Response.Status status, String msg) {
        return addCors(new Response(status, "application/json", "{\"ok\":false,\"error\":\"" + msg + "\"}"));
    }

    private Response addCors(Response r) {
        r.addHeader("Access-Control-Allow-Origin", "*");
        r.addHeader("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
        r.addHeader("Access-Control-Allow-Headers", "Content-Type");
        return r;
    }

    private static int parseInt(String s, int def) {
        if (s == null) {
            return def;
        }
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static Integer parseIntObj(String s) {
        if (s == null) {
            return null;
        }
        try {
            return Integer.valueOf(s.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static final String WEB_UI_HTML =
            "<!DOCTYPE html><html><head><meta charset='utf-8'>"
            + "<meta name='viewport' content='width=device-width,initial-scale=1'>"
            + "<title>Sony PMCA WebControl</title>"
            + "<style>"
            + "body{font-family:system-ui,sans-serif;background:#111;color:#eee;margin:0;padding:12px;text-align:center}"
            + ".wrap{max-width:760px;margin:0 auto}"
            + "img{width:100%;max-height:460px;min-height:240px;object-fit:contain;background:#000;border:1px solid #333;border-radius:6px;display:block}"
            + ".card{background:#1c1c1e;border-radius:8px;padding:12px;margin-top:10px}"
            + ".row{display:flex;flex-wrap:wrap;gap:8px;justify-content:center;align-items:center;margin:8px 0}"
            + "button,a.btn{background:#2c2c2e;color:#fff;border:1px solid #444;border-radius:6px;padding:8px 14px;font-size:14px;cursor:pointer;text-decoration:none;display:inline-block}"
            + "button:hover,a.btn:hover{background:#3a3a3c}button.pri,a.btn.pri{background:#0a84ff;border-color:#0a84ff}"
            + "button.succ,a.btn.succ{background:#30d158;border-color:#30d158;color:#000;font-weight:600}"
            + "button.warn,a.btn.warn{background:#ff453a;border-color:#ff453a}"
            + "input{background:#2c2c2e;color:#fff;border:1px solid #444;border-radius:6px;padding:6px;width:68px;text-align:center}"
            + ".stat{font-family:monospace;font-size:13px;color:#30d158}"
            + "</style></head><body><div class='wrap'>"
            + "<h3>Sony PMCA WebControl</h3>"
            + "<img id='lv' alt='Waiting for LiveView...'/>"
            + "<div class='card'><div id='st' class='stat'>Loading...</div></div>"
            + "<div class='card'><b>Manual Focus &amp; Bracketing</b><div class='row'>"
            + "<button onclick=\"api('/api/focus/drive?dir=near&speed=4')\">&#9664;&#9664;&#9664; (-12)</button>"
            + "<button onclick=\"api('/api/focus/drive?dir=near&speed=2')\">&#9664;&#9664; (-3)</button>"
            + "<button onclick=\"api('/api/focus/drive?dir=near&speed=1')\">&#9664; Near (-1)</button>"
            + "<button onclick=\"api('/api/focus/drive?dir=far&speed=1')\">Far (+1) &#9654;</button>"
            + "<button onclick=\"api('/api/focus/drive?dir=far&speed=2')\">(+3) &#9654;&#9654;</button>"
            + "<button onclick=\"api('/api/focus/drive?dir=far&speed=4')\">(+12) &#9654;&#9654;&#9654;</button>"
            + "</div><div class='row'>"
            + "<span>Pos:</span><input id='fPos' type='number' value='0'/>"
            + "<button onclick=\"api('/api/focus/goto?pos='+document.getElementById('fPos').value)\">Go to Pos</button>"
            + "<button onclick=\"api('/api/zoom?magnify=100')\">MF Assist</button>"
            + "<button onclick=\"api('/api/zoom?magnify=0')\">Assist Off</button>"
            + "</div><div class='row'>"
            + "<button onclick='setNear()'>Set Near</button>"
            + "<input id='bMin' type='number' value='0'/>"
            + "<span>to</span>"
            + "<input id='bMax' type='number' value='100'/>"
            + "<button onclick='setFar()'>Set Far</button>"
            + "<span>Shots:</span><input id='bShots' type='number' value='15'/>"
            + "<button class='pri' onclick='startStack()'>Start Stack</button>"
            + "<button class='warn' onclick=\"api('/api/bracket/abort')\">Abort</button>"
            + "</div></div>"
            + "<div class='card'><b>Zoom (Optical PZ &amp; Digital)</b><div class='row'>"
            + "<button onclick=\"api('/api/zoom?dir=out&action=step')\">Zoom Out (W)</button>"
            + "<button onclick=\"api('/api/zoom?dir=in&action=step')\">Zoom In (T)</button>"
            + "<button onclick=\"api('/api/zoom?mag=100')\">1.0x</button>"
            + "<button onclick=\"api('/api/zoom?mag=140')\">1.4x</button>"
            + "<button onclick=\"api('/api/zoom?mag=200')\">2.0x</button>"
            + "<button onclick=\"api('/api/zoom?mag=400')\">4.0x</button>"
            + "</div></div>"
            + "<div class='card'><b>Exposure, Capture &amp; Download</b><div class='row'>"
            + "<button onclick=\"api('/api/exposure?aperture=dec')\">F -</button>"
            + "<button onclick=\"api('/api/exposure?aperture=inc')\">F +</button>"
            + "<button onclick=\"api('/api/exposure?shutter=dec')\">Shutter -</button>"
            + "<button onclick=\"api('/api/exposure?shutter=inc')\">Shutter +</button>"
            + "<button onclick=\"api('/api/exposure?iso=0')\">ISO AUTO</button>"
            + "<button onclick=\"api('/api/exposure?iso=100')\">ISO 100</button>"
            + "<button onclick=\"api('/api/exposure?iso=400')\">ISO 400</button>"
            + "<button onclick=\"api('/api/exposure?iso=1600')\">ISO 1600</button>"
            + "</div><div class='row'>"
            + "<button class='pri' onclick=\"api('/api/capture')\">Take Photo</button>"
            + "<a class='btn succ' href='/api/photos/latest' download='photo_latest.jpg'>&#11015; Download Last Photo</a>"
            + "</div></div></div>"
            + "<script>"
            + "var curFocus=0,lv=document.getElementById('lv'),prevUrl=null;"
            + "function api(u){fetch(u,{method:'POST'}).catch(console.error);}"
            + "function setNear(){document.getElementById('bMin').value=curFocus;}"
            + "function setFar(){document.getElementById('bMax').value=curFocus;}"
            + "function startStack(){"
            + "var a=document.getElementById('bMin').value,b=document.getElementById('bMax').value,c=document.getElementById('bShots').value;"
            + "api('/api/bracket?min='+a+'&max='+b+'&shots='+c);}"
            + "function pullLv(){"
            + "fetch('/api/liveview.jpg?t='+Date.now()).then(function(r){if(!r.ok)throw new Error();return r.blob();})"
            + ".then(function(b){var u=URL.createObjectURL(b);lv.src=u;if(prevUrl)URL.revokeObjectURL(prevUrl);prevUrl=u;setTimeout(pullLv,90);})"
            + ".catch(function(){setTimeout(pullLv,400);});}"
            + "pullLv();"
            + "setInterval(function(){fetch('/api/status').then(r=>r.json()).then(d=>{"
            + "curFocus=d.currentFocus;"
            + "var isoStr=d.iso===0?'AUTO':d.iso;"
            + "var zStr=((d.opticalZoom||100)*(d.digitalZoom||100)/10000).toFixed(1)+'x';"
            + "document.getElementById('st').innerText="
            + "'State: '+d.state+' | Focus: '+d.currentFocus+'/'+d.maxFocus+' | '+d.apertureFormatted+' | '+d.shutterSpeed+' | ISO '+isoStr+' | Zoom '+zStr+' | Left: '+d.remainingShots;"
            + "}).catch(()=>{});},350);"
            + "</script></body></html>";
}

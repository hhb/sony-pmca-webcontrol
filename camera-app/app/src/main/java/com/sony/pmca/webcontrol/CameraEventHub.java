package com.sony.pmca.webcontrol;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.LinkedList;

/**
 * Thread-safe event ring buffer and latest state snapshot store.
 * Supports long-polling via waitForEvents(sinceSeq, timeoutSec).
 */
public class CameraEventHub {
    private static final int MAX_EVENTS = 256;

    private final Object m_lock = new Object();
    private final LinkedList<JSONObject> m_events = new LinkedList<JSONObject>();
    private long m_seq = 0;

    // Latest state snapshots
    private JSONObject m_aperture = new JSONObject();
    private JSONObject m_shutter = new JSONObject();
    private JSONObject m_focus = new JSONObject();
    private JSONObject m_zoom = new JSONObject();
    private int m_autoIso = 0;
    private int m_focalLength = 0;
    private int m_powerZoom = -1;
    private JSONObject m_previewMag = new JSONObject();
    private JSONObject m_lastStoreImage = new JSONObject();
    private int m_lastShutterStatus = -1;
    private boolean m_capturing = false;
    private JSONObject m_lens = new JSONObject();
    private JSONObject m_exposureCompensation = new JSONObject();
    private String m_wifiIp = "0.0.0.0";
    private int m_httpPort = 8080;

    public long getSeq() {
        synchronized (m_lock) {
            return m_seq;
        }
    }

    public void setWifiInfo(String ip, int port) {
        synchronized (m_lock) {
            m_wifiIp = ip != null ? ip : "0.0.0.0";
            m_httpPort = port;
        }
    }

    public void setLensInfo(JSONObject lens) {
        synchronized (m_lock) {
            m_lens = lens != null ? lens : new JSONObject();
        }
    }

    public void setCapturing(boolean capturing) {
        synchronized (m_lock) {
            m_capturing = capturing;
        }
    }

    public void setLastShutterStatus(int status) {
        synchronized (m_lock) {
            m_lastShutterStatus = status;
        }
    }

    public void setAutoIso(int iso) {
        synchronized (m_lock) {
            m_autoIso = iso;
        }
    }

    public void setFocalLength(int fl) {
        synchronized (m_lock) {
            m_focalLength = fl;
        }
    }

    public void setPowerZoom(int status) {
        synchronized (m_lock) {
            m_powerZoom = status;
        }
    }

    public void updateAperture(JSONObject data) {
        synchronized (m_lock) {
            m_aperture = data;
        }
    }

    public void updateExposureCompensation(JSONObject data) {
        synchronized (m_lock) {
            m_exposureCompensation = data != null ? data : new JSONObject();
        }
    }

    public void updateShutter(JSONObject data) {
        synchronized (m_lock) {
            m_shutter = data;
        }
    }

    public void updateFocus(JSONObject data) {
        synchronized (m_lock) {
            m_focus = data;
        }
    }

    public void updateZoom(JSONObject data) {
        synchronized (m_lock) {
            m_zoom = data;
        }
    }

    public void updatePreviewMag(JSONObject data) {
        synchronized (m_lock) {
            m_previewMag = data;
        }
    }

    public void updateLastStoreImage(JSONObject data) {
        synchronized (m_lock) {
            m_lastStoreImage = data;
        }
    }

    public long emit(String type, JSONObject data) {
        synchronized (m_lock) {
            m_seq++;
            JSONObject ev = new JSONObject();
            try {
                ev.put("seq", m_seq);
                ev.put("t", System.currentTimeMillis());
                ev.put("type", type);
                ev.put("data", data != null ? data : new JSONObject());
            } catch (Throwable ignored) {
            }
            m_events.addLast(ev);
            while (m_events.size() > MAX_EVENTS) {
                m_events.removeFirst();
            }
            m_lock.notifyAll();
            return m_seq;
        }
    }

    public JSONObject getStateSnapshot() {
        synchronized (m_lock) {
            JSONObject root = new JSONObject();
            try {
                root.put("ok", true);
                root.put("seq", m_seq);
                root.put("aperture", m_aperture);
                root.put("shutter", m_shutter);
                root.put("focus", m_focus);
                root.put("zoom", m_zoom);
                root.put("autoIso", m_autoIso);
                root.put("focalLength", m_focalLength);
                root.put("powerZoom", m_powerZoom);
                root.put("previewMagnification", m_previewMag);
                root.put("lastStoreImage", m_lastStoreImage);
                root.put("lastShutterStatus", m_lastShutterStatus);
                root.put("capturing", m_capturing);
                root.put("lens", m_lens);
                root.put("exposureCompensation", m_exposureCompensation);

                JSONObject wifi = new JSONObject();
                wifi.put("ip", m_wifiIp);
                wifi.put("port", m_httpPort);
                root.put("wifi", wifi);
            } catch (Throwable ignored) {
            }
            return root;
        }
    }

    public JSONObject waitForEvents(long sinceSeq, int timeoutSec) {
        long clampedTimeoutMs = Math.max(0, Math.min(30, timeoutSec)) * 1000L;
        long deadline = System.currentTimeMillis() + clampedTimeoutMs;

        synchronized (m_lock) {
            while (m_seq <= sinceSeq && clampedTimeoutMs > 0) {
                long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0) {
                    break;
                }
                try {
                    m_lock.wait(remaining);
                } catch (InterruptedException e) {
                    break;
                }
            }

            JSONArray arr = new JSONArray();
            for (JSONObject ev : m_events) {
                long s = ev.optLong("seq", 0);
                if (s > sinceSeq) {
                    arr.put(ev);
                }
            }

            JSONObject res = new JSONObject();
            try {
                res.put("ok", true);
                res.put("seq", m_seq);
                res.put("events", arr);
            } catch (Throwable ignored) {
            }
            return res;
        }
    }
}

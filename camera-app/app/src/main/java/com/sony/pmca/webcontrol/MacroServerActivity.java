package com.sony.pmca.webcontrol;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.hardware.Camera;
import android.net.NetworkInfo;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.BatteryManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.format.Formatter;
import android.util.Pair;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.widget.TextView;

import com.github.ma1co.pmcademo.app.BaseActivity;
import com.github.ma1co.pmcademo.app.Logger;
import com.sony.scalar.hardware.CameraEx;

import org.json.JSONObject;

import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

public class MacroServerActivity extends BaseActivity implements SurfaceHolder.Callback {

    public static final long MAIN_THREAD_TIMEOUT_MS = 3000L;

    private SurfaceHolder m_surfaceHolder;
    private CameraEx m_camera;
    private CameraEx.AutoPictureReviewControl m_autoReviewControl;
    private int m_pictureReviewTime;

    private final Handler m_handler = new Handler(Looper.getMainLooper());
    private final CameraEventHub m_eventHub = new CameraEventHub();
    private final RpcDispatcher m_rpc = new RpcDispatcher();
    private final LiveViewManager m_liveViewManager = new LiveViewManager();

    private FocusScaleView m_focusScaleView;
    private BatteryView m_batteryView;
    private TextView m_tvCameraStatus;
    private TextView m_tvBattery;
    private TextView m_tvWifiStatus;

    private WifiManager m_wifiManager;
    private BroadcastReceiver m_networkStateReceiver;
    private BroadcastReceiver m_batteryReceiver;
    private MacroHttpServer m_httpServer;
    private int m_httpPort = MacroHttpServer.DEFAULT_PORT;
    private String m_wifiIp = "0.0.0.0";

    // Cached display values for on-camera LCD
    private volatile int m_curFocus = 0;
    private volatile int m_maxFocus = 0;
    private volatile int m_aperture = 0;
    private volatile String m_shutterSpeed = "--";
    private volatile int m_iso = 0;
    private volatile int m_digitalZoom = 100;

    public CameraEventHub getEventHub() {
        return m_eventHub;
    }

    public RpcDispatcher getRpcDispatcher() {
        return m_rpc;
    }

    public CameraEx getCameraEx() {
        return m_camera;
    }

    public LiveViewManager getLiveViewManager() {
        return m_liveViewManager;
    }

    private final CameraGoalController m_goalController = new CameraGoalController(this);

    public CameraGoalController getGoalController() {
        return m_goalController;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Thread.setDefaultUncaughtExceptionHandler(new CustomExceptionHandler());
        setContentView(R.layout.activity_macro_server);

        SurfaceView surfaceView = (SurfaceView) findViewById(R.id.surfaceView);
        m_surfaceHolder = surfaceView.getHolder();
        m_surfaceHolder.setType(SurfaceHolder.SURFACE_TYPE_PUSH_BUFFERS);

        m_focusScaleView = (FocusScaleView) findViewById(R.id.vFocusScale);
        m_batteryView = (BatteryView) findViewById(R.id.vBattery);
        m_tvCameraStatus = (TextView) findViewById(R.id.tvCameraStatus);
        m_tvBattery = (TextView) findViewById(R.id.tvBattery);
        m_tvWifiStatus = (TextView) findViewById(R.id.tvWifiStatus);

        m_wifiManager = (WifiManager) getSystemService(Context.WIFI_SERVICE);
        m_networkStateReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                updateWifiStatus();
            }
        };
        m_batteryReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                updateBatteryView(intent);
            }
        };
    }

    @Override
    protected void onResume() {
        super.onResume();
        setAutoPowerOffMode(false);

        registerReceiver(m_networkStateReceiver, new IntentFilter(WifiManager.NETWORK_STATE_CHANGED_ACTION));
        Intent stickyBattery = registerReceiver(m_batteryReceiver, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (stickyBattery != null) {
            updateBatteryView(stickyBattery);
        }
        try {
            m_wifiManager.setWifiEnabled(true);
        } catch (Throwable t) {
            Logger.error("Wi-Fi init error: " + t.toString());
        }

        startHttpServerWithFallback();

        try {
            m_camera = CameraEx.open(0, null);
            m_surfaceHolder.addCallback(this);
            m_autoReviewControl = new CameraEx.AutoPictureReviewControl();
            m_camera.setAutoPictureReviewControl(m_autoReviewControl);

            registerCameraListeners();
            initCameraDefaults();
            refreshInitialState();
            updateStatusText();
        } catch (Throwable t) {
            Logger.error("onResume camera init error: " + t.toString());
            m_tvCameraStatus.setText("Init error: " + t.getMessage());
        }
    }

    private void registerCameraListeners() {
        m_camera.setShutterListener(new CameraEx.ShutterListener() {
            @Override
            public void onShutter(int status, CameraEx cameraEx) {
                try {
                    m_camera.cancelTakePicture();
                } catch (Throwable ignored) {
                }
                m_eventHub.setState("lastShutterStatus", status);
                JSONObject data = new JSONObject();
                try {
                    data.put("status", status);
                } catch (Throwable ignored) {
                }
                m_eventHub.emit("Shutter", data);
                updateStatusText();
            }
        });

        try {
            m_camera.setCaptureStatusListener(new CameraEx.OnCaptureStatusListener() {
                @Override
                public void onStart(int reason, CameraEx cameraEx) {
                    m_eventHub.setState("capturing", true);
                    JSONObject data = new JSONObject();
                    try {
                        data.put("capturing", true);
                        data.put("reason", reason);
                    } catch (Throwable ignored) {
                    }
                    m_eventHub.emit("CaptureStart", data);
                }

                @Override
                public void onEnd(int reason, int arg1, CameraEx cameraEx) {
                    m_eventHub.setState("capturing", false);
                    JSONObject data = new JSONObject();
                    try {
                        data.put("capturing", false);
                        data.put("reason", reason);
                    } catch (Throwable ignored) {
                    }
                    m_eventHub.emit("CaptureEnd", data);
                }
            });
        } catch (Throwable ignored) {
        }

        m_camera.setJpegListener(new CameraEx.JpegListener() {
            @Override
            public void onPictureTaken(byte[] data, CameraEx cameraEx) {
                if (data != null) {
                    m_liveViewManager.onPictureJpeg(data);
                    JSONObject ev = new JSONObject();
                    try {
                        ev.put("size", data.length);
                    } catch (Throwable ignored) {
                    }
                    m_eventHub.emit("Jpeg", ev);
                }
            }
        });

        try {
            m_camera.setStoreImageCompleteListener(new CameraEx.StoreImageCompleteListener() {
                @Override
                public void onDone(int status, CameraEx.StoreImageInfo info, CameraEx cameraEx) {
                    JSONObject data = new JSONObject();
                    try {
                        data.put("status", status);
                        if (info != null) {
                            data.put("DirectoryName", info.DirectoryName);
                            data.put("FileName", info.FileName);
                            data.put("DirectoryNo", info.DirectoryNo);
                            data.put("FileNo", info.FileNo);
                            data.put("MediaId", info.MediaId);
                        }
                    } catch (Throwable ignored) {
                    }
                    m_eventHub.setState("lastStoreImage", data);
                    m_eventHub.emit("StoreImageComplete", data);
                }
            });
        } catch (Throwable ignored) {
        }

        m_camera.setFocusDriveListener(new CameraEx.FocusDriveListener() {
            @Override
            public void onChanged(CameraEx.FocusPosition focusPosition, CameraEx cameraEx) {
                if (focusPosition == null) return;
                m_maxFocus = focusPosition.maxPosition;
                m_curFocus = focusPosition.currentPosition;
                m_focusScaleView.setMaxPosition(m_maxFocus);
                m_focusScaleView.setCurPosition(m_curFocus);

                JSONObject data = new JSONObject();
                try {
                    data.put("currentPosition", m_curFocus);
                    data.put("maxPosition", m_maxFocus);
                } catch (Throwable ignored) {
                }
                m_eventHub.setState("focus", data);
                m_eventHub.emit("FocusDrive", data);
                m_goalController.onFocusChanged(m_curFocus, m_maxFocus);
                updateStatusText();
            }
        });

        try {
            m_camera.setAutoFocusStartListener(new CameraEx.AutoFocusStartListener() {
                @Override
                public void onStart(CameraEx cameraEx) {
                    m_eventHub.emit("AutoFocusStart", new JSONObject());
                }
            });
            m_camera.setAutoFocusDoneListener(new CameraEx.AutoFocusDoneListener() {
                @Override
                public void onDone(int status, int[] focusAreaIds, CameraEx cameraEx) {
                    JSONObject data = new JSONObject();
                    try {
                        data.put("status", status);
                        data.put("focusAreaIds", RpcDispatcher.toJsonValue(focusAreaIds));
                    } catch (Throwable ignored) {
                    }
                    m_eventHub.emit("AutoFocusDone", data);
                }
            });
        } catch (Throwable ignored) {
        }

        m_camera.setApertureChangeListener(new CameraEx.ApertureChangeListener() {
            @Override
            public void onApertureChange(CameraEx.ApertureInfo info, CameraEx cameraEx) {
                if (info == null) return;
                m_aperture = info.currentAperture;
                JSONObject data = new JSONObject();
                try {
                    data.put("currentAperture", info.currentAperture);
                    data.put("currentAvailableMin", info.currentAvailableMin);
                    data.put("currentAvailableMax", info.currentAvailableMax);
                } catch (Throwable ignored) {
                }
                m_eventHub.setState("aperture", data);
                m_eventHub.emit("ApertureChange", data);
                m_goalController.onApertureChanged(info.currentAperture, info.currentAvailableMin, info.currentAvailableMax);
                updateStatusText();
            }
        });

        m_camera.setShutterSpeedChangeListener(new CameraEx.ShutterSpeedChangeListener() {
            @Override
            public void onShutterSpeedChange(CameraEx.ShutterSpeedInfo info, CameraEx cameraEx) {
                if (info == null) return;
                m_shutterSpeed = formatShutter(info.currentShutterSpeed_n, info.currentShutterSpeed_d);
                JSONObject data = new JSONObject();
                try {
                    data.put("currentShutterSpeed_n", info.currentShutterSpeed_n);
                    data.put("currentShutterSpeed_d", info.currentShutterSpeed_d);
                    data.put("currentAvailableMin_n", info.currentAvailableMin_n);
                    data.put("currentAvailableMin_d", info.currentAvailableMin_d);
                    data.put("currentAvailableMax_n", info.currentAvailableMax_n);
                    data.put("currentAvailableMax_d", info.currentAvailableMax_d);
                } catch (Throwable ignored) {
                }
                m_eventHub.setState("shutter", data);
                m_eventHub.emit("ShutterSpeedChange", data);
                m_goalController.onShutterChanged(
                        info.currentShutterSpeed_n, info.currentShutterSpeed_d,
                        info.currentAvailableMin_n, info.currentAvailableMin_d,
                        info.currentAvailableMax_n, info.currentAvailableMax_d);
                updateStatusText();
            }
        });

        try {
            m_camera.setAutoISOSensitivityListener(new CameraEx.AutoISOSensitivityListener() {
                @Override
                public void onChanged(int iso, CameraEx cameraEx) {
                    m_eventHub.setState("autoIso", iso);
                    JSONObject data = new JSONObject();
                    try {
                        data.put("iso", iso);
                    } catch (Throwable ignored) {
                    }
                    m_eventHub.emit("AutoISOSensitivity", data);
                }
            });
        } catch (Throwable ignored) {
        }

        try {
            m_camera.setZoomChangeListener(new CameraEx.ZoomChangeListener() {
                @Override
                public void onChanged(CameraEx.ZoomInfo info, CameraEx cameraEx) {
                    if (info == null) return;
                    if (info.digitalMagnification > 0) m_digitalZoom = info.digitalMagnification;
                    JSONObject data = new JSONObject();
                    try {
                        data.put("opticalPosition", info.opticalPosition);
                        data.put("opticalMagnification", info.opticalMagnification);
                        data.put("digitalPosition", info.digitalPosition);
                        data.put("digitalMagnification", info.digitalMagnification);
                        data.put("digitalZoomType", info.digitalZoomType);
                        data.put("stopped", info.stopped);
                    } catch (Throwable ignored) {
                    }
                    m_eventHub.setState("zoom", data);
                    m_eventHub.emit("ZoomChange", data);
                    m_goalController.onZoomChanged(info.opticalPosition, info.stopped);
                    updateStatusText();
                }
            });
        } catch (Throwable ignored) {
        }

        try {
            m_camera.setFocalLengthChangeListener(new CameraEx.FocalLengthChangeListener() {
                @Override
                public void onFocalLengthChanged(int fl, CameraEx cameraEx) {
                    m_eventHub.setState("focalLength", fl);
                    JSONObject data = new JSONObject();
                    try {
                        data.put("focalLength", fl);
                    } catch (Throwable ignored) {
                    }
                    m_eventHub.emit("FocalLengthChange", data);
                }
            });
        } catch (Throwable ignored) {
        }

        try {
            m_camera.setPowerZoomListener(new CameraEx.PowerZoomListener() {
                @Override
                public void onChanged(int status, CameraEx cameraEx) {
                    m_eventHub.setState("powerZoom", status);
                    JSONObject data = new JSONObject();
                    try {
                        data.put("status", status);
                    } catch (Throwable ignored) {
                    }
                    m_eventHub.emit("PowerZoom", data);
                }
            });
        } catch (Throwable ignored) {
        }

        try {
            m_camera.setPreviewMagnificationListener(new CameraEx.PreviewMagnificationListener() {
                @Override
                public void onChanged(boolean enabled, int ratio, int arg2, Pair pos, CameraEx cameraEx) {
                    JSONObject data = new JSONObject();
                    try {
                        data.put("enabled", enabled);
                        data.put("ratio", ratio);
                        data.put("pos", RpcDispatcher.toJsonValue(pos));
                    } catch (Throwable ignored) {
                    }
                    m_eventHub.setState("previewMagnification", data);
                    m_eventHub.emit("PreviewMagnification", data);
                }

                @Override
                public void onInfoUpdated(boolean enabled, Pair pos, CameraEx cameraEx) {
                    JSONObject data = new JSONObject();
                    try {
                        data.put("enabled", enabled);
                        data.put("pos", RpcDispatcher.toJsonValue(pos));
                    } catch (Throwable ignored) {
                    }
                    m_eventHub.emit("PreviewMagnificationInfo", data);
                }
            });
        } catch (Throwable ignored) {
        }

        try {
            m_camera.setSettingChangedListener(new CameraEx.SettingChangedListener() {
                @Override
                public void onChanged(int[] types, Camera.Parameters params, CameraEx cameraEx) {
                    try {
                        if (params != null) {
                            m_iso = m_camera.createParametersModifier(params).getISOSensitivity();
                            int ec = params.getExposureCompensation();
                            JSONObject ecObj = new JSONObject();
                            ecObj.put("index", ec);
                            ecObj.put("step", (double) params.getExposureCompensationStep());
                            ecObj.put("min", params.getMinExposureCompensation());
                            ecObj.put("max", params.getMaxExposureCompensation());
                            m_eventHub.setState("exposureCompensation", ecObj);
                        }
                    } catch (Throwable ignored) {
                    }
                    JSONObject data = new JSONObject();
                    try {
                        data.put("types", RpcDispatcher.toJsonValue(types));
                    } catch (Throwable ignored) {
                    }
                    m_eventHub.emit("SettingChanged", data);
                    updateStatusText();
                }
            });
        } catch (Throwable ignored) {
        }

        try {
            m_camera.setEquipmentCallback(new CameraEx.EquipmentCallback() {
                @Override
                public void onEquipmentChange(int deviceType, int status, CameraEx cameraEx) {
                    if (deviceType == CameraEx.EquipmentCallback.DEVICE_TYPE_LENS) {
                        m_eventHub.setState("lens", buildLensInfoJson());
                    }
                    JSONObject data = new JSONObject();
                    try {
                        data.put("deviceType", deviceType);
                        data.put("status", status);
                    } catch (Throwable ignored) {
                    }
                    m_eventHub.emit("EquipmentChange", data);
                }
            });
        } catch (Throwable ignored) {
        }

        try {
            m_camera.setErrorCallback(new CameraEx.ErrorCallback() {
                @Override
                public void onError(int error, CameraEx cameraEx) {
                    JSONObject data = new JSONObject();
                    try {
                        data.put("error", error);
                    } catch (Throwable ignored) {
                    }
                    m_eventHub.emit("Error", data);
                }
            });
        } catch (Throwable ignored) {
        }
    }

    private void startHttpServerWithFallback() {
        int[] candidatePorts = new int[]{80, MacroHttpServer.DEFAULT_PORT};
        for (int port : candidatePorts) {
            try {
                MacroHttpServer srv = new MacroHttpServer(this, port);
                srv.start();
                m_httpServer = srv;
                m_httpPort = port;
                m_eventHub.setWifiInfo(m_wifiIp, m_httpPort);
                Logger.info("HTTP server listening on port " + port);
                return;
            } catch (Throwable t) {
                Logger.info("Port " + port + " unavailable (" + t.getMessage() + ")");
            }
        }
        Logger.error("Failed to start HTTP server on any port");
    }

    @Override
    protected void onPause() {
        super.onPause();

        if (m_httpServer != null) {
            try {
                m_httpServer.stop();
            } catch (Throwable ignored) {
            }
            m_httpServer = null;
        }

        try {
            unregisterReceiver(m_networkStateReceiver);
        } catch (Throwable ignored) {
        }
        try {
            unregisterReceiver(m_batteryReceiver);
        } catch (Throwable ignored) {
        }
        try {
            m_wifiManager.setWifiEnabled(false);
        } catch (Throwable ignored) {
        }
        setAutoPowerOffMode(true);

        m_liveViewManager.stop();

        if (m_camera != null) {
            m_surfaceHolder.removeCallback(this);
            try {
                m_camera.stopZoom();
            } catch (Throwable ignored) {
            }
            try {
                m_camera.stopPreviewMagnification();
            } catch (Throwable ignored) {
            }
            try {
                m_camera.getNormalCamera().stopPreview();
            } catch (Throwable ignored) {
            }
            if (m_autoReviewControl != null) {
                try {
                    m_autoReviewControl.setPictureReviewTime(m_pictureReviewTime);
                    m_camera.setAutoPictureReviewControl(null);
                } catch (Throwable ignored) {
                }
                m_autoReviewControl = null;
            }
            try {
                m_camera.release();
            } catch (Throwable ignored) {
            }
            m_camera = null;
        }
    }

    private void initCameraDefaults() {
        try {
            m_pictureReviewTime = m_autoReviewControl.getPictureReviewTime();
            m_autoReviewControl.setPictureReviewTime(0);

            Camera.Parameters params = m_camera.createEmptyParameters();
            CameraEx.ParametersModifier modifier = m_camera.createParametersModifier(params);
            modifier.setDriveMode(CameraEx.ParametersModifier.DRIVE_MODE_SINGLE);
            params.setFocusMode(CameraEx.ParametersModifier.FOCUS_MODE_MANUAL);
            m_camera.getNormalCamera().setParameters(params);

            // Enable Precision Digital Zoom so setDigitalZoom works out of the box
            try {
                Camera.Parameters dzParams = m_camera.createEmptyParameters();
                CameraEx.ParametersModifier dzMod = m_camera.createParametersModifier(dzParams);
                dzMod.setDigitalZoomMode(CameraEx.ParametersModifier.DIGITAL_ZOOM_TYPE_PRECISION, true);
                m_camera.getNormalCamera().setParameters(dzParams);
            } catch (Throwable ignored) {
            }
        } catch (Throwable t) {
            Logger.error("initCameraDefaults error: " + t.toString());
        }
    }

    private void refreshInitialState() {
        m_eventHub.setState("lens", buildLensInfoJson());
        try {
            Camera.Parameters params = m_camera.getNormalCamera().getParameters();
            CameraEx.ParametersModifier mod = m_camera.createParametersModifier(params);
            try {
                m_aperture = mod.getAperture();
                if (m_aperture > 0) {
                    JSONObject ap = new JSONObject();
                    ap.put("currentAperture", m_aperture);
                    m_eventHub.setState("aperture", ap);
                }
            } catch (Throwable ignored) {
            }
            try {
                Pair ss = mod.getShutterSpeed();
                if (ss != null && ss.first != null && ss.second != null) {
                    int n = (Integer) ss.first;
                    int d = (Integer) ss.second;
                    m_shutterSpeed = formatShutter(n, d);
                    JSONObject ssObj = new JSONObject();
                    ssObj.put("currentShutterSpeed_n", n);
                    ssObj.put("currentShutterSpeed_d", d);
                    m_eventHub.setState("shutter", ssObj);
                }
            } catch (Throwable ignored) {
            }
            try {
                m_iso = mod.getISOSensitivity();
            } catch (Throwable ignored) {
            }
            try {
                int ec = params.getExposureCompensation();
                JSONObject ecObj = new JSONObject();
                ecObj.put("index", ec);
                ecObj.put("step", (double) params.getExposureCompensationStep());
                ecObj.put("min", params.getMinExposureCompensation());
                ecObj.put("max", params.getMaxExposureCompensation());
                m_eventHub.setState("exposureCompensation", ecObj);
            } catch (Throwable ignored) {
            }
        } catch (Throwable ignored) {
        }
    }

    private String formatShutter(int n, int d) {
        if (d <= 0) return "--";
        if (d == 1) return n + "s";
        if (n == 1) return "1/" + d;
        return n + "/" + d;
    }

    private void updateWifiStatus() {
        try {
            WifiInfo info = m_wifiManager.getConnectionInfo();
            int ipInt = info.getIpAddress();
            m_wifiIp = (ipInt != 0) ? Formatter.formatIpAddress(ipInt) : "0.0.0.0";
            m_eventHub.setWifiInfo(m_wifiIp, m_httpPort);

            String ssid = info.getSSID() != null ? info.getSSID() : "";
            String portSuffix = (m_httpPort == 80) ? "" : (":" + m_httpPort);
            NetworkInfo.DetailedState state = WifiInfo.getDetailedStateOf(info.getSupplicantState());
            if (ipInt != 0) {
                m_tvWifiStatus.setText("Wi-Fi: " + ssid + " -> http://" + m_wifiIp + portSuffix);
            } else {
                m_tvWifiStatus.setText("Wi-Fi: " + state.name() + " (" + ssid + ")");
            }
        } catch (Throwable t) {
            m_tvWifiStatus.setText("Wi-Fi: error");
        }
    }

    private void updateBatteryView(Intent intent) {
        if (intent == null || m_tvBattery == null) return;
        int level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
        if (level < 0 || scale <= 0) return;
        final int pct = (level * 100) / scale;
        final int color;
        if (pct < 15) {
            color = Color.parseColor("#FF5555");
        } else if (pct <= 30) {
            color = Color.parseColor("#FFD700");
        } else {
            color = Color.parseColor("#00FF88");
        }
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (m_batteryView != null) {
                    m_batteryView.setPercent(pct);
                }
                m_tvBattery.setText(pct + "%");
                m_tvBattery.setTextColor(color);
            }
        });
    }

    private void updateStatusText() {
        final String apStr = (m_aperture > 0) ? String.format(Locale.US, "F%.1f", m_aperture / 100.0f) : "F--";
        final String isoStr = (m_iso == 0) ? "AUTO" : String.valueOf(m_iso);
        final String zoomStr = (m_digitalZoom > 100) ? String.format(Locale.US, "  DZ:%.1fx", m_digitalZoom / 100.0f) : "";
        final String text = apStr + "  " + m_shutterSpeed + "  ISO:" + isoStr + zoomStr + "  MF:" + m_curFocus + "/" + m_maxFocus;
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                m_tvCameraStatus.setText(text);
            }
        });
    }

    // --- Synchronous Main-Thread Execution Helper ---

    public <T> T runOnMainSync(final Callable<T> task, long timeoutMs) throws Exception {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            return task.call();
        }
        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicReference<T> resultRef = new AtomicReference<T>();
        final AtomicReference<Throwable> errRef = new AtomicReference<Throwable>();

        m_handler.post(new Runnable() {
            @Override
            public void run() {
                try {
                    resultRef.set(task.call());
                } catch (Throwable t) {
                    errRef.set(t);
                } finally {
                    latch.countDown();
                }
            }
        });

        boolean ok = latch.await(timeoutMs, TimeUnit.MILLISECONDS);
        if (!ok) {
            throw new TimeoutException("Main thread camera call timed out after " + timeoutMs + "ms");
        }
        Throwable err = errRef.get();
        if (err != null) {
            if (err instanceof Exception) {
                throw (Exception) err;
            }
            throw new RuntimeException(err);
        }
        return resultRef.get();
    }

    // --- Lens Info & Raw CameraEx Queries ---

    public JSONObject buildLensInfoJson() {
        if (m_camera == null) return new JSONObject();
        try {
            Object val = RpcDispatcher.toJsonValue(m_camera.getLensInfo());
            if (val instanceof JSONObject) {
                return (JSONObject) val;
            }
        } catch (Throwable ignored) {
        }
        return new JSONObject();
    }

    // --- SurfaceHolder.Callback ---

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        try {
            Camera cam = m_camera.getNormalCamera();
            cam.setPreviewDisplay(holder);
            cam.startPreview();
            m_liveViewManager.start(m_camera, "/data/data/" + getPackageName() + "/lib/lib_memorymap.so");
        } catch (Throwable t) {
            Logger.error("surfaceCreated error: " + t.toString());
        }
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        m_liveViewManager.stop();
    }

    // --- Physical Camera Keys ---

    @Override
    protected boolean onShutterKeyDown() {
        try {
            if (m_camera != null) {
                m_camera.burstableTakePicture();
            }
        } catch (Throwable ignored) {
        }
        return true;
    }

    @Override
    protected boolean onShutterKeyUp() {
        return true;
    }

    @Override
    protected boolean onMenuKeyUp() {
        onBackPressed();
        return true;
    }

    @Override
    protected void setColorDepth(boolean highQuality) {
        super.setColorDepth(false);
    }
}

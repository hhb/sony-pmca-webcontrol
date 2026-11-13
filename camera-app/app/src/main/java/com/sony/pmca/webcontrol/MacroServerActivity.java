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
import android.os.Environment;
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
import com.sony.scalar.hardware.CameraSequence;
import com.sony.scalar.hardware.DeviceBuffer;
import com.sony.scalar.hardware.DeviceMemory;
import com.sony.scalar.sysutil.ScalarProperties;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

public class MacroServerActivity extends BaseActivity implements SurfaceHolder.Callback {

    private static final int LIVEVIEW_BUF_CAPACITY = 128 * 1024;
    public static final long MAIN_THREAD_TIMEOUT_MS = 3000L;

    private SurfaceHolder m_surfaceHolder;
    private CameraEx m_camera;
    private CameraSequence m_cameraSequence;
    private CameraEx.AutoPictureReviewControl m_autoReviewControl;
    private int m_pictureReviewTime;

    private final Handler m_handler = new Handler(Looper.getMainLooper());
    private final CameraEventHub m_eventHub = new CameraEventHub();
    private final RpcDispatcher m_rpc = new RpcDispatcher();

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
    private volatile int m_opticalZoom = 100;
    private volatile int m_digitalZoom = 100;
    private volatile int m_previewMag = 0;

    // Hardware LiveView / JPEG buffer (via Sony CameraSequence)
    private final Object m_frameLock = new Object();
    private ByteBuffer m_liveviewByteBuf;
    private byte[] m_latestJpeg;
    private byte[] m_lastCapturedJpeg;
    private long m_lastLiveviewFetchMs = 0;

    public CameraEventHub getEventHub() {
        return m_eventHub;
    }

    public RpcDispatcher getRpcDispatcher() {
        return m_rpc;
    }

    public CameraEx getCameraEx() {
        return m_camera;
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
                m_eventHub.setLastShutterStatus(status);
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
                    m_eventHub.setCapturing(true);
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
                    m_eventHub.setCapturing(false);
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
                    synchronized (m_frameLock) {
                        m_latestJpeg = data;
                        m_lastCapturedJpeg = data;
                    }
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
                    m_eventHub.updateLastStoreImage(data);
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
                m_eventHub.updateFocus(data);
                m_eventHub.emit("FocusDrive", data);
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
                m_eventHub.updateAperture(data);
                m_eventHub.emit("ApertureChange", data);
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
                m_eventHub.updateShutter(data);
                m_eventHub.emit("ShutterSpeedChange", data);
                updateStatusText();
            }
        });

        try {
            m_camera.setAutoISOSensitivityListener(new CameraEx.AutoISOSensitivityListener() {
                @Override
                public void onChanged(int iso, CameraEx cameraEx) {
                    m_eventHub.setAutoIso(iso);
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
                    if (info.opticalMagnification > 0) m_opticalZoom = info.opticalMagnification;
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
                    m_eventHub.updateZoom(data);
                    m_eventHub.emit("ZoomChange", data);
                    updateStatusText();
                }
            });
        } catch (Throwable ignored) {
        }

        try {
            m_camera.setFocalLengthChangeListener(new CameraEx.FocalLengthChangeListener() {
                @Override
                public void onFocalLengthChanged(int fl, CameraEx cameraEx) {
                    m_eventHub.setFocalLength(fl);
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
                    m_eventHub.setPowerZoom(status);
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
                    m_previewMag = enabled ? ratio : 0;
                    JSONObject data = new JSONObject();
                    try {
                        data.put("enabled", enabled);
                        data.put("ratio", ratio);
                        data.put("pos", RpcDispatcher.toJsonValue(pos));
                    } catch (Throwable ignored) {
                    }
                    m_eventHub.updatePreviewMag(data);
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
                            m_eventHub.updateExposureCompensation(ecObj);
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
                        m_eventHub.setLensInfo(buildLensInfoJson());
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

        stopLiveviewSequence();

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
        m_eventHub.setLensInfo(buildLensInfoJson());
        try {
            Camera.Parameters params = m_camera.getNormalCamera().getParameters();
            CameraEx.ParametersModifier mod = m_camera.createParametersModifier(params);
            try {
                m_aperture = mod.getAperture();
                if (m_aperture > 0) {
                    JSONObject ap = new JSONObject();
                    ap.put("currentAperture", m_aperture);
                    m_eventHub.updateAperture(ap);
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
                    m_eventHub.updateShutter(ssObj);
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
                m_eventHub.updateExposureCompensation(ecObj);
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

    // --- Raw Camera.Parameters Access ---

    public JSONObject getRawParamsJson() {
        JSONObject map = new JSONObject();
        if (m_camera == null) return map;
        String flat = m_camera.getNormalCamera().getParameters().flatten();
        if (flat != null) {
            String[] pairs = flat.split(";");
            for (String p : pairs) {
                int eq = p.indexOf('=');
                if (eq > 0) {
                    String k = p.substring(0, eq);
                    String v = p.substring(eq + 1);
                    try {
                        map.put(k, v);
                    } catch (Throwable ignored) {
                    }
                }
            }
        }
        return map;
    }

    public void setRawParams(JSONObject updates) throws Exception {
        if (m_camera == null) throw new IllegalStateException("Camera not open");
        Camera.Parameters params = m_camera.createEmptyParameters();
        Iterator<String> it = updates.keys();
        while (it.hasNext()) {
            String k = it.next();
            String v = String.valueOf(updates.get(k));
            params.set(k, v);
        }
        m_camera.getNormalCamera().setParameters(params);
    }

    // --- Hardware LiveView Sequence (Sony CameraSequence API) ---

    private void startLiveviewSequence() {
        if (m_camera == null || m_cameraSequence != null) {
            return;
        }
        synchronized (m_frameLock) {
            try {
                m_cameraSequence = CameraSequence.open(m_camera);
                if (m_cameraSequence != null) {
                    CameraSequence.Options opts = new CameraSequence.Options();
                    opts.setOption("MEMORY_MAP_FILE", getLibPath("lib_memorymap.so"));
                    opts.setOption("EXPOSURE_COUNT", 1);
                    opts.setOption("RECORD_COUNT", 1);
                    opts.setOption("INTERIM_PRE_REVIEW_ENABLED", false);
                    opts.setOption("AUTO_RELEASE_LOCK_ENABLED", false);
                    opts.setOption("PREVIEW_FRAME_RATE", 30000);
                    opts.setOption("PREVIEW_FRAME_WIDTH", 640);
                    opts.setOption("PREVIEW_FRAME_HEIGHT", 0);
                    m_cameraSequence.setReleaseLock(false);
                    m_cameraSequence.startPreviewSequence(opts);
                    Logger.info("CameraSequence LiveView started (640px JPEG)");
                }
            } catch (Throwable t) {
                Logger.error("startLiveviewSequence error: " + t.toString());
                m_cameraSequence = null;
            }
        }
    }

    private void stopLiveviewSequence() {
        synchronized (m_frameLock) {
            if (m_cameraSequence != null) {
                try {
                    m_cameraSequence.stopPreviewSequence();
                } catch (Throwable ignored) {
                }
                try {
                    m_cameraSequence.release();
                } catch (Throwable ignored) {
                }
                m_cameraSequence = null;
            }
        }
    }

    private String getLibPath(String libName) {
        return "/data/data/" + getPackageName() + "/lib/" + libName;
    }

    public byte[] getLatestLiveViewJpeg() {
        synchronized (m_frameLock) {
            long now = System.currentTimeMillis();
            if (m_cameraSequence != null && (now - m_lastLiveviewFetchMs) >= 40) {
                DeviceMemory[] frames = null;
                try {
                    frames = m_cameraSequence.getPreviewSequenceFrames(1);
                    if (frames != null && frames.length > 0 && frames[0] != null) {
                        DeviceBuffer buf = (DeviceBuffer) frames[0];
                        if (m_liveviewByteBuf == null) {
                            m_liveviewByteBuf = ByteBuffer.allocateDirect(LIVEVIEW_BUF_CAPACITY);
                        }
                        m_liveviewByteBuf.clear();
                        int size = buf.read(m_liveviewByteBuf);
                        if (size > 0 && size <= LIVEVIEW_BUF_CAPACITY) {
                            byte[] jpeg = new byte[size];
                            m_liveviewByteBuf.rewind();
                            m_liveviewByteBuf.get(jpeg, 0, size);
                            m_latestJpeg = jpeg;
                            m_lastLiveviewFetchMs = now;
                        }
                    }
                } catch (Throwable t) {
                    Logger.error("getPreviewSequenceFrames error: " + t.toString());
                } finally {
                    if (frames != null) {
                        for (DeviceMemory dm : frames) {
                            if (dm != null) {
                                try {
                                    dm.release();
                                } catch (Throwable ignored) {
                                }
                            }
                        }
                    }
                }
            }
            return m_latestJpeg;
        }
    }

    public byte[] getLastCapturedJpeg() {
        synchronized (m_frameLock) {
            return m_lastCapturedJpeg;
        }
    }

    public File resolveSdDir(String relDir) {
        File external = Environment.getExternalStorageDirectory();
        File dcim = new File(external, "DCIM");
        File baseRoot = (dcim.exists() && dcim.isDirectory()) ? dcim : external;
        if (relDir == null || relDir.trim().isEmpty() || "/".equals(relDir.trim())) {
            return baseRoot;
        }
        String clean = relDir.trim().replace("\\", "/");
        while (clean.startsWith("/")) clean = clean.substring(1);
        if (clean.contains("..")) {
            return baseRoot;
        }
        if (clean.startsWith("DCIM/")) {
            clean = clean.substring("DCIM/".length());
        } else if ("DCIM".equals(clean)) {
            return baseRoot;
        }
        File target = clean.isEmpty() ? baseRoot : new File(baseRoot, clean);
        return target;
    }

    public JSONObject listPhotoDirectory(String relDir, int limit) {
        JSONObject out = new JSONObject();
        JSONArray dirsArr = new JSONArray();
        JSONArray photosArr = new JSONArray();
        try {
            File external = Environment.getExternalStorageDirectory();
            File dcim = new File(external, "DCIM");
            File baseRoot = (dcim.exists() && dcim.isDirectory()) ? dcim : external;
            File target = resolveSdDir(relDir);

            List<File> subDirs = new ArrayList<File>();
            List<File> subPhotos = new ArrayList<File>();
            File[] entries = target.listFiles();
            if (entries != null) {
                for (File f : entries) {
                    if (f.isDirectory()) {
                        subDirs.add(f);
                    } else if (f.isFile()) {
                        String name = f.getName().toLowerCase();
                        if (name.endsWith(".jpg") || name.endsWith(".jpeg") || name.endsWith(".arw")) {
                            subPhotos.add(f);
                        }
                    }
                }
            }

            Comparator<File> nameDesc = new Comparator<File>() {
                @Override
                public int compare(File a, File b) {
                    return b.getName().compareToIgnoreCase(a.getName());
                }
            };
            Collections.sort(subDirs, nameDesc);
            Collections.sort(subPhotos, nameDesc);

            for (File d : subDirs) {
                JSONObject item = new JSONObject();
                item.put("name", d.getName());
                item.put("path", toRelativeDcimPath(baseRoot, d));
                dirsArr.put(item);
            }

            int maxPhotos = (limit > 0 && subPhotos.size() > limit) ? limit : subPhotos.size();
            for (int i = 0; i < maxPhotos; i++) {
                File f = subPhotos.get(i);
                JSONObject item = new JSONObject();
                item.put("name", f.getName());
                item.put("path", f.getAbsolutePath().replace("\\", "/"));
                item.put("size", f.length());
                photosArr.put(item);
            }

            out.put("ok", true);
            out.put("dir", toRelativeDcimPath(baseRoot, target));
            out.put("totalPhotosInDir", subPhotos.size());
            out.put("dirs", dirsArr);
            out.put("photos", photosArr);
        } catch (Throwable t) {
            Logger.error("listPhotoDirectory error: " + t.toString());
            try {
                out.put("ok", false);
                out.put("error", t.toString());
            } catch (Throwable ignored) {
            }
        }
        return out;
    }

    private static String toRelativeDcimPath(File baseRoot, File target) {
        String rootPath = baseRoot.getAbsolutePath();
        String targetPath = target.getAbsolutePath();
        if (targetPath.equals(rootPath)) {
            return "";
        }
        if (targetPath.startsWith(rootPath + "/")) {
            return targetPath.substring(rootPath.length() + 1);
        }
        return target.getName();
    }

    public File getLatestPhotoFile() {
        try {
            File root = resolveSdDir(null);
            File[] entries = root.listFiles();
            if (entries == null) return null;

            List<File> subDirs = new ArrayList<File>();
            List<File> directPhotos = new ArrayList<File>();
            for (File f : entries) {
                if (f.isDirectory()) {
                    subDirs.add(f);
                } else if (f.isFile()) {
                    String name = f.getName().toLowerCase();
                    if (name.endsWith(".jpg") || name.endsWith(".jpeg") || name.endsWith(".arw")) {
                        directPhotos.add(f);
                    }
                }
            }
            Comparator<File> nameDesc = new Comparator<File>() {
                @Override
                public int compare(File a, File b) {
                    return b.getName().compareToIgnoreCase(a.getName());
                }
            };
            Collections.sort(subDirs, nameDesc);
            for (File d : subDirs) {
                File[] files = d.listFiles();
                if (files == null) continue;
                File best = null;
                for (File f : files) {
                    if (!f.isFile()) continue;
                    String name = f.getName().toLowerCase();
                    if (name.endsWith(".jpg") || name.endsWith(".jpeg") || name.endsWith(".arw")) {
                        if (best == null || f.getName().compareToIgnoreCase(best.getName()) > 0) {
                            best = f;
                        }
                    }
                }
                if (best != null) return best;
            }
            if (!directPhotos.isEmpty()) {
                Collections.sort(directPhotos, nameDesc);
                return directPhotos.get(0);
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    public String getCrashLogText() {
        File f = Logger.getFile();
        if (!f.exists()) {
            return "Log file does not exist yet: " + f.getAbsolutePath();
        }
        StringBuilder sb = new StringBuilder();
        BufferedReader br = null;
        try {
            br = new BufferedReader(new FileReader(f));
            String line;
            while ((line = br.readLine()) != null) {
                sb.append(line).append("\n");
            }
        } catch (Throwable t) {
            sb.append("Error reading log: ").append(t.toString());
        } finally {
            if (br != null) {
                try {
                    br.close();
                } catch (Throwable ignored) {
                }
            }
        }
        return sb.toString();
    }

    // --- SurfaceHolder.Callback ---

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        try {
            Camera cam = m_camera.getNormalCamera();
            cam.setPreviewDisplay(holder);
            cam.startPreview();
            startLiveviewSequence();
        } catch (Throwable t) {
            Logger.error("surfaceCreated error: " + t.toString());
        }
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        stopLiveviewSequence();
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

package com.a6000.aimacro;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.graphics.YuvImage;
import android.hardware.Camera;
import android.net.NetworkInfo;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.Handler;
import android.text.format.Formatter;
import android.util.Pair;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.widget.TextView;

import com.github.ma1co.pmcademo.app.BaseActivity;
import com.github.ma1co.pmcademo.app.Logger;
import com.sony.scalar.hardware.CameraEx;
import com.sony.scalar.sysutil.ScalarProperties;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;

public class MacroServerActivity extends BaseActivity
        implements SurfaceHolder.Callback, CameraEx.ShutterListener, Camera.PreviewCallback {

    public enum State {
        idle, focusing, capturing, bracketing, error
    }

    private SurfaceHolder m_surfaceHolder;
    private CameraEx m_camera;
    private CameraEx.AutoPictureReviewControl m_autoReviewControl;
    private int m_pictureReviewTime;

    private final Handler m_handler = new Handler();

    private FocusScaleView m_focusScaleView;
    private TextView m_tvWifiStatus;
    private TextView m_tvCameraStatus;
    private TextView m_tvInstructions;

    private WifiManager m_wifiManager;
    private BroadcastReceiver m_networkStateReceiver;
    private MacroHttpServer m_httpServer;
    private String m_wifiIp = "0.0.0.0";

    private volatile State m_state = State.idle;
    private volatile int m_curFocus = 0;
    private volatile int m_maxFocus = 0;
    private volatile int m_aperture = 0;
    private volatile String m_shutterSpeed = "--";
    private volatile int m_iso = 0;
    private volatile List<Integer> m_supportedIsos;

    // Focus drive / goto / bracketing state
    private int m_focusBeforeDrive = 0;
    private int m_targetFocus = 0;
    private int m_stuckCount = 0;
    private LinkedList<Integer> m_focusQueue = new LinkedList<Integer>();

    // Latest preview frame buffer (YUV)
    private final Object m_frameLock = new Object();
    private byte[] m_latestYuv;
    private int m_previewWidth = 0;
    private int m_previewHeight = 0;
    private int m_previewFormat = ImageFormat.NV21;

    private final Runnable m_checkFocusRunnable = new Runnable() {
        @Override
        public void run() {
            if ((m_state == State.focusing || m_state == State.bracketing) && m_focusBeforeDrive == m_curFocus) {
                m_stuckCount++;
                if (m_stuckCount > 8) {
                    Logger.info("Focus drive stalled at " + m_curFocus);
                    if (m_state == State.focusing) {
                        m_state = State.idle;
                        updateStatusText();
                    } else if (m_state == State.bracketing) {
                        takePictureInternal();
                    }
                } else {
                    driveTowardTarget();
                }
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_macro_server);

        if (!(Thread.getDefaultUncaughtExceptionHandler() instanceof CustomExceptionHandler)) {
            Thread.setDefaultUncaughtExceptionHandler(new CustomExceptionHandler());
        }

        SurfaceView surfaceView = (SurfaceView) findViewById(R.id.surfaceView);
        m_surfaceHolder = surfaceView.getHolder();
        m_surfaceHolder.setType(SurfaceHolder.SURFACE_TYPE_PUSH_BUFFERS);

        m_focusScaleView = (FocusScaleView) findViewById(R.id.vFocusScale);
        m_tvWifiStatus = (TextView) findViewById(R.id.tvWifiStatus);
        m_tvCameraStatus = (TextView) findViewById(R.id.tvCameraStatus);
        m_tvInstructions = (TextView) findViewById(R.id.tvInstructions);

        m_wifiManager = (WifiManager) getSystemService(Context.WIFI_SERVICE);
        m_networkStateReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                NetworkInfo info = intent.getParcelableExtra(WifiManager.EXTRA_NETWORK_INFO);
                if (info != null) {
                    handleNetworkState(info.getDetailedState());
                }
            }
        };

        m_httpServer = new MacroHttpServer(this);
    }

    @Override
    protected void onResume() {
        super.onResume();

        setAutoPowerOffMode(false);
        registerReceiver(m_networkStateReceiver, new IntentFilter(WifiManager.NETWORK_STATE_CHANGED_ACTION));
        m_wifiManager.setWifiEnabled(true);

        try {
            m_httpServer.start();
        } catch (IOException e) {
            Logger.error("Failed to start HTTP server: " + e.getMessage());
        }

        m_camera = CameraEx.open(0, null);
        m_surfaceHolder.addCallback(this);
        m_autoReviewControl = new CameraEx.AutoPictureReviewControl();
        m_camera.setAutoPictureReviewControl(m_autoReviewControl);
        m_camera.setShutterListener(this);

        m_camera.setFocusDriveListener(new CameraEx.FocusDriveListener() {
            @Override
            public void onChanged(CameraEx.FocusPosition focusPosition, CameraEx cameraEx) {
                m_handler.removeCallbacks(m_checkFocusRunnable);
                m_stuckCount = 0;
                m_maxFocus = focusPosition.maxPosition;
                m_curFocus = focusPosition.currentPosition;
                m_focusScaleView.setMaxPosition(m_maxFocus);
                m_focusScaleView.setCurPosition(m_curFocus);

                if (m_state == State.focusing) {
                    if (m_curFocus == m_targetFocus) {
                        m_state = State.idle;
                    } else {
                        driveTowardTarget();
                    }
                } else if (m_state == State.bracketing && !m_focusQueue.isEmpty()) {
                    if (m_curFocus == m_focusQueue.getFirst()) {
                        takePictureInternal();
                    } else {
                        driveTowardTarget();
                    }
                }
                updateStatusText();
            }
        });

        m_camera.setApertureChangeListener(new CameraEx.ApertureChangeListener() {
            @Override
            public void onChanged(int aperture, CameraEx cameraEx) {
                m_aperture = aperture;
                updateStatusText();
            }
        });

        m_camera.setShutterSpeedChangeListener(new CameraEx.ShutterSpeedChangeListener() {
            @Override
            public void onChanged(int num, int den, CameraEx cameraEx) {
                m_shutterSpeed = formatShutter(num, den);
                updateStatusText();
            }
        });

        initCameraDefaults();
        refreshExposureCache();
        updateStatusText();
    }

    @Override
    protected void onPause() {
        super.onPause();

        abortBracket();
        m_handler.removeCallbacks(m_checkFocusRunnable);

        if (m_httpServer != null) {
            m_httpServer.stop();
        }

        try {
            unregisterReceiver(m_networkStateReceiver);
        } catch (IllegalArgumentException ignored) {
        }
        m_wifiManager.setWifiEnabled(false);
        setAutoPowerOffMode(true);

        if (m_camera != null) {
            m_surfaceHolder.removeCallback(this);
            try {
                m_camera.getNormalCamera().setPreviewCallback(null);
                m_camera.getNormalCamera().stopPreview();
            } catch (Exception ignored) {
            }
            if (m_autoReviewControl != null) {
                m_autoReviewControl.setPictureReviewTime(m_pictureReviewTime);
                m_camera.setAutoPictureReviewControl(null);
                m_autoReviewControl = null;
            }
            m_camera.release();
            m_camera = null;
        }
    }

    private void initCameraDefaults() {
        Camera.Parameters params = m_camera.getNormalCamera().getParameters();
        CameraEx.ParametersModifier modifier = m_camera.createParametersModifier(params);
        params.setSceneMode(CameraEx.ParametersModifier.SCENE_MODE_MANUAL_EXPOSURE);
        modifier.setDriveMode(CameraEx.ParametersModifier.DRIVE_MODE_SINGLE);
        params.setFocusMode(CameraEx.ParametersModifier.FOCUS_MODE_MANUAL);
        modifier.setSelfTimer(0);
        m_camera.getNormalCamera().setParameters(params);

        String platformVersion = ScalarProperties.getString(ScalarProperties.PROP_VERSION_PLATFORM);
        if (platformVersion != null) {
            String[] split = platformVersion.split("\\Q.\\E");
            if (split.length >= 2 && Integer.parseInt(split[1]) < 3) {
                m_state = State.error;
                m_tvInstructions.setText("ERROR: Focus drive not supported on this body");
            }
        }

        m_pictureReviewTime = m_autoReviewControl.getPictureReviewTime();
        m_autoReviewControl.setPictureReviewTime(0);
    }

    @SuppressWarnings("unchecked")
    private void refreshExposureCache() {
        if (m_camera == null) {
            return;
        }
        Camera.Parameters params = m_camera.getNormalCamera().getParameters();
        CameraEx.ParametersModifier modifier = m_camera.createParametersModifier(params);
        m_aperture = modifier.getAperture();
        Pair<Integer, Integer> ss = modifier.getShutterSpeed();
        if (ss != null) {
            m_shutterSpeed = formatShutter(ss.first, ss.second);
        }
        m_iso = modifier.getISOSensitivity();
        m_supportedIsos = (List<Integer>) modifier.getSupportedISOSensitivities();
    }

    private void handleNetworkState(NetworkInfo.DetailedState state) {
        WifiInfo info = m_wifiManager.getConnectionInfo();
        String ssid = info != null ? info.getSSID() : "";
        switch (state) {
            case CONNECTING:
                m_tvWifiStatus.setText("Wi-Fi: Connecting to " + ssid + "...");
                break;
            case OBTAINING_IPADDR:
                m_tvWifiStatus.setText("Wi-Fi: Obtaining IP...");
                break;
            case CONNECTED:
                m_wifiIp = info != null ? Formatter.formatIpAddress(info.getIpAddress()) : "0.0.0.0";
                m_tvWifiStatus.setText("READY (" + ssid + "): http://" + m_wifiIp + ":" + MacroHttpServer.PORT);
                break;
            case DISCONNECTED:
                m_wifiIp = "0.0.0.0";
                m_tvWifiStatus.setText("Wi-Fi: Disconnected (Check saved Wi-Fi in camera settings)");
                break;
            case FAILED:
                m_tvWifiStatus.setText("Wi-Fi: Connection failed");
                break;
            default:
                break;
        }
    }

    private void updateStatusText() {
        String fStr = m_aperture > 0 ? String.format(Locale.US, "F%.1f", m_aperture / 100.0f) : "F--";
        String isoStr = m_iso == 0 ? "AUTO" : String.valueOf(m_iso);
        String msg = String.format(Locale.US,
                "Focus: %d/%d | %s | %s | ISO %s | [%s]",
                m_curFocus, m_maxFocus, fStr, m_shutterSpeed, isoStr, m_state.name());
        if (m_state == State.bracketing) {
            msg += " (" + m_focusQueue.size() + " left)";
        }
        m_tvCameraStatus.setText(msg);
    }

    private static String formatShutter(int n, int d) {
        if (n == 1 && d != 2 && d != 1) {
            return n + "/" + d;
        } else if (d == 1) {
            return n == 65535 ? "BULB" : (n + "\"");
        } else {
            return String.format(Locale.US, "%.1f\"", (float) n / (float) d);
        }
    }

    // --- Camera Preview Callback (Live View) ---

    @Override
    public void onPreviewFrame(byte[] data, Camera camera) {
        if (data == null) {
            return;
        }
        synchronized (m_frameLock) {
            if (m_latestYuv == null || m_latestYuv.length != data.length) {
                m_latestYuv = new byte[data.length];
            }
            System.arraycopy(data, 0, m_latestYuv, 0, data.length);
        }
    }

    public byte[] getLatestLiveViewJpeg() {
        byte[] yuvCopy;
        int w;
        int h;
        int fmt;
        synchronized (m_frameLock) {
            if (m_latestYuv == null || m_previewWidth <= 0 || m_previewHeight <= 0) {
                return null;
            }
            yuvCopy = new byte[m_latestYuv.length];
            System.arraycopy(m_latestYuv, 0, yuvCopy, 0, m_latestYuv.length);
            w = m_previewWidth;
            h = m_previewHeight;
            fmt = m_previewFormat;
        }
        YuvImage yuvImage = new YuvImage(yuvCopy, fmt, w, h, null);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        yuvImage.compressToJpeg(new Rect(0, 0, w, h), 75, baos);
        return baos.toByteArray();
    }

    // --- SurfaceHolder.Callback ---

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        try {
            Camera cam = m_camera.getNormalCamera();
            Camera.Parameters params = cam.getParameters();
            Camera.Size previewSize = params.getPreviewSize();
            m_previewWidth = previewSize.width;
            m_previewHeight = previewSize.height;
            m_previewFormat = params.getPreviewFormat();
            cam.setPreviewDisplay(holder);
            cam.setPreviewCallback(this);
            cam.startPreview();
        } catch (IOException e) {
            Logger.error("surfaceCreated error: " + e.getMessage());
        }
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
    }

    // --- Public API Methods called from MacroHttpServer ---

    public void postToUi(Runnable r) {
        m_handler.post(r);
    }

    public void driveFocusStep(final boolean far, final int speed) {
        m_handler.post(new Runnable() {
            @Override
            public void run() {
                if (m_camera == null || m_state == State.error) {
                    return;
                }
                int clampedSpeed = Math.max(1, Math.min(4, speed));
                m_camera.startOneShotFocusDrive(
                        far ? CameraEx.FOCUS_DRIVE_DIRECTION_FAR : CameraEx.FOCUS_DRIVE_DIRECTION_NEAR,
                        clampedSpeed);
            }
        });
    }

    public void gotoFocusPosition(final int targetPos) {
        m_handler.post(new Runnable() {
            @Override
            public void run() {
                if (m_camera == null || m_state == State.error) {
                    return;
                }
                m_focusQueue.clear();
                m_targetFocus = Math.max(0, Math.min(m_maxFocus > 0 ? m_maxFocus : targetPos, targetPos));
                if (m_curFocus == m_targetFocus) {
                    m_state = State.idle;
                    updateStatusText();
                    return;
                }
                m_state = State.focusing;
                m_stuckCount = 0;
                updateStatusText();
                driveTowardTarget();
            }
        });
    }

    public void startBracket(final int minPos, final int maxPos, final int shots) {
        m_handler.post(new Runnable() {
            @Override
            public void run() {
                if (m_camera == null || m_state == State.error) {
                    return;
                }
                int start = Math.min(minPos, maxPos);
                int end = Math.max(minPos, maxPos);
                int count = Math.max(2, shots);

                m_focusQueue.clear();
                float step = (float) (end - start) / (float) (count - 1);
                for (int i = 0; i < count; i++) {
                    int pos = Math.round(start + i * step);
                    if (m_focusQueue.isEmpty() || m_focusQueue.getLast() != pos) {
                        m_focusQueue.addLast(pos);
                    }
                }
                if (m_focusQueue.isEmpty()) {
                    return;
                }
                m_targetFocus = m_focusQueue.getFirst();
                m_state = State.bracketing;
                m_stuckCount = 0;
                updateStatusText();
                if (m_curFocus == m_targetFocus) {
                    takePictureInternal();
                } else {
                    driveTowardTarget();
                }
            }
        });
    }

    public void abortBracket() {
        m_handler.post(new Runnable() {
            @Override
            public void run() {
                m_handler.removeCallbacks(m_checkFocusRunnable);
                m_focusQueue.clear();
                if (m_state == State.bracketing || m_state == State.focusing) {
                    m_state = State.idle;
                }
                updateStatusText();
            }
        });
    }

    public void captureSingle() {
        m_handler.post(new Runnable() {
            @Override
            public void run() {
                if (m_camera == null || m_state == State.error) {
                    return;
                }
                m_state = State.capturing;
                updateStatusText();
                takePictureInternal();
            }
        });
    }

    public void adjustExposure(final String apertureOp, final String shutterOp, final Integer isoVal) {
        m_handler.post(new Runnable() {
            @Override
            public void run() {
                if (m_camera == null) {
                    return;
                }
                if ("inc".equalsIgnoreCase(apertureOp)) {
                    m_camera.incrementAperture();
                } else if ("dec".equalsIgnoreCase(apertureOp)) {
                    m_camera.decrementAperture();
                }

                if ("inc".equalsIgnoreCase(shutterOp)) {
                    m_camera.incrementShutterSpeed();
                } else if ("dec".equalsIgnoreCase(shutterOp)) {
                    m_camera.decrementShutterSpeed();
                }

                if (isoVal != null) {
                    Camera.Parameters params = m_camera.createEmptyParameters();
                    m_camera.createParametersModifier(params).setISOSensitivity(isoVal);
                    m_camera.getNormalCamera().setParameters(params);
                    m_iso = isoVal;
                }
                updateStatusText();
            }
        });
    }

    private void driveTowardTarget() {
        int target = (m_state == State.bracketing && !m_focusQueue.isEmpty()) ? m_focusQueue.getFirst() : m_targetFocus;
        m_focusBeforeDrive = m_curFocus;
        if (m_curFocus == target) {
            if (m_state == State.bracketing) {
                takePictureInternal();
            } else {
                m_state = State.idle;
                updateStatusText();
            }
            return;
        }
        int absDiff = Math.abs(m_curFocus - target);
        int speed;
        if (absDiff > 25) {
            speed = 4;
        } else if (absDiff > 20) {
            speed = 3;
        } else if (absDiff > 15) {
            speed = 2;
        } else {
            speed = 1;
        }
        m_camera.startOneShotFocusDrive(
                m_curFocus < target ? CameraEx.FOCUS_DRIVE_DIRECTION_FAR : CameraEx.FOCUS_DRIVE_DIRECTION_NEAR,
                speed);
        m_handler.postDelayed(m_checkFocusRunnable, 60);
    }

    private void takePictureInternal() {
        m_camera.burstableTakePicture();
    }

    @Override
    public void onShutter(int i, CameraEx cameraEx) {
        m_camera.cancelTakePicture();
        if (m_state == State.bracketing) {
            if (i == 0 && !m_focusQueue.isEmpty()) {
                m_focusQueue.removeFirst();
                if (m_focusQueue.isEmpty()) {
                    m_state = State.idle;
                    updateStatusText();
                } else {
                    m_targetFocus = m_focusQueue.getFirst();
                    m_stuckCount = 0;
                    updateStatusText();
                    driveTowardTarget();
                }
            } else {
                m_focusQueue.clear();
                m_state = State.idle;
                updateStatusText();
            }
        } else {
            m_state = State.idle;
            updateStatusText();
        }
    }

    // --- Physical Camera Keys ---

    @Override
    protected boolean onEnterKeyDown() {
        captureSingle();
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

    // --- Status JSON Builder ---

    public String getStatusJson() {
        StringBuilder sb = new StringBuilder();
        sb.append("{");
        sb.append("\"state\":\"").append(m_state.name()).append("\",");
        sb.append("\"currentFocus\":").append(m_curFocus).append(",");
        sb.append("\"maxFocus\":").append(m_maxFocus).append(",");
        sb.append("\"remainingShots\":").append(m_focusQueue.size()).append(",");
        sb.append("\"aperture\":").append(m_aperture).append(",");
        sb.append("\"apertureFormatted\":\"").append(m_aperture > 0 ? String.format(Locale.US, "F%.1f", m_aperture / 100.0f) : "F--").append("\",");
        sb.append("\"shutterSpeed\":\"").append(m_shutterSpeed).append("\",");
        sb.append("\"iso\":").append(m_iso).append(",");
        sb.append("\"wifiIp\":\"").append(m_wifiIp).append("\"");
        sb.append("}");
        return sb.toString();
    }
}

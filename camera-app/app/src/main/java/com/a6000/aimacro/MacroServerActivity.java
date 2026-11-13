package com.a6000.aimacro;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
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
import com.sony.scalar.hardware.CameraSequence;
import com.sony.scalar.hardware.DeviceBuffer;
import com.sony.scalar.hardware.DeviceMemory;
import com.sony.scalar.sysutil.ScalarProperties;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.nio.ByteBuffer;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;

public class MacroServerActivity extends BaseActivity
        implements SurfaceHolder.Callback, CameraEx.ShutterListener {

    public enum State {
        idle, focusing, capturing, bracketing, error
    }

    private static final int LIVEVIEW_BUF_CAPACITY = 128 * 1024;

    private SurfaceHolder m_surfaceHolder;
    private CameraEx m_camera;
    private CameraSequence m_cameraSequence;
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
    private int m_httpPort = MacroHttpServer.DEFAULT_PORT;
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

    // Hardware LiveView / JPEG buffer (via Sony CameraSequence)
    private final Object m_frameLock = new Object();
    private ByteBuffer m_liveviewByteBuf;
    private byte[] m_latestJpeg;
    private long m_lastLiveviewFetchMs = 0;

    private final Runnable m_checkFocusRunnable = new Runnable() {
        @Override
        public void run() {
            if ((m_state == State.focusing || m_state == State.bracketing) && m_focusBeforeDrive == m_curFocus) {
                m_stuckCount++;
                if (m_stuckCount == 2 && m_camera != null) {
                    // Re-assert Manual Focus mode in case camera temporarily left MF state
                    try {
                        Camera.Parameters focusParams = m_camera.createEmptyParameters();
                        focusParams.setFocusMode(CameraEx.ParametersModifier.FOCUS_MODE_MANUAL);
                        m_camera.getNormalCamera().setParameters(focusParams);
                    } catch (Throwable ignored) {
                    }
                }
                if (m_stuckCount > 12) {
                    Logger.info("Focus drive stalled at " + m_curFocus + " (target " + m_targetFocus + ")");
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
                try {
                    NetworkInfo info = intent.getParcelableExtra(WifiManager.EXTRA_NETWORK_INFO);
                    if (info != null) {
                        handleNetworkState(info.getDetailedState());
                    }
                } catch (Throwable t) {
                    Logger.error("Network receiver error: " + t.toString());
                }
            }
        };
    }

    @Override
    protected void onResume() {
        super.onResume();

        try {
            setAutoPowerOffMode(false);
            registerReceiver(m_networkStateReceiver, new IntentFilter(WifiManager.NETWORK_STATE_CHANGED_ACTION));
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
            m_camera.setShutterListener(this);

            m_camera.setJpegListener(new CameraEx.JpegListener() {
                @Override
                public void onPictureTaken(byte[] data, CameraEx cameraEx) {
                    if (data != null) {
                        synchronized (m_frameLock) {
                            m_latestJpeg = data;
                        }
                    }
                }
            });

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
                        if (m_maxFocus > 0) {
                            m_targetFocus = Math.max(0, Math.min(m_maxFocus, m_targetFocus));
                        }
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
                public void onApertureChange(CameraEx.ApertureInfo apertureInfo, CameraEx cameraEx) {
                    if (apertureInfo != null) {
                        m_aperture = apertureInfo.currentAperture;
                        updateStatusText();
                    }
                }
            });

            m_camera.setShutterSpeedChangeListener(new CameraEx.ShutterSpeedChangeListener() {
                @Override
                public void onShutterSpeedChange(CameraEx.ShutterSpeedInfo shutterSpeedInfo, CameraEx cameraEx) {
                    if (shutterSpeedInfo != null) {
                        m_shutterSpeed = formatShutter(
                                shutterSpeedInfo.currentShutterSpeed_n,
                                shutterSpeedInfo.currentShutterSpeed_d);
                        updateStatusText();
                    }
                }
            });

            initCameraDefaults();
            refreshExposureCache();
            updateStatusText();
        } catch (Throwable t) {
            Logger.error("onResume camera init error: " + t.toString());
            m_tvInstructions.setText("Init error: " + t.getMessage());
        }
    }

    private void startHttpServerWithFallback() {
        // Try port 80 first; if non-root UID blocks <1024, fall back to 8080
        int[] candidatePorts = new int[]{80, MacroHttpServer.DEFAULT_PORT};
        for (int port : candidatePorts) {
            try {
                MacroHttpServer srv = new MacroHttpServer(this, port);
                srv.start();
                m_httpServer = srv;
                m_httpPort = port;
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

        abortBracket();
        m_handler.removeCallbacks(m_checkFocusRunnable);

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
            m_wifiManager.setWifiEnabled(false);
        } catch (Throwable ignored) {
        }
        setAutoPowerOffMode(true);

        stopLiveviewSequence();

        if (m_camera != null) {
            m_surfaceHolder.removeCallback(this);
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
        // IMPORTANT: On Sony CameraEx, setParameters MUST use m_camera.createEmptyParameters(),
        // NEVER m_camera.getNormalCamera().getParameters() (which throws RuntimeException)!
        try {
            Camera.Parameters params = m_camera.createEmptyParameters();
            CameraEx.ParametersModifier modifier = m_camera.createParametersModifier(params);
            modifier.setDriveMode(CameraEx.ParametersModifier.DRIVE_MODE_SINGLE);
            params.setFocusMode(CameraEx.ParametersModifier.FOCUS_MODE_MANUAL);
            modifier.setSelfTimer(0);
            m_camera.getNormalCamera().setParameters(params);
        } catch (Throwable t) {
            Logger.error("setDefaults MF/drive warning: " + t.toString());
            try {
                Camera.Parameters focusParams = m_camera.createEmptyParameters();
                focusParams.setFocusMode(CameraEx.ParametersModifier.FOCUS_MODE_MANUAL);
                m_camera.getNormalCamera().setParameters(focusParams);
            } catch (Throwable ignored) {
            }
        }

        try {
            String platformVersion = ScalarProperties.getString(ScalarProperties.PROP_VERSION_PLATFORM);
            if (platformVersion != null) {
                String[] split = platformVersion.split("\\Q.\\E");
                if (split.length >= 2 && Integer.parseInt(split[1]) < 3) {
                    m_state = State.error;
                    m_tvInstructions.setText("ERROR: Focus drive not supported on this body");
                }
            }
        } catch (Throwable t) {
            Logger.error("Platform check warning: " + t.toString());
        }

        try {
            m_pictureReviewTime = m_autoReviewControl.getPictureReviewTime();
            m_autoReviewControl.setPictureReviewTime(0);
        } catch (Throwable t) {
            Logger.error("AutoReview init warning: " + t.toString());
        }
    }

    @SuppressWarnings("unchecked")
    private void refreshExposureCache() {
        if (m_camera == null) {
            return;
        }
        try {
            Camera.Parameters params = m_camera.getNormalCamera().getParameters();
            CameraEx.ParametersModifier modifier = m_camera.createParametersModifier(params);
            m_aperture = modifier.getAperture();
            Pair<Integer, Integer> ss = modifier.getShutterSpeed();
            if (ss != null) {
                m_shutterSpeed = formatShutter(ss.first, ss.second);
            }
            m_iso = modifier.getISOSensitivity();
            m_supportedIsos = (List<Integer>) modifier.getSupportedISOSensitivities();
        } catch (Throwable t) {
            Logger.error("refreshExposureCache warning: " + t.toString());
        }
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
                String portSuffix = (m_httpPort == 80) ? "" : (":" + m_httpPort);
                m_tvWifiStatus.setText("READY (" + ssid + "): http://" + m_wifiIp + portSuffix);
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

    // --- Hardware LiveView Sequence (Sony CameraSequence API) ---

    private void startLiveviewSequence() {
        synchronized (m_frameLock) {
            if (m_camera == null || m_cameraSequence != null) {
                return;
            }
            try {
                m_cameraSequence = CameraSequence.open(m_camera);
                if (m_cameraSequence != null) {
                    CameraSequence.Options opts = new CameraSequence.Options();
                    opts.setOption("PREVIEW_FRAME_RATE", 30000);
                    opts.setOption("PREVIEW_FRAME_WIDTH", 640);
                    opts.setOption("PREVIEW_FRAME_HEIGHT", 0);
                    opts.setOption("PREVIEW_FRAME_FORMAT", 256); // 256 = JPEG
                    opts.setOption("PREVIEW_FRAME_MAX_NUM", 1);
                    opts.setOption("JPEG_COMPRESS_RATE_DENOM", 15);
                    opts.setOption("JPEG_COMPRESS_MAX_SIZE", 100);
                    opts.setOption("INTERIM_PRE_REVIEW_ENABLED", true);
                    m_cameraSequence.startPreviewSequence(opts);
                    Logger.info("CameraSequence LiveView started (640px JPEG)");
                }
            } catch (Throwable t) {
                Logger.error("startLiveviewSequence error: " + t.toString());
                stopLiveviewSequence();
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

    public byte[] getLatestLiveViewJpeg() {
        synchronized (m_frameLock) {
            long now = System.currentTimeMillis();
            if (m_cameraSequence != null && m_state != State.capturing && (now - m_lastLiveviewFetchMs) >= 40) {
                m_lastLiveviewFetchMs = now;
                DeviceMemory[] frames = null;
                try {
                    frames = m_cameraSequence.getPreviewSequenceFrames(1);
                    if (frames != null && frames.length > 0 && frames[0] != null) {
                        DeviceBuffer dbuf = (DeviceBuffer) frames[0];
                        int dataSize = dbuf.getSize();
                        if (dataSize > 0 && dataSize <= LIVEVIEW_BUF_CAPACITY) {
                            if (m_liveviewByteBuf == null) {
                                m_liveviewByteBuf = ByteBuffer.allocateDirect(LIVEVIEW_BUF_CAPACITY);
                            }
                            m_liveviewByteBuf.rewind();
                            dbuf.read(m_liveviewByteBuf, dataSize, 0);
                            m_liveviewByteBuf.rewind();
                            byte[] jpeg = new byte[dataSize];
                            m_liveviewByteBuf.get(jpeg, 0, dataSize);
                            m_latestJpeg = jpeg;
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

    // --- Public API Methods called from MacroHttpServer ---

    public void driveFocusStep(final boolean far, final int speed) {
        m_handler.post(new Runnable() {
            @Override
            public void run() {
                if (m_camera == null || m_state == State.error || m_state == State.bracketing) {
                    return;
                }
                int clampedSpeed = Math.max(1, Math.min(4, speed));
                int stepDelta;
                if (clampedSpeed == 1) {
                    stepDelta = 1;
                } else if (clampedSpeed == 2) {
                    stepDelta = 3;
                } else if (clampedSpeed == 3) {
                    stepDelta = 6;
                } else {
                    stepDelta = 12;
                }

                // Use closed-loop target position with 50ms retry because Sony's
                // startOneShotFocusDrive frequently drops single unverified pulses.
                int basePos = (m_state == State.focusing) ? m_targetFocus : m_curFocus;
                int nextTarget = basePos + (far ? stepDelta : -stepDelta);
                if (m_maxFocus > 0) {
                    nextTarget = Math.max(0, Math.min(m_maxFocus, nextTarget));
                } else {
                    nextTarget = Math.max(0, nextTarget);
                }

                m_handler.removeCallbacks(m_checkFocusRunnable);
                m_focusQueue.clear();
                m_targetFocus = nextTarget;
                if (m_curFocus == m_targetFocus && m_maxFocus > 0) {
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

    public void gotoFocusPosition(final int targetPos) {
        m_handler.post(new Runnable() {
            @Override
            public void run() {
                if (m_camera == null || m_state == State.error) {
                    return;
                }
                m_handler.removeCallbacks(m_checkFocusRunnable);
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
                try {
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
                } catch (Throwable t) {
                    Logger.error("adjustExposure error: " + t.toString());
                }
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
        try {
            m_handler.removeCallbacks(m_checkFocusRunnable);
            m_camera.startOneShotFocusDrive(
                    m_curFocus < target ? CameraEx.FOCUS_DRIVE_DIRECTION_FAR : CameraEx.FOCUS_DRIVE_DIRECTION_NEAR,
                    speed);
            // Sony startOneShotFocusDrive does not always trigger FocusDriveListener on the first pulse;
            // schedule a 50ms check to retry until FocusDriveListener confirms movement.
            m_handler.postDelayed(m_checkFocusRunnable, 50);
        } catch (Throwable t) {
            Logger.error("driveTowardTarget error: " + t.toString());
            m_state = State.idle;
            updateStatusText();
        }
    }

    private void takePictureInternal() {
        try {
            m_camera.burstableTakePicture();
        } catch (Throwable t) {
            Logger.error("takePictureInternal error: " + t.toString());
            m_state = State.idle;
            updateStatusText();
        }
    }

    @Override
    public void onShutter(int i, CameraEx cameraEx) {
        try {
            m_camera.cancelTakePicture();
        } catch (Throwable ignored) {
        }
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
        sb.append("\"wifiIp\":\"").append(m_wifiIp).append("\",");
        sb.append("\"port\":").append(m_httpPort);
        sb.append("}");
        return sb.toString();
    }
}

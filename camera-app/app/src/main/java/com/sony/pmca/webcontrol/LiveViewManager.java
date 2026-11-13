package com.sony.pmca.webcontrol;

import com.github.ma1co.pmcademo.app.Logger;
import com.sony.scalar.hardware.CameraEx;
import com.sony.scalar.hardware.CameraSequence;
import com.sony.scalar.hardware.DeviceBuffer;
import com.sony.scalar.hardware.DeviceMemory;

import java.nio.ByteBuffer;
import java.util.LinkedList;

/**
 * Manages hardware LiveView streaming via Sony CameraSequence and
 * thread-safe frame delivery to MJPEG / snapshot HTTP clients.
 */
public class LiveViewManager {
    public static final int MAX_MJPEG_CLIENTS = 3;
    private static final int LIVEVIEW_BUF_CAPACITY = 128 * 1024;

    public static final class MjpegClient {
        volatile boolean closed = false;
    }

    public static final class LiveViewFrame {
        public final long seq;
        public final byte[] jpeg;

        public LiveViewFrame(long seq, byte[] jpeg) {
            this.seq = seq;
            this.jpeg = jpeg;
        }
    }

    private final Object m_frameLock = new Object();
    private final Object m_captureLock = new Object();

    private CameraSequence m_cameraSequence;
    private ByteBuffer m_liveviewByteBuf;
    private byte[] m_latestJpeg;
    private byte[] m_lastCapturedJpeg;
    private long m_lastLiveviewFetchMs = 0;
    private long m_frameSeq = 0;
    private final LinkedList<MjpegClient> m_mjpegClientList = new LinkedList<MjpegClient>();
    private int m_mjpegClients = 0;
    private volatile boolean m_liveviewRunning = false;
    private Thread m_liveviewThread;

    public void start(CameraEx camera, String memoryMapLibPath) {
        if (camera == null) {
            return;
        }
        synchronized (m_captureLock) {
            if (m_cameraSequence != null) {
                return;
            }
            try {
                m_cameraSequence = CameraSequence.open(camera);
                if (m_cameraSequence != null) {
                    CameraSequence.Options opts = new CameraSequence.Options();
                    opts.setOption("MEMORY_MAP_FILE", memoryMapLibPath);
                    opts.setOption("EXPOSURE_COUNT", 1);
                    opts.setOption("RECORD_COUNT", 1);
                    opts.setOption("INTERIM_PRE_REVIEW_ENABLED", false);
                    opts.setOption("AUTO_RELEASE_LOCK_ENABLED", false);
                    opts.setOption("PREVIEW_FRAME_RATE", 30000);
                    opts.setOption("PREVIEW_FRAME_WIDTH", 640);
                    opts.setOption("PREVIEW_FRAME_HEIGHT", 0);
                    m_cameraSequence.setReleaseLock(false);
                    m_cameraSequence.startPreviewSequence(opts);
                    synchronized (m_frameLock) {
                        m_liveviewRunning = true;
                        m_liveviewThread = new Thread(new Runnable() {
                            @Override
                            public void run() {
                                runProducer();
                            }
                        }, "LiveviewProducer");
                        m_liveviewThread.start();
                    }
                    Logger.info("CameraSequence LiveView started (640px JPEG)");
                }
            } catch (Throwable t) {
                Logger.error("startLiveviewSequence error: " + t.toString());
                m_cameraSequence = null;
            }
        }
    }

    public void stop() {
        Thread threadToJoin;
        synchronized (m_frameLock) {
            m_liveviewRunning = false;
            threadToJoin = m_liveviewThread;
            m_liveviewThread = null;
            m_frameLock.notifyAll();
        }
        if (threadToJoin != null && threadToJoin != Thread.currentThread()) {
            try {
                threadToJoin.join(500);
            } catch (InterruptedException ignored) {
            }
        }
        synchronized (m_captureLock) {
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

    public MjpegClient acquireMjpegClient() {
        synchronized (m_frameLock) {
            while (m_mjpegClientList.size() >= MAX_MJPEG_CLIENTS) {
                MjpegClient oldest = m_mjpegClientList.removeFirst();
                oldest.closed = true;
            }
            MjpegClient client = new MjpegClient();
            m_mjpegClientList.addLast(client);
            m_mjpegClients = m_mjpegClientList.size();
            m_frameLock.notifyAll();
            return client;
        }
    }

    public void releaseMjpegClient(MjpegClient client) {
        synchronized (m_frameLock) {
            if (client != null) {
                client.closed = true;
                m_mjpegClientList.remove(client);
            }
            m_mjpegClients = m_mjpegClientList.size();
            m_frameLock.notifyAll();
        }
    }

    public LiveViewFrame waitForNextFrame(MjpegClient client, long lastSeq, long timeoutMs) {
        synchronized (m_frameLock) {
            if (m_latestJpeg == null && m_liveviewRunning) {
                // Trigger an immediate fetch outside frameLock if no frame is cached yet
                m_frameLock.notifyAll();
            }
            long deadline = System.currentTimeMillis() + timeoutMs;
            while (m_liveviewRunning && (client == null || !client.closed) && m_frameSeq == lastSeq) {
                long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0) {
                    break;
                }
                try {
                    m_frameLock.wait(remaining);
                } catch (InterruptedException ignored) {
                    break;
                }
            }
            if (!m_liveviewRunning || (client != null && client.closed)) {
                return null;
            }
            // If timed out while still running (e.g. long exposure), return current seq so caller keeps waiting
            return new LiveViewFrame(m_frameSeq, m_latestJpeg);
        }
    }

    public byte[] getLatestLiveViewJpeg() {
        fetchPreviewFrame(33);
        synchronized (m_frameLock) {
            return m_latestJpeg;
        }
    }

    public void onPictureJpeg(byte[] data) {
        if (data == null) {
            return;
        }
        synchronized (m_frameLock) {
            m_latestJpeg = data;
            m_lastCapturedJpeg = data;
            m_frameSeq++;
            m_frameLock.notifyAll();
        }
    }

    public byte[] getLastCapturedJpeg() {
        synchronized (m_frameLock) {
            return m_lastCapturedJpeg;
        }
    }

    private void runProducer() {
        while (m_liveviewRunning) {
            long intervalMs;
            synchronized (m_frameLock) {
                while (m_liveviewRunning && m_mjpegClients <= 0) {
                    try {
                        m_frameLock.wait(500);
                    } catch (InterruptedException ignored) {
                    }
                }
                if (!m_liveviewRunning) {
                    break;
                }
                if (m_mjpegClients <= 1) {
                    intervalMs = 33;
                } else if (m_mjpegClients == 2) {
                    intervalMs = 50;
                } else {
                    intervalMs = 85;
                }
            }

            fetchPreviewFrame(intervalMs);

            try {
                Thread.sleep(intervalMs);
            } catch (InterruptedException ignored) {
            }
        }
    }

    private void fetchPreviewFrame(long minIntervalMs) {
        long now = System.currentTimeMillis();
        synchronized (m_frameLock) {
            if (!m_liveviewRunning || (now - m_lastLiveviewFetchMs) < minIntervalMs) {
                return;
            }
        }

        byte[] jpeg = null;
        synchronized (m_captureLock) {
            if (m_cameraSequence == null) {
                return;
            }
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
                        jpeg = new byte[size];
                        m_liveviewByteBuf.rewind();
                        m_liveviewByteBuf.get(jpeg, 0, size);
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

        if (jpeg != null) {
            synchronized (m_frameLock) {
                m_latestJpeg = jpeg;
                m_lastLiveviewFetchMs = System.currentTimeMillis();
                m_frameSeq++;
                m_frameLock.notifyAll();
            }
        }
    }
}

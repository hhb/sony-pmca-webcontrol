package com.sony.pmca.webcontrol;

import com.sony.scalar.hardware.CameraEx;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.concurrent.Callable;

/**
 * Event-driven, device-side closed-loop controller for camera parameters that
 * only expose relative/stepping APIs (focus, aperture, shutter speed, power zoom).
 *
 * Concurrency model:
 * - Each parameter has an independent GoalSlot, so different parameters run in parallel.
 * - Within the same parameter, a new command increments generationId (latest-wins preemption),
 *   immediately waking and aborting any in-flight older request for that parameter.
 * - Waiting happens on the calling HTTP worker thread; only individual short drive calls
 *   are dispatched to the Android main thread via runOnMainSync, so the camera UI never blocks.
 */
public class CameraGoalController {

    private static final int FOCUS_NEAR = 0;
    private static final int FOCUS_FAR = 1;
    private static final int ZOOM_TELE = 0;
    private static final int ZOOM_WIDE = 1;

    private static final class GoalSlot {
        long generation = 0;
        long updateSeq = 0;
        boolean hasValue = false;

        int v1 = 0;
        int v2 = 0;
        int min1 = 0;
        int max1 = 0;
        int minN = 0;
        int minD = 0;
        int maxN = 0;
        int maxD = 0;

        synchronized long nextGeneration() {
            generation++;
            notifyAll();
            return generation;
        }

        synchronized long getUpdateSeq() {
            return updateSeq;
        }

        synchronized boolean isCurrent(long myGen) {
            return generation == myGen;
        }

        synchronized void waitForChange(long myGen, long prevSeq, long timeoutMs) {
            long deadline = System.currentTimeMillis() + timeoutMs;
            while (generation == myGen && updateSeq == prevSeq) {
                long rem = deadline - System.currentTimeMillis();
                if (rem <= 0) break;
                try {
                    wait(rem);
                } catch (InterruptedException ignored) {
                    break;
                }
            }
        }
    }

    private final MacroServerActivity m_activity;
    private final GoalSlot m_focus = new GoalSlot();
    private final GoalSlot m_aperture = new GoalSlot();
    private final GoalSlot m_shutter = new GoalSlot();
    private final GoalSlot m_zoom = new GoalSlot();

    public CameraGoalController(MacroServerActivity activity) {
        m_activity = activity;
    }

    // --- Hardware Listener Hooks ---

    public void onFocusChanged(int currentPos, int maxPos) {
        synchronized (m_focus) {
            m_focus.hasValue = true;
            m_focus.v1 = currentPos;
            m_focus.max1 = maxPos;
            m_focus.updateSeq++;
            m_focus.notifyAll();
        }
    }

    public void onApertureChanged(int currentAperture, int minAperture, int maxAperture) {
        synchronized (m_aperture) {
            m_aperture.hasValue = true;
            m_aperture.v1 = currentAperture;
            m_aperture.min1 = minAperture;
            m_aperture.max1 = maxAperture;
            m_aperture.updateSeq++;
            m_aperture.notifyAll();
        }
    }

    public void onShutterChanged(int n, int d, int minN, int minD, int maxN, int maxD) {
        synchronized (m_shutter) {
            m_shutter.hasValue = true;
            m_shutter.v1 = n;
            m_shutter.v2 = d;
            m_shutter.minN = minN;
            m_shutter.minD = minD;
            m_shutter.maxN = maxN;
            m_shutter.maxD = maxD;
            m_shutter.updateSeq++;
            m_shutter.notifyAll();
        }
    }

    public void onZoomChanged(int opticalPosition, boolean stopped) {
        synchronized (m_zoom) {
            m_zoom.hasValue = true;
            m_zoom.v1 = opticalPosition;
            m_zoom.v2 = stopped ? 1 : 0;
            m_zoom.updateSeq++;
            m_zoom.notifyAll();
        }
    }

    // --- RPC Entrypoint ---

    public JSONObject execute(String method, JSONArray args) throws Exception {
        if ("setFocusPosition".equals(method)) {
            if (args == null || args.length() < 1) {
                throw new IllegalArgumentException("setFocusPosition requires (targetPos[, tolerance])");
            }
            int target = args.getInt(0);
            int tol = args.length() >= 2 ? args.getInt(1) : 2;
            return setFocusPosition(target, Math.max(0, tol));
        }
        if ("setAperture".equals(method)) {
            if (args == null || args.length() < 1) {
                throw new IllegalArgumentException("setAperture requires (fValue100)");
            }
            return setAperture(args.getInt(0));
        }
        if ("setShutterSpeed".equals(method)) {
            if (args == null || args.length() < 2) {
                throw new IllegalArgumentException("setShutterSpeed requires (numerator, denominator)");
            }
            return setShutterSpeed(args.getInt(0), args.getInt(1));
        }
        if ("setZoomPosition".equals(method)) {
            if (args == null || args.length() < 1) {
                throw new IllegalArgumentException("setZoomPosition requires (opticalPosition[, tolerance])");
            }
            int target = args.getInt(0);
            int tol = args.length() >= 2 ? args.getInt(1) : 1;
            return setZoomPosition(target, Math.max(0, tol));
        }
        throw new NoSuchMethodException("Unknown highlevel method: " + method);
    }

    // --- Closed-Loop Implementations ---

    private JSONObject setFocusPosition(int targetPos, int tolerance) throws Exception {
        final long myGen = m_focus.nextGeneration();
        int stalled = 0;
        int steps = 0;

        for (int i = 0; i < 60; i++) {
            int current;
            boolean has;
            int maxPos;
            synchronized (m_focus) {
                if (!m_focus.isCurrent(myGen)) {
                    return buildResult(false, true, false, m_focus.v1, targetPos, steps);
                }
                has = m_focus.hasValue;
                current = m_focus.v1;
                maxPos = m_focus.max1;
            }
            if (!has) {
                throw new IllegalStateException("Focus position unavailable (ensure lens is in MF)");
            }

            int clampedTarget = (maxPos > 0) ? Math.max(0, Math.min(maxPos, targetPos)) : targetPos;
            int diff = clampedTarget - current;
            if (Math.abs(diff) <= tolerance) {
                return buildResult(true, false, false, current, clampedTarget, steps);
            }

            final int dir = diff > 0 ? FOCUS_FAR : FOCUS_NEAR;
            final int absDiff = Math.abs(diff);
            final int speed = absDiff > 30 ? 4 : absDiff > 12 ? 3 : absDiff > 4 ? 2 : 1;

            long seqBefore = m_focus.getUpdateSeq();
            driveOnMain(new Runnable() {
                @Override
                public void run() {
                    CameraEx cam = m_activity.getCameraEx();
                    if (cam != null) cam.startOneShotFocusDrive(dir, speed);
                }
            });
            steps++;

            m_focus.waitForChange(myGen, seqBefore, 260);
            if (!m_focus.isCurrent(myGen)) {
                synchronized (m_focus) {
                    return buildResult(false, true, false, m_focus.v1, clampedTarget, steps);
                }
            }
            if (m_focus.getUpdateSeq() == seqBefore) {
                stalled++;
                if (stalled >= 3) {
                    synchronized (m_focus) {
                        return buildResult(false, false, true, m_focus.v1, clampedTarget, steps);
                    }
                }
            } else {
                stalled = 0;
            }
        }

        synchronized (m_focus) {
            return buildResult(Math.abs(targetPos - m_focus.v1) <= tolerance, false, false, m_focus.v1, targetPos, steps);
        }
    }

    private JSONObject setAperture(int targetF100) throws Exception {
        final long myGen = m_aperture.nextGeneration();
        int stalled = 0;
        int steps = 0;

        for (int i = 0; i < 45; i++) {
            int current;
            int minAp;
            int maxAp;
            boolean has;
            synchronized (m_aperture) {
                if (!m_aperture.isCurrent(myGen)) {
                    return buildResult(false, true, false, m_aperture.v1, targetF100, steps);
                }
                has = m_aperture.hasValue;
                current = m_aperture.v1;
                minAp = m_aperture.min1;
                maxAp = m_aperture.max1;
            }
            if (!has) {
                throw new IllegalStateException("Aperture state unavailable");
            }

            int clamped = targetF100;
            if (minAp > 0 && clamped < minAp) clamped = minAp;
            if (maxAp > 0 && clamped > maxAp) clamped = maxAp;

            int diff = clamped - current;
            if (Math.abs(diff) <= 15) {
                return buildResult(true, false, false, current, clamped, steps);
            }

            final boolean inc = diff > 0;
            long seqBefore = m_aperture.getUpdateSeq();
            driveOnMain(new Runnable() {
                @Override
                public void run() {
                    CameraEx cam = m_activity.getCameraEx();
                    if (cam != null) {
                        if (inc) cam.incrementAperture();
                        else cam.decrementAperture();
                    }
                }
            });
            steps++;

            m_aperture.waitForChange(myGen, seqBefore, 250);
            if (!m_aperture.isCurrent(myGen)) {
                synchronized (m_aperture) {
                    return buildResult(false, true, false, m_aperture.v1, clamped, steps);
                }
            }
            if (m_aperture.getUpdateSeq() == seqBefore) {
                stalled++;
                if (stalled >= 2) {
                    synchronized (m_aperture) {
                        return buildResult(false, false, true, m_aperture.v1, clamped, steps);
                    }
                }
            } else {
                stalled = 0;
            }
        }

        synchronized (m_aperture) {
            return buildResult(false, false, false, m_aperture.v1, targetF100, steps);
        }
    }

    private JSONObject setShutterSpeed(int targetN, int targetD) throws Exception {
        if (targetN <= 0 || targetD <= 0) {
            throw new IllegalArgumentException("Shutter numerator and denominator must be > 0");
        }
        final double targetSec = (double) targetN / (double) targetD;
        final long myGen = m_shutter.nextGeneration();
        int stalled = 0;
        int steps = 0;

        for (int i = 0; i < 65; i++) {
            int curN;
            int curD;
            boolean has;
            synchronized (m_shutter) {
                if (!m_shutter.isCurrent(myGen)) {
                    return buildShutterResult(false, true, false, m_shutter.v1, m_shutter.v2, targetN, targetD, steps);
                }
                has = m_shutter.hasValue;
                curN = m_shutter.v1;
                curD = m_shutter.v2;
            }
            if (!has || curD <= 0) {
                throw new IllegalStateException("Shutter speed state unavailable");
            }

            double curSec = (double) curN / (double) curD;
            double ratio = curSec / targetSec;
            // Within ~10% (less than 1/6 EV) is considered converged
            if (ratio >= 0.90 && ratio <= 1.11) {
                return buildShutterResult(true, false, false, curN, curD, targetN, targetD, steps);
            }

            // incrementShutterSpeed -> faster (smaller seconds), decrementShutterSpeed -> slower (larger seconds)
            final boolean faster = curSec > targetSec;
            long seqBefore = m_shutter.getUpdateSeq();
            driveOnMain(new Runnable() {
                @Override
                public void run() {
                    CameraEx cam = m_activity.getCameraEx();
                    if (cam != null) {
                        if (faster) cam.incrementShutterSpeed();
                        else cam.decrementShutterSpeed();
                    }
                }
            });
            steps++;

            m_shutter.waitForChange(myGen, seqBefore, 250);
            if (!m_shutter.isCurrent(myGen)) {
                synchronized (m_shutter) {
                    return buildShutterResult(false, true, false, m_shutter.v1, m_shutter.v2, targetN, targetD, steps);
                }
            }
            if (m_shutter.getUpdateSeq() == seqBefore) {
                stalled++;
                if (stalled >= 2) {
                    synchronized (m_shutter) {
                        return buildShutterResult(false, false, true, m_shutter.v1, m_shutter.v2, targetN, targetD, steps);
                    }
                }
            } else {
                stalled = 0;
            }
        }

        synchronized (m_shutter) {
            return buildShutterResult(false, false, false, m_shutter.v1, m_shutter.v2, targetN, targetD, steps);
        }
    }

    private JSONObject setZoomPosition(int targetPos, int tolerance) throws Exception {
        final long myGen = m_zoom.nextGeneration();
        int stalled = 0;
        int steps = 0;

        for (int i = 0; i < 60; i++) {
            int current;
            boolean has;
            synchronized (m_zoom) {
                if (!m_zoom.isCurrent(myGen)) {
                    return buildResult(false, true, false, m_zoom.v1, targetPos, steps);
                }
                has = m_zoom.hasValue;
                current = m_zoom.v1;
            }
            if (!has) {
                throw new IllegalStateException("Zoom state unavailable");
            }

            int diff = targetPos - current;
            if (Math.abs(diff) <= tolerance) {
                return buildResult(true, false, false, current, targetPos, steps);
            }

            final int dir = diff > 0 ? CameraEx.ZOOM_DIRECTION_TELE : CameraEx.ZOOM_DIRECTION_WIDE;
            final int speed = Math.abs(diff) > 12 ? 2 : 1;
            long seqBefore = m_zoom.getUpdateSeq();
            driveOnMain(new Runnable() {
                @Override
                public void run() {
                    CameraEx cam = m_activity.getCameraEx();
                    if (cam != null) cam.startZoom(dir, speed);
                }
            });
            steps++;

            m_zoom.waitForChange(myGen, seqBefore, 120);
            driveOnMain(new Runnable() {
                @Override
                public void run() {
                    CameraEx cam = m_activity.getCameraEx();
                    if (cam != null) cam.stopZoom();
                }
            });
            if (!m_zoom.isCurrent(myGen)) {
                synchronized (m_zoom) {
                    return buildResult(false, true, false, m_zoom.v1, targetPos, steps);
                }
            }
            if (m_zoom.getUpdateSeq() == seqBefore) {
                stalled++;
                if (stalled >= 2) {
                    synchronized (m_zoom) {
                        return buildResult(false, false, true, m_zoom.v1, targetPos, steps);
                    }
                }
            } else {
                stalled = 0;
            }
        }

        synchronized (m_zoom) {
            return buildResult(Math.abs(targetPos - m_zoom.v1) <= tolerance, false, false, m_zoom.v1, targetPos, steps);
        }
    }

    private void driveOnMain(final Runnable r) throws Exception {
        m_activity.runOnMainSync(new Callable<Void>() {
            @Override
            public Void call() {
                r.run();
                return null;
            }
        }, MacroServerActivity.MAIN_THREAD_TIMEOUT_MS);
    }

    private static JSONObject buildResult(boolean converged, boolean superseded, boolean stalled, int actual, int target, int steps) {
        JSONObject obj = new JSONObject();
        try {
            obj.put("converged", converged);
            obj.put("superseded", superseded);
            obj.put("stalled", stalled);
            obj.put("position", actual);
            obj.put("target", target);
            obj.put("steps", steps);
        } catch (Throwable ignored) {
        }
        return obj;
    }

    private static JSONObject buildShutterResult(boolean converged, boolean superseded, boolean stalled, int n, int d, int targetN, int targetD, int steps) {
        JSONObject obj = new JSONObject();
        try {
            obj.put("converged", converged);
            obj.put("superseded", superseded);
            obj.put("stalled", stalled);
            obj.put("currentShutterSpeed_n", n);
            obj.put("currentShutterSpeed_d", d);
            obj.put("target_n", targetN);
            obj.put("target_d", targetD);
            obj.put("steps", steps);
        } catch (Throwable ignored) {
        }
        return obj;
    }
}

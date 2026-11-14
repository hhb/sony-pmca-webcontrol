package com.github.ma1co.pmcademo.app;

import android.os.Environment;

import java.io.*;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class Logger {
    private static final SimpleDateFormat TIME_FMT = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US);
    private static final int BUFFER_SIZE = 4096;
    private static BufferedWriter s_writer = null;

    public static File getFile() {
        return new File(Environment.getExternalStorageDirectory(), "PMCADEMO/LOG.TXT");
    }

    public static synchronized void flush() {
        if (s_writer != null) {
            try {
                s_writer.flush();
            } catch (IOException e) {
                try { s_writer.close(); } catch (IOException ignored) {}
                s_writer = null;
            }
        }
    }

    protected static synchronized void log(String msg) {
        logLine(msg, false);
    }

    private static synchronized void logLine(String msg, boolean flushNow) {
        try {
            if (s_writer == null) {
                File f = getFile();
                if (f.getParentFile() != null) {
                    f.getParentFile().mkdirs();
                }
                s_writer = new BufferedWriter(new FileWriter(f, true), BUFFER_SIZE);
            }
            s_writer.append(msg);
            s_writer.newLine();
            if (flushNow) {
                s_writer.flush();
            }
        } catch (IOException e) {
            if (s_writer != null) {
                try { s_writer.close(); } catch (IOException ignored) {}
                s_writer = null;
            }
        }
    }

    protected static void log(String type, String msg) {
        log(type, msg, false);
    }

    private static void log(String type, String msg, boolean flushNow) {
        String ts;
        synchronized (Logger.class) {
            ts = TIME_FMT.format(new Date());
        }
        logLine("[" + ts + "] [" + type + "] " + msg, flushNow);
    }

    public static void info(String msg) { log("INFO", msg, false); }
    public static void error(String msg) { log("ERROR", msg, true); }
}

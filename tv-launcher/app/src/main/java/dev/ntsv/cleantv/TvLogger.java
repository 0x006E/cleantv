package dev.ntsv.cleantv;

import android.util.Log;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Centralized, decoupled logger for CleanTV Launcher.
 * Provides formatted timestamps, memory tracking, and tag-based filtering.
 */
public final class TvLogger {
    private static final String GLOBAL_TAG = "CleanTV";
    private static final SimpleDateFormat DATE_FORMAT = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);

    private TvLogger() {}

    public static void d(String subTag, String message) {
        Log.d(GLOBAL_TAG + ":" + subTag, format(message));
    }

    public static void i(String subTag, String message) {
        Log.i(GLOBAL_TAG + ":" + subTag, format(message));
    }

    public static void w(String subTag, String message) {
        Log.w(GLOBAL_TAG + ":" + subTag, format(message));
    }

    public static void e(String subTag, String message, Throwable t) {
        Log.e(GLOBAL_TAG + ":" + subTag, format(message), t);
    }

    private static String format(String message) {
        return "[" + DATE_FORMAT.format(new Date()) + "] " + message;
    }

    public static void logMemory(String location) {
        Runtime runtime = Runtime.getRuntime();
        long usedMem = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024);
        long maxMem = runtime.maxMemory() / (1024 * 1024);
        i("MemProfile", location + " -> Memory: " + usedMem + "MB / " + maxMem + "MB");
    }
}


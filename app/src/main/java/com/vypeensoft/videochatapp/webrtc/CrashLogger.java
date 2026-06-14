package com.vypeensoft.videochatapp.webrtc;

import android.content.Context;
import android.os.Build;
import android.util.Log;
import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class CrashLogger {
    private static final String TAG = "CrashLogger";

    public static void init(final Context context) {
        final Thread.UncaughtExceptionHandler defaultHandler = Thread.getDefaultUncaughtExceptionHandler();

        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            @Override
            public void uncaughtException(Thread thread, Throwable throwable) {
                saveCrashLog(context, throwable);
                
                // Call default handler to continue process termination
                if (defaultHandler != null) {
                    defaultHandler.uncaughtException(thread, throwable);
                }
            }
        });
    }

    private static void saveCrashLog(Context context, Throwable throwable) {
        try {
            String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date());
            String fileName = "crash_" + timestamp + ".txt";
            
            File dir = getLogDirectory(context);
            if (!dir.exists()) {
                dir.mkdirs();
            }

            File logFile = new File(dir, fileName);
            FileWriter writer = new FileWriter(logFile);
            PrintWriter printWriter = new PrintWriter(writer);

            printWriter.println("App version: 1.0");
            printWriter.println("Android version: " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")");
            printWriter.println("Device Model: " + Build.MODEL);
            printWriter.println("Manufacturer: " + Build.MANUFACTURER);
            printWriter.println("Date: " + timestamp);
            printWriter.println("----------------------------------------");
            throwable.printStackTrace(printWriter);
            
            printWriter.close();
            writer.close();
            
            Log.d(TAG, "Crash log written to: " + logFile.getAbsolutePath());
        } catch (Exception e) {
            Log.e(TAG, "Failed to write crash log file", e);
        }
    }

    private static File getLogDirectory(Context context) {
        File dir = new File("/sdcard/Vypeensoft/Video_Caller/logs");
        boolean isWritable = false;
        try {
            if (!dir.exists()) {
                dir.mkdirs();
            }
            if (dir.exists()) {
                File testFile = new File(dir, "test_log_write_" + System.currentTimeMillis() + ".tmp");
                if (testFile.createNewFile()) {
                    testFile.delete();
                    isWritable = true;
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Log check error", e);
        }

        if (!isWritable) {
            File externalDir = context.getExternalFilesDir(null);
            if (externalDir != null) {
                dir = new File(externalDir, "Vypeensoft/Video_Caller/logs");
            } else {
                dir = new File(context.getFilesDir(), "Vypeensoft/Video_Caller/logs");
            }
            dir.mkdirs();
        }
        return dir;
    }
}

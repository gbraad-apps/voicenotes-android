package nl.gbraad.transcribe;

import android.os.Build;
import android.util.Log;
import java.io.File;
import java.io.InputStream;

public class WhisperLib {
    private static final String TAG = "WhisperLib";

    static {
        Log.d(TAG, "Primary ABI: " + Build.SUPPORTED_ABIS[0]);
        boolean loadedOptimized = false;

        if (isArmV7a()) {
            String cpuInfo = readCpuInfo();
            if (cpuInfo != null && cpuInfo.contains("vfpv4")) {
                try {
                    System.loadLibrary("whisper_vfpv4");
                    loadedOptimized = true;
                    Log.d(TAG, "Loaded libwhisper_vfpv4.so");
                } catch (UnsatisfiedLinkError e) {
                    Log.w(TAG, "vfpv4 lib not available, falling back");
                }
            }
        } else if (isArmV8a()) {
            String cpuInfo = readCpuInfo();
            if (cpuInfo != null && cpuInfo.contains("fphp")) {
                try {
                    System.loadLibrary("whisper_v8fp16_va");
                    loadedOptimized = true;
                    Log.d(TAG, "Loaded libwhisper_v8fp16_va.so");
                } catch (UnsatisfiedLinkError e) {
                    Log.w(TAG, "v8fp16 lib not available, falling back");
                }
            }
        }

        if (!loadedOptimized) {
            System.loadLibrary("whisper");
            Log.d(TAG, "Loaded libwhisper.so");
        }
    }

    public static native long initContextFromInputStream(InputStream inputStream);
    public static native long initContext(String modelPath);
    public static native void freeContext(long contextPtr);
    public static native void stopTranscription();
    public static native void resetAbort();
    public static native void fullTranscribe(long contextPtr, int numThreads,
            float[] audioData, String language, WhisperCallback callback);
    public static native int getTextSegmentCount(long contextPtr);
    public static native String getTextSegment(long contextPtr, int index);
    public static native String getSystemInfo();

    private static boolean isArmV7a() {
        return Build.SUPPORTED_ABIS[0].equals("armeabi-v7a");
    }

    private static boolean isArmV8a() {
        return Build.SUPPORTED_ABIS[0].equals("arm64-v8a");
    }

    private static String readCpuInfo() {
        try (java.io.BufferedReader br = new java.io.BufferedReader(
                new java.io.InputStreamReader(new java.io.FileInputStream("/proc/cpuinfo")))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line).append('\n');
            return sb.toString();
        } catch (Exception e) {
            return null;
        }
    }

    public static int preferredThreadCount() {
        return Math.max(1, Math.min(Runtime.getRuntime().availableProcessors() - 1, 4));
    }
}

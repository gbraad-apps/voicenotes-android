package nl.gbraad.transcribe;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.util.Log;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Captures microphone audio at 16 kHz mono 16-bit PCM via AudioRecord.
 * Simultaneously:
 *  - writes a WAV file for later M4A conversion
 *  - emits overlapping float[] chunks for real-time Whisper transcription
 */
public class LiveRecorder {

    private static final String TAG = "LiveRecorder";

    public static final int SAMPLE_RATE   = 16000;
    public static final int OVERLAP_SECS  = 2;

    public interface Callback {
        /** Called from capture thread; submit to Whisper executor. */
        void onChunkReady(float[] samples, int chunkIndex);
        /** Peak amplitude 0–32767 for the level meter. */
        void onAmplitude(int amp);
    }

    private final int chunkSamples;
    private final int overlapSamples;

    private AudioRecord audioRecord;
    private Thread captureThread;
    private volatile boolean running = false;
    private volatile boolean paused  = false;

    private RandomAccessFile wavRaf;
    private long totalPcmBytes = 0;

    public static final int DEFAULT_CHUNK_SECS = 5;

    public LiveRecorder(int chunkSeconds) {
        this.chunkSamples   = chunkSeconds * SAMPLE_RATE;
        this.overlapSamples = OVERLAP_SECS * SAMPLE_RATE;
    }

    public void start(File wavOutput, Callback cb) throws IOException {
        int minBuf = AudioRecord.getMinBufferSize(
                SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        minBuf = Math.max(minBuf, 4096);

        // AudioSource.DEFAULT follows Android audio routing automatically:
        // wired headset mic → Bluetooth SCO → built-in mic
        audioRecord = new AudioRecord(
                MediaRecorder.AudioSource.DEFAULT,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                minBuf);

        if (audioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
            throw new IOException("AudioRecord failed to initialize");
        }

        wavRaf = new RandomAccessFile(wavOutput, "rw");
        wavRaf.write(buildWavHeader(0)); // placeholder header

        audioRecord.startRecording();
        running = true;

        captureThread = new Thread(() -> captureLoop(cb), "LiveRecorder");
        captureThread.start();
    }

    private void captureLoop(Callback cb) {
        final int readSize   = 1024; // shorts per read
        final short[] readBuf   = new short[readSize];
        // Rolling window: overlap + one full chunk
        final short[] window = new short[chunkSamples + overlapSamples];
        int windowPos = 0;
        int samplesThisChunk = 0;
        int chunkIndex = 0;

        while (running) {
            if (paused) { try { Thread.sleep(50); } catch (InterruptedException ignored) {} continue; }
            int got = audioRecord.read(readBuf, 0, readSize);
            if (got <= 0) continue;

            // Write PCM to WAV file
            byte[] bytes = shortsToBytes(readBuf, got);
            try {
                wavRaf.write(bytes);
                totalPcmBytes += bytes.length;
            } catch (IOException e) {
                Log.e(TAG, "WAV write error", e);
                break;
            }

            // Amplitude for level meter
            int maxAmp = 0;
            for (int i = 0; i < got; i++) maxAmp = Math.max(maxAmp, Math.abs(readBuf[i]));
            cb.onAmplitude(maxAmp);

            // Fill rolling window
            for (int i = 0; i < got; i++) {
                if (windowPos < window.length) {
                    window[windowPos++] = readBuf[i];
                }
            }
            samplesThisChunk += got;

            // Emit chunk when interval reached
            if (samplesThisChunk >= chunkSamples) {
                int len = Math.min(windowPos, chunkSamples + overlapSamples);
                float[] chunk = new float[len];
                for (int i = 0; i < len; i++) chunk[i] = window[i] / 32768.0f;
                cb.onChunkReady(chunk, chunkIndex++);

                // Slide window: keep overlap
                int keep = Math.min(overlapSamples, windowPos);
                int keepFrom = windowPos - keep;
                System.arraycopy(window, keepFrom, window, 0, keep);
                windowPos = keep;
                samplesThisChunk = 0;
            }
        }

        // Emit final partial chunk (whatever is left)
        if (windowPos > SAMPLE_RATE) { // at least 1 second
            float[] last = new float[windowPos];
            for (int i = 0; i < windowPos; i++) last[i] = window[i] / 32768.0f;
            cb.onChunkReady(last, chunkIndex);
        }
    }

    /** Pause capturing — AudioRecord stops reading, Whisper drains the remaining buffer. */
    public void pause() {
        paused = true;
        if (audioRecord != null) audioRecord.stop();
    }

    /** Resume capturing after a pause. */
    public void resume() {
        paused = false;
        if (audioRecord != null) audioRecord.startRecording();
    }

    public boolean isPaused() { return paused; }

    /**
     * Stop and re-open AudioRecord so it picks up a newly connected/disconnected
     * headset mic. Audio data collection continues uninterrupted.
     */
    public void restartAudioRecord() {
        if (audioRecord == null || paused) return;
        try {
            audioRecord.stop();
            audioRecord.release();
        } catch (Exception ignored) {}

        int minBuf = AudioRecord.getMinBufferSize(
                SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        minBuf = Math.max(minBuf, 4096);

        audioRecord = new AudioRecord(
                MediaRecorder.AudioSource.DEFAULT, // re-queries audio routing
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                minBuf);

        if (audioRecord.getState() == AudioRecord.STATE_INITIALIZED) {
            audioRecord.startRecording();
            android.util.Log.d("LiveRecorder", "AudioRecord restarted for new device routing");
        }
    }

    /** Stop recording and finalise the WAV header. Returns the closed WAV file. */
    public File stop(File wavOutput) {
        running = false;
        if (audioRecord != null) {
            try { audioRecord.stop(); } catch (Exception ignored) {}
            audioRecord.release();
            audioRecord = null;
        }
        if (captureThread != null) {
            try { captureThread.join(3000); } catch (InterruptedException ignored) {}
        }
        // Finalise WAV header with real data length
        try {
            wavRaf.seek(0);
            wavRaf.write(buildWavHeader((int) totalPcmBytes));
            wavRaf.close();
        } catch (IOException e) {
            Log.e(TAG, "WAV finalise error", e);
        }
        return wavOutput;
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private static byte[] shortsToBytes(short[] shorts, int count) {
        ByteBuffer buf = ByteBuffer.allocate(count * 2).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < count; i++) buf.putShort(shorts[i]);
        return buf.array();
    }

    private static byte[] buildWavHeader(int pcmBytes) {
        int byteRate  = SAMPLE_RATE * 1 * 2;
        ByteBuffer b = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN);
        b.put("RIFF".getBytes());
        b.putInt(pcmBytes + 36);
        b.put("WAVE".getBytes());
        b.put("fmt ".getBytes());
        b.putInt(16);
        b.putShort((short) 1);   // PCM
        b.putShort((short) 1);   // mono
        b.putInt(SAMPLE_RATE);
        b.putInt(byteRate);
        b.putShort((short) 2);   // block align
        b.putShort((short) 16);  // bits/sample
        b.put("data".getBytes());
        b.putInt(pcmBytes);
        return b.array();
    }
}

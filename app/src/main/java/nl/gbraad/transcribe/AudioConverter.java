package nl.gbraad.transcribe;

import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;
import android.util.Log;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ShortBuffer;

public class AudioConverter {
    private static final String TAG = "AudioConverter";
    private static final int TARGET_SAMPLE_RATE = 16000;
    private static final int TARGET_CHANNELS = 1;
    private static final int TARGET_BIT_DEPTH = 16;

    public interface ProgressListener {
        void onProgress(float progress);
    }

    /**
     * Converts any audio/video URI to a float[] of 16kHz mono PCM samples ready for Whisper.
     * Returns null on failure.
     */
    public static float[] convertToSamples(Context context, Uri uri, ProgressListener listener)
            throws IOException {
        File wavFile = convertToWav(context, uri, listener);
        if (wavFile == null) return null;
        try {
            return wavFileToFloatSamples(wavFile);
        } finally {
            wavFile.delete();
        }
    }

    private static File convertToWav(Context context, Uri uri, ProgressListener listener)
            throws IOException {
        MediaExtractor extractor = new MediaExtractor();
        try {
            extractor.setDataSource(context, uri, null);
            int trackIndex = findAudioTrack(extractor);
            if (trackIndex == -1) {
                Log.w(TAG, "No audio track found");
                return null;
            }
            extractor.selectTrack(trackIndex);
            MediaFormat format = extractor.getTrackFormat(trackIndex);

            File outFile = File.createTempFile("whisper_", ".wav", context.getCacheDir());
            boolean ok = processAudio(extractor, format, outFile, listener);
            return ok ? outFile : null;
        } finally {
            extractor.release();
        }
    }

    private static boolean processAudio(MediaExtractor extractor, MediaFormat format,
                                         File outFile, ProgressListener listener) {
        String mime = format.getString(MediaFormat.KEY_MIME);
        if (mime == null) return false;
        int srcSampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE);
        int srcChannels   = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
        long durationUs   = format.containsKey(MediaFormat.KEY_DURATION)
                            ? format.getLong(MediaFormat.KEY_DURATION) : 0;

        MediaCodec codec;
        try {
            codec = MediaCodec.createDecoderByType(mime);
        } catch (IOException e) {
            Log.e(TAG, "No decoder for " + mime, e);
            return false;
        }
        codec.configure(format, null, null, 0);
        codec.start();

        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        boolean sawInputEOS = false, sawOutputEOS = false;
        long totalBytes = 0;

        try (RandomAccessFile raf = new RandomAccessFile(outFile, "rw")) {
            raf.write(buildWavHeader(0)); // placeholder header

            while (!sawOutputEOS) {
                if (!sawInputEOS) {
                    int inIdx = codec.dequeueInputBuffer(10_000);
                    if (inIdx >= 0) {
                        ByteBuffer inBuf = codec.getInputBuffer(inIdx);
                        int n = extractor.readSampleData(inBuf, 0);
                        if (n < 0) {
                            sawInputEOS = true;
                            codec.queueInputBuffer(inIdx, 0, 0, 0,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                        } else {
                            codec.queueInputBuffer(inIdx, 0, n, extractor.getSampleTime(), 0);
                            extractor.advance();
                        }
                    }
                }

                int outIdx = codec.dequeueOutputBuffer(info, 10_000);
                if (outIdx >= 0) {
                    ByteBuffer outBuf = codec.getOutputBuffer(outIdx);
                    if (outBuf != null && info.size > 0) {
                        byte[] chunk = new byte[info.size];
                        outBuf.position(info.offset);
                        outBuf.limit(info.offset + info.size);
                        outBuf.get(chunk);

                        byte[] pcm = processPcmChunk(chunk, srcSampleRate, srcChannels);
                        raf.write(pcm);
                        totalBytes += pcm.length;

                        if (listener != null && durationUs > 0) {
                            float progress = Math.min(1f,
                                    (float) info.presentationTimeUs / durationUs);
                            listener.onProgress(progress);
                        }
                    }
                    codec.releaseOutputBuffer(outIdx, false);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        sawOutputEOS = true;
                    }
                }
            }

            // rewrite header with real data size
            raf.seek(0);
            raf.write(buildWavHeader((int) totalBytes));
            return true;
        } catch (IOException e) {
            Log.e(TAG, "Audio processing failed", e);
            return false;
        } finally {
            codec.stop();
            codec.release();
        }
    }

    private static byte[] processPcmChunk(byte[] chunk, int srcSampleRate, int srcChannels) {
        ShortBuffer sb = ByteBuffer.wrap(chunk).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer();
        short[] shorts = new short[sb.remaining()];
        sb.get(shorts);

        short[] mono = srcChannels == 2 ? stereoToMono(shorts) : shorts;
        short[] resampled = srcSampleRate != TARGET_SAMPLE_RATE
                ? resample(mono, srcSampleRate) : mono;

        ByteBuffer out = ByteBuffer.allocate(resampled.length * 2).order(ByteOrder.LITTLE_ENDIAN);
        for (short s : resampled) out.putShort(s);
        return out.array();
    }

    private static short[] stereoToMono(short[] stereo) {
        short[] mono = new short[stereo.length / 2];
        for (int i = 0; i < mono.length; i++) {
            mono[i] = (short) ((stereo[i * 2] + stereo[i * 2 + 1]) / 2);
        }
        return mono;
    }

    private static short[] resample(short[] input, int srcRate) {
        double ratio = (double) srcRate / TARGET_SAMPLE_RATE;
        short[] out = new short[(int) (input.length / ratio)];
        for (int i = 0; i < out.length; i++) {
            int idx = (int) (i * ratio);
            out[i] = idx < input.length ? input[idx] : 0;
        }
        return out;
    }

    private static float[] wavFileToFloatSamples(File wavFile) throws IOException {
        try (RandomAccessFile raf = new RandomAccessFile(wavFile, "r")) {
            raf.seek(44); // skip WAV header
            long remaining = (raf.length() - 44) / 2;
            float[] samples = new float[(int) remaining];
            byte[] buf = new byte[2];
            for (int i = 0; i < samples.length; i++) {
                raf.readFully(buf);
                short s = (short) ((buf[1] << 8) | (buf[0] & 0xFF));
                samples[i] = s / 32768.0f;
            }
            return samples;
        }
    }

    private static int findAudioTrack(MediaExtractor extractor) {
        for (int i = 0; i < extractor.getTrackCount(); i++) {
            String mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME);
            if (mime != null && mime.startsWith("audio/")) return i;
        }
        return -1;
    }

    private static byte[] buildWavHeader(int pcmBytes) {
        int byteRate  = TARGET_SAMPLE_RATE * TARGET_CHANNELS * (TARGET_BIT_DEPTH / 8);
        int blockAlign = TARGET_CHANNELS * (TARGET_BIT_DEPTH / 8);
        ByteBuffer b = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN);
        b.put("RIFF".getBytes());
        b.putInt(pcmBytes + 36);
        b.put("WAVE".getBytes());
        b.put("fmt ".getBytes());
        b.putInt(16);
        b.putShort((short) 1);
        b.putShort((short) TARGET_CHANNELS);
        b.putInt(TARGET_SAMPLE_RATE);
        b.putInt(byteRate);
        b.putShort((short) blockAlign);
        b.putShort((short) TARGET_BIT_DEPTH);
        b.put("data".getBytes());
        b.putInt(pcmBytes);
        return b.array();
    }
}

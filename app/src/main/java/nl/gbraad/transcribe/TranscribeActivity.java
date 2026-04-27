package nl.gbraad.transcribe;

import android.Manifest;
import android.app.AlertDialog;
import android.content.Intent;
import android.media.AudioDeviceCallback;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.content.pm.PackageManager;
import android.media.MediaPlayer;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.documentfile.provider.DocumentFile;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class TranscribeActivity extends AppCompatActivity {

    private static final String TAG = "TranscribeActivity";
    private static final String PREFS = "transcribe";
    private static final String MODEL_URI_KEY = "model_uri";
    private static final String MODEL_PATH_KEY = "model_path";
    public  static final String EXTRA_MODE  = "mode";
    public  static final String MODE_RECORD = "record";
    public  static final String MODE_TEXT   = "text";

    private static final String[][] MODELS = {
        {"ggml-base.bin",  "Base (~141 MB) — recommended",
         "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-base.bin"},
        {"ggml-tiny.bin",  "Tiny  (~39 MB) — fast, less accurate",
         "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-tiny.bin"},
    };

    static final String[] LANGUAGES = {
            "auto", "en", "de", "nl", "fr", "es", "it", "pt", "ru", "zh", "ja", "ko", "ar"
    };
    private static final String[] LANGUAGE_NAMES = {
            "Auto-detect", "English", "German", "Dutch", "French", "Spanish",
            "Italian", "Portuguese", "Russian", "Chinese", "Japanese", "Korean", "Arabic"
    };

    // Views
    private TextView tvFilename, tvStatus, tvTranscript;
    private ProgressBar progressBar;
    private Button btnTranscribe, btnSwitchModel, btnSave, btnMedia;
    private Spinner spinnerLanguage;
    private ScrollView scrollTranscript;

    // State
    private Uri fileUri;
    private Uri folderUri;
    private String filePath;
    private boolean isRecordMode = false;

    // Playback
    private MediaPlayer mediaPlayer;
    private boolean isPlaying = false;

    // Recording — live mode uses LiveRecorder; stop-then-transcribe uses MediaRecorder
    private MediaRecorder mediaRecorder;
    private LiveRecorder liveRecorder;
    private File recordingWavFile;
    private File recordingFile;
    private boolean isRecording = false;
    private boolean recordingDone = false;
    private android.view.View levelContainer, levelBar;
    private final Runnable levelUpdater = this::updateLevel;
    private int liveChunkIndex = 0;
    private final java.util.concurrent.atomic.AtomicInteger pendingChunks = new java.util.concurrent.atomic.AtomicInteger(0);
    private volatile boolean recordingFullyStopped = false;

    // Whisper
    private long whisperContextPtr = 0;
    private String modelPath;
    private Uri modelUri;
    private final StringBuilder transcriptBuilder = new StringBuilder();
    private boolean transcriptSaved = false; // true after saveMarkdown() succeeds
    private long lastSegmentEndCentis = 0;
    private static final long  PARAGRAPH_GAP_CENTIS  = 150;  // 1.5 s silence gap
    private static final float NO_SPEECH_THRESHOLD   = 0.6f; // Whisper confidence it's silence
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor        = Executors.newSingleThreadExecutor();
    private final ExecutorService whisperExecutor = Executors.newSingleThreadExecutor();

    private ActivityResultLauncher<Intent> modelPickerLauncher;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_transcribe);

        // Intercept ALL back navigation — hardware button AND gesture swipe
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                handleBack();
            }
        });

        tvFilename      = findViewById(R.id.tv_filename);
        tvStatus        = findViewById(R.id.tv_status);
        tvTranscript    = findViewById(R.id.tv_transcript);
        progressBar     = findViewById(R.id.progress_bar);
        btnTranscribe   = findViewById(R.id.btn_transcribe);
        btnSwitchModel  = findViewById(R.id.btn_select_model);
        btnSave         = findViewById(R.id.btn_save);
        btnMedia        = findViewById(R.id.btn_media);
        spinnerLanguage = findViewById(R.id.spinner_language);
        scrollTranscript = findViewById(R.id.scroll_transcript);

        ArrayAdapter<String> langAdapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, LANGUAGE_NAMES);
        langAdapter.setDropDownViewResource(R.layout.spinner_dropdown_item);
        spinnerLanguage.setAdapter(langAdapter);

        // Pre-select the default language saved in settings
        String defaultLang = getSharedPreferences(PREFS, MODE_PRIVATE)
                .getString("default_language", "auto");
        for (int i = 0; i < LANGUAGES.length; i++) {
            if (LANGUAGES[i].equals(defaultLang)) { spinnerLanguage.setSelection(i); break; }
        }

        modelPickerLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                        Uri uri = result.getData().getData();
                        if (uri != null) handleModelSelected(uri);
                    }
                });

        String mode = getIntent().getStringExtra(EXTRA_MODE);
        isRecordMode = MODE_RECORD.equals(mode);
        boolean isTextMode = MODE_TEXT.equals(mode);

        // Read folder_uri regardless of mode — needed for saving recordings too
        String folderUriStr = getIntent().getStringExtra("folder_uri");
        if (folderUriStr != null) folderUri = Uri.parse(folderUriStr);

        if (isRecordMode) {
            setupRecordMode();
        } else if (isTextMode) {
            setupTextMode();
        } else {
            setupFileMode();
        }

        // Toolbar with back button
        androidx.appcompat.widget.Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) getSupportActionBar().setDisplayHomeAsUpEnabled(true);

        levelContainer = findViewById(R.id.level_container);
        levelBar       = findViewById(R.id.level_bar);

        btnSwitchModel.setOnClickListener(v -> showModelPicker());
        btnTranscribe.setOnClickListener(v -> startTranscription());
        btnSave.setOnClickListener(v -> saveMarkdown());
        tvFilename.setOnClickListener(v -> promptRename());

        loadSavedModel();
        checkAndOfferModelDownload(); // no-op if model already loaded

        // Pre-load Whisper model immediately in live mode so recording can start right away
        if (isRecordMode && isLiveMode() && whisperContextPtr == 0) {
            executor.submit(this::preloadWhisperModel);
        }

        // Leftover recovery happens in MainActivity.onResume()
    }

    // ── Text-only mode (.md file opened directly) ─────────────────────────────

    private void setupTextMode() {
        Intent intent = getIntent();
        filePath = intent.getStringExtra("file_path");
        String uriStr = intent.getStringExtra("content_uri");
        fileUri = uriStr != null ? Uri.parse(uriStr) : Uri.fromFile(new File(filePath));
        tvFilename.setText(new File(filePath).getName());
        // No audio — hide media button
        btnMedia.setVisibility(View.GONE);
        // No model needed — hide status and transcribe
        btnTranscribe.setVisibility(View.GONE);
        tvStatus.setVisibility(View.GONE);
        progressBar.setVisibility(View.GONE);
        // Load the markdown content directly
        loadMdFileIntoView(fileUri);
    }

    private void loadMdFileIntoView(Uri uri) {
        try (java.io.InputStream is = getContentResolver().openInputStream(uri);
             java.io.BufferedReader br = new java.io.BufferedReader(
                     new java.io.InputStreamReader(is))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line).append('\n');
            String content = sb.toString().trim();
            transcriptBuilder.setLength(0);
            transcriptBuilder.append(content);
            tvTranscript.setText(content);
            transcriptSaved = true;
            btnSave.setEnabled(true);
            styleSaveButton();
        } catch (IOException e) {
            tvTranscript.setText("Could not read file: " + e.getMessage());
        }
    }

    // ── File mode ─────────────────────────────────────────────────────────────

    private void setupFileMode() {
        Intent intent = getIntent();
        filePath = intent.getStringExtra("file_path");
        String uriStr = intent.getStringExtra("content_uri");

        fileUri = uriStr != null ? Uri.parse(uriStr) : Uri.fromFile(new File(filePath));
        // folderUri already read in onCreate before this call

        tvFilename.setText(new File(filePath).getName());
        btnMedia.setText("▶");
        btnMedia.setOnClickListener(v -> togglePlayback());

        // Pre-load any existing .md file with the same base name into the transcript view
        loadExistingMarkdown();

        // Prepare MediaPlayer
        mediaPlayer = new MediaPlayer();
        try {
            mediaPlayer.setDataSource(this, fileUri);
            mediaPlayer.prepareAsync();
            mediaPlayer.setOnPreparedListener(mp -> btnMedia.setEnabled(true));
            mediaPlayer.setOnCompletionListener(mp -> {
                isPlaying = false;
                btnMedia.setText("▶");
            });
            btnMedia.setEnabled(false);
        } catch (IOException e) {
            Log.e(TAG, "MediaPlayer setup failed", e);
            btnMedia.setEnabled(false);
        }
    }

    private void togglePlayback() {
        if (mediaPlayer == null) return;
        if (isPlaying) {
            mediaPlayer.pause();
            btnMedia.setText("▶");
        } else {
            mediaPlayer.start();
            btnMedia.setText("||");
        }
        isPlaying = !isPlaying;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            handleBack();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    // ── Rename ────────────────────────────────────────────────────────────────

    private void promptRename() {
        String current = tvFilename.getText().toString();
        // Strip extension for editing
        String base = current.contains(".") ? current.substring(0, current.lastIndexOf('.')) : current;
        EditText input = new EditText(this);
        input.setText(base);
        input.selectAll();
        new AlertDialog.Builder(this)
                .setTitle("Rename")
                .setView(input)
                .setPositiveButton("Rename", (d, w) -> {
                    String newBase = input.getText().toString().trim();
                    if (!newBase.isEmpty()) {
                        String ext = current.contains(".") ? current.substring(current.lastIndexOf('.')) : "";
                        tvFilename.setText(newBase + ext);
                        // Update filePath base name for saving
                        if (filePath != null) {
                            File orig = new File(filePath);
                            filePath = new File(orig.getParent(), newBase + ext).getAbsolutePath();
                        }
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    // ── Record mode ───────────────────────────────────────────────────────────

    private void setupRecordMode() {
        String takeName = new SimpleDateFormat("'Take' yyMMdd HHmm", java.util.Locale.getDefault())
                .format(new java.util.Date());
        tvFilename.setText(takeName);

        // Restart AudioRecord when headset is plugged/unplugged during recording
        audioManager = (AudioManager) getSystemService(AUDIO_SERVICE);
        audioDeviceCallback = new AudioDeviceCallback() {
            @Override
            public void onAudioDevicesAdded(AudioDeviceInfo[] addedDevices) {
                if (isRecording && liveRecorder != null && hasHeadsetMic(addedDevices)) {
                    Log.d(TAG, "Headset mic connected — restarting AudioRecord");
                    liveRecorder.restartAudioRecord();
                }
            }
            @Override
            public void onAudioDevicesRemoved(AudioDeviceInfo[] removedDevices) {
                if (isRecording && liveRecorder != null && hasHeadsetMic(removedDevices)) {
                    Log.d(TAG, "Headset mic removed — restarting AudioRecord");
                    liveRecorder.restartAudioRecord();
                }
            }
        };
        audioManager.registerAudioDeviceCallback(audioDeviceCallback, mainHandler);

        // ● start → ■ tap once = pause → ● tap = resume → ■ long-press or Transcribe btn = stop
        btnMedia.setText("●");
        btnMedia.setOnClickListener(v -> {
            if (!hasRecordPermission()) { requestRecordPermission(); return; }
            if (!isRecording && !recordingDone) {
                if (isLiveMode()) startLiveRecording(); else startSimpleRecording();
            } else if (isRecording) {
                if (liveRecorder != null) {
                    if (liveRecorder.isPaused()) {
                        liveRecorder.resume();
                        btnMedia.setText("■");
                        tvStatus.setText("Recording + transcribing...");
                    } else {
                        liveRecorder.pause();
                        btnMedia.setText("●");
                        tvStatus.setText("Paused — tap ● to resume");
                    }
                } else {
                    stopRecording(); // simple mode: ■ = stop
                }
            }
        });
        btnMedia.setOnLongClickListener(v -> {
            if (isRecording) { stopRecording(); return true; }
            return false;
        });
        btnMedia.setEnabled(!isLiveMode()); // disabled until model preloaded in live mode

        // Transcribe button: hidden during recording, available for re-transcription after
        btnTranscribe.setText("Transcribe");
        btnTranscribe.setEnabled(false);
        btnTranscribe.setOnClickListener(v -> startTranscription());
    }

    private static boolean hasHeadsetMic(AudioDeviceInfo[] devices) {
        for (AudioDeviceInfo d : devices) {
            int t = d.getType();
            if (t == AudioDeviceInfo.TYPE_WIRED_HEADSET
             || t == AudioDeviceInfo.TYPE_USB_HEADSET
             || t == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
             || t == AudioDeviceInfo.TYPE_BLE_HEADSET) return true;
        }
        return false;
    }

    private boolean isLiveMode() {
        return getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean("live_transcribe", true);
    }

    /** Simple recording: MediaRecorder → M4A, then user taps Transcribe manually. */
    private void startSimpleRecording() {
        String displayName = tvFilename.getText().toString().trim();
        String safeName = displayName.replaceAll("[^a-zA-Z0-9 _-]", "").trim();
        if (safeName.isEmpty()) safeName = "recording";

        recordingFile = new File(getFilesDir(), safeName + ".m4a");
        if (recordingFile.exists()) recordingFile.delete();

        mediaRecorder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                ? new MediaRecorder(this) : new MediaRecorder();
        mediaRecorder.setAudioSource(MediaRecorder.AudioSource.DEFAULT);
        mediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
        mediaRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
        mediaRecorder.setAudioSamplingRate(44100);
        mediaRecorder.setAudioEncodingBitRate(128_000);
        mediaRecorder.setOutputFile(recordingFile.getAbsolutePath());
        try {
            mediaRecorder.prepare();
            mediaRecorder.start();
            isRecording = true;
            btnMedia.setText("||");  // pause/resume
            btnMedia.setEnabled(true);
            btnMedia.setOnClickListener(v -> toggleSimplePause());
            btnTranscribe.setText("Stop");
            btnTranscribe.setEnabled(true);
            btnTranscribe.setOnClickListener(v -> stopRecording());
            tvStatus.setText("Recording — tap || to pause");
            levelContainer.setVisibility(android.view.View.VISIBLE);
            mainHandler.post(levelUpdater);
        } catch (IOException e) {
            Log.e(TAG, "Simple recording failed", e);
            Toast.makeText(this, "Recording failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void startLiveRecording() {
        if (whisperContextPtr == 0) {
            // Load model first (quick check)
            if (modelPath != null) {
                whisperContextPtr = WhisperLib.initContext(modelPath);
            } else if (modelUri != null) {
                try (InputStream is = getContentResolver().openInputStream(modelUri)) {
                    whisperContextPtr = WhisperLib.initContextFromInputStream(is);
                } catch (IOException e) {
                    Log.e(TAG, "Model load failed", e);
                }
            }
            if (whisperContextPtr == 0) {
                Toast.makeText(this, "Load a model first", Toast.LENGTH_SHORT).show();
                return;
            }
        }

        String displayName = tvFilename.getText().toString().trim();
        String safeName = displayName.replaceAll("[^a-zA-Z0-9 _-]", "").trim();
        if (safeName.isEmpty()) safeName = "recording";

        // Write directly to .wav — syncInternalRecordings() only runs in onResume()
        // which fires after back is pressed, so the file is already closed and complete.
        recordingWavFile = new File(getFilesDir(), safeName + ".wav");
        liveChunkIndex   = 0;
        pendingChunks.set(0);
        recordingFullyStopped = false;
        WhisperLib.resetAbort();
        transcriptBuilder.setLength(0);
        transcriptSaved = false;
        lastSegmentEndCentis = 0;
        tvTranscript.setText("");

        int chunkSecs = getSharedPreferences(PREFS, MODE_PRIVATE)
                .getInt("chunk_secs", LiveRecorder.DEFAULT_CHUNK_SECS);
        liveRecorder = new LiveRecorder(chunkSecs);
        try {
            String language = LANGUAGES[spinnerLanguage.getSelectedItemPosition()];
            liveRecorder.start(recordingWavFile, new LiveRecorder.Callback() {
                @Override
                public void onChunkReady(float[] samples, int idx) {
                    pendingChunks.incrementAndGet();
                    whisperExecutor.submit(() -> transcribeChunk(samples, language));
                }
                @Override
                public void onAmplitude(int amp) {
                    float f = Math.min(1f, amp / 10000f);
                    mainHandler.post(() -> {
                        int w = (int)(levelContainer.getWidth() * f);
                        android.view.ViewGroup.LayoutParams lp = levelBar.getLayoutParams();
                        lp.width = w;
                        levelBar.setLayoutParams(lp);
                    });
                }
            });
            isRecording = true;
            btnMedia.setText("||");  // pause
            btnTranscribe.setText("Stop");
            btnTranscribe.setEnabled(true);
            btnTranscribe.setOnClickListener(v -> stopRecording());
            tvStatus.setText("Recording + transcribing live...");
            levelContainer.setVisibility(android.view.View.VISIBLE);
        } catch (IOException e) {
            Log.e(TAG, "LiveRecorder start failed", e);
            Toast.makeText(this, "Recording failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void transcribeChunk(float[] samples, String language) {
        if (whisperContextPtr == 0) return;
        WhisperLib.fullTranscribe(whisperContextPtr, WhisperLib.preferredThreadCount(),
                samples, language, new WhisperCallback() {
            @Override public void onNewSegment(long s, long e, String text, float noSpeechProb) {
                String trimmed = text.trim();
                if (trimmed.isEmpty()) return;
                boolean isParagraphBreak = noSpeechProb > NO_SPEECH_THRESHOLD
                        || (lastSegmentEndCentis > 0
                                && s - lastSegmentEndCentis > PARAGRAPH_GAP_CENTIS);
                String chunk = (transcriptBuilder.length() == 0) ? trimmed
                        : (isParagraphBreak ? "\n\n" + trimmed : " " + trimmed);
                lastSegmentEndCentis = e;
                transcriptBuilder.append(chunk);
                mainHandler.post(() -> {
                    tvTranscript.setText(transcriptBuilder.toString());
                    scrollTranscript.post(() -> scrollTranscript.fullScroll(ScrollView.FOCUS_DOWN));
                });
            }
            @Override public void onProgress(int p) {
                mainHandler.post(() -> tvStatus.setText(
                        liveRecorder != null && liveRecorder.isPaused()
                        ? "Transcribing remaining... " + p + "%"
                        : "Recording + transcribing... " + p + "%"));
            }
            @Override public void onComplete() {
                int remaining = pendingChunks.decrementAndGet();
                if (remaining == 0 && recordingFullyStopped) {
                    activateSave();
                }
            }
        });
    }

    private void activateSave() {
        mainHandler.post(() -> {
            tvStatus.setText("Done — tap Save");
            progressBar.setVisibility(View.GONE);
            btnSave.setEnabled(true);
            styleSaveButton();
            btnMedia.setEnabled(true);
            btnTranscribe.setVisibility(View.GONE);
        });
    }

    private void updateLevel() {
        if (!isRecording || mediaRecorder == null) return;
        int amp = mediaRecorder.getMaxAmplitude();
        float fraction = Math.min(1f, amp / 10000f);
        levelContainer.post(() -> {
            int width = (int)(levelContainer.getWidth() * fraction);
            android.view.ViewGroup.LayoutParams lp = levelBar.getLayoutParams();
            lp.width = width;
            levelBar.setLayoutParams(lp);
        });
        mainHandler.postDelayed(levelUpdater, 100);
    }

    private void stopRecording() {
        levelContainer.setVisibility(android.view.View.GONE);
        isRecording = false;
        recordingDone = true;

        if (liveRecorder != null) {
            encodingInProgress = true;
            btnMedia.setEnabled(false);
            mainHandler.post(() -> tvStatus.setText("Saving audio..."));

            executor.submit(() -> {
                try {
                    // 1. Stop AudioRecord — emits final chunk to whisperExecutor
                    File wav = liveRecorder.stop(recordingWavFile); // finalizes WAV header
                    liveRecorder = null;

                    // Encode PCM WAV → M4A
                    String baseName = recordingWavFile.getName().replace(".wav", "");
                    File m4a = new File(getCacheDir(), baseName + ".m4a");
                    File encoded = encodePcmWavToM4a(wav, m4a);

                    // 3. Save to folder — prefer M4A, fall back to WAV
                    String destName = (encoded != null) ? baseName + ".m4a" : baseName + ".wav";
                    File toSave    = (encoded != null) ? encoded : wav;
                    Uri savedUri   = copyRecordingToFolder(toSave, destName);
                    fileUri  = savedUri != null ? savedUri : Uri.fromFile(toSave);
                    filePath = toSave.getAbsolutePath();

                    // 4. Clean up temp files
                    wav.delete();
                    if (encoded != null) encoded.delete();

                    mainHandler.post(this::setupPlayback);
                } catch (Exception e) {
                    Log.e(TAG, "Stop/save error", e);
                } finally {
                    cancelNotification();
                    encodingInProgress = false;
                }

                // Signal that recording is fully stopped.
                // activateSave() fires from onComplete() of the last chunk via pendingChunks counter.
                recordingFullyStopped = true;
                if (pendingChunks.get() == 0) {
                    // All chunks already finished before stop completed — activate now
                    activateSave();
                    return;  // skip old sentinel
                }

                // Old sentinel kept as fallback (should rarely be needed now)
                try {
                    whisperExecutor.submit(() -> mainHandler.post(() -> {
                        encodingInProgress = false;
                        if (pendingFinish) {
                            finish();
                            return;
                        }
                        // activateSave() should have already run via onComplete counter
                        // Live mode: transcription already done, hide Transcribe button
                        btnTranscribe.setVisibility(View.GONE);
                    }));
                } catch (Exception ignored) {};
            });
            return;
        }
        // Simple mode: stop MediaRecorder
        if (mediaRecorder != null) {
            try { mediaRecorder.stop(); } catch (Exception ignored) {}
            mediaRecorder.release();
            mediaRecorder = null;
        }
        // Copy M4A to SAF folder synchronously so it appears in the list immediately
        if (recordingFile != null && recordingFile.exists()) {
            Uri target = folderUri != null ? folderUri : parseSavedFolderUri();
            Uri savedUri = target != null ? copyToFolder(target, recordingFile, recordingFile.getName()) : null;
            if (savedUri != null) {
                fileUri  = savedUri;
                filePath = recordingFile.getAbsolutePath();
                recordingFile.delete(); // remove from getFilesDir now it's in SAF
            } else {
                fileUri  = Uri.fromFile(recordingFile);
                filePath = recordingFile.getAbsolutePath();
            }
            tvStatus.setText("Saved: " + recordingFile.getName());
        }
        // Reset buttons: ▶ for playback, Transcribe for manual transcription
        btnTranscribe.setText("Transcribe");
        btnTranscribe.setOnClickListener(v -> startTranscription());
        if (modelPath != null || modelUri != null) btnTranscribe.setEnabled(true);
        setupPlayback();
        if (modelPath != null || modelUri != null) btnTranscribe.setEnabled(true);
    }

    private void toggleSimplePause() {
        if (mediaRecorder == null) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            if (isRecording) {
                try { mediaRecorder.pause(); isRecording = false;
                    btnMedia.setText("●"); tvStatus.setText("Paused"); } catch (Exception ignored) {}
            } else {
                try { mediaRecorder.resume(); isRecording = true;
                    btnMedia.setText("||"); tvStatus.setText("Recording..."); } catch (Exception ignored) {}
            }
        }
    }

    /** Copy a recording to the SAF folder synchronously, then delete the source. */
    private void copyRecordingSync(File src) {
        if (src == null || !src.exists()) return;
        Uri target = folderUri != null ? folderUri : parseSavedFolderUri();
        if (target == null) return; // no folder selected — file stays in getFilesDir()
        Uri saved = copyToFolder(target, src, src.getName());
        if (saved != null) src.delete();
    }

    private Uri parseSavedFolderUri() {
        String s = getSharedPreferences(PREFS, MODE_PRIVATE).getString("folder_uri", null);
        return s != null ? Uri.parse(s) : null;
    }

    private void setupPlayback() {
        btnMedia.setText("▶");
        btnMedia.setOnClickListener(v -> togglePlayback());
        btnMedia.setEnabled(false);
        if (mediaPlayer != null) { try { mediaPlayer.release(); } catch (Exception ignored) {} }
        mediaPlayer = new MediaPlayer();
        try {
            mediaPlayer.setDataSource(this, fileUri);
            mediaPlayer.prepareAsync();
            mediaPlayer.setOnPreparedListener(mp -> btnMedia.setEnabled(true));
            mediaPlayer.setOnCompletionListener(mp -> { isPlaying = false; btnMedia.setText("▶"); });
        } catch (IOException e) {
            Log.e(TAG, "Playback setup failed", e);
        }
    }

    /** Encode WAV PCM to M4A using MediaCodec + MediaMuxer. Returns null on failure. */
    /**
     * Encodes a raw PCM WAV (16 kHz, mono, 16-bit) directly to AAC/M4A
     * without a decoder step — the WAV is already uncompressed PCM.
     */
    private File encodePcmWavToM4a(File wavFile, File m4aFile) {
        android.media.MediaCodec codec = null;
        android.media.MediaMuxer muxer = null;
        java.io.RandomAccessFile raf    = null;
        try {
            raf = new java.io.RandomAccessFile(wavFile, "r");
            raf.seek(44); // skip WAV header — everything after is raw PCM

            android.media.MediaFormat fmt = android.media.MediaFormat.createAudioFormat(
                    "audio/mp4a-latm", LiveRecorder.SAMPLE_RATE, 1);
            fmt.setInteger(android.media.MediaFormat.KEY_BIT_RATE, 128_000);
            fmt.setInteger(android.media.MediaFormat.KEY_AAC_PROFILE,
                    android.media.MediaCodecInfo.CodecProfileLevel.AACObjectLC);
            fmt.setInteger(android.media.MediaFormat.KEY_MAX_INPUT_SIZE, 16_384);

            codec = android.media.MediaCodec.createEncoderByType("audio/mp4a-latm");
            codec.configure(fmt, null, null, android.media.MediaCodec.CONFIGURE_FLAG_ENCODE);
            codec.start();

            muxer = new android.media.MediaMuxer(
                    m4aFile.getAbsolutePath(),
                    android.media.MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);

            android.media.MediaCodec.BufferInfo info = new android.media.MediaCodec.BufferInfo();
            int  muxTrack     = -1;
            boolean muxStarted = false;
            boolean inputDone  = false;
            boolean outputDone = false;
            long presentationUs = 0;
            final int FRAME = 1024;            // AAC frame size in samples
            byte[] pcm = new byte[FRAME * 2]; // 16-bit = 2 bytes/sample

            while (!outputDone) {
                if (!inputDone) {
                    int inIdx = codec.dequeueInputBuffer(10_000);
                    if (inIdx >= 0) {
                        java.nio.ByteBuffer inBuf = codec.getInputBuffer(inIdx);
                        inBuf.clear();
                        int n = raf.read(pcm);
                        if (n <= 0) {
                            codec.queueInputBuffer(inIdx, 0, 0, presentationUs,
                                    android.media.MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputDone = true;
                        } else {
                            inBuf.put(pcm, 0, n);
                            codec.queueInputBuffer(inIdx, 0, n, presentationUs, 0);
                            presentationUs += (long)(n / 2) * 1_000_000L / LiveRecorder.SAMPLE_RATE;
                        }
                    }
                }
                int outIdx = codec.dequeueOutputBuffer(info, 10_000);
                if (outIdx == android.media.MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    muxTrack = muxer.addTrack(codec.getOutputFormat());
                    muxer.start();
                    muxStarted = true;
                } else if (outIdx >= 0 && muxStarted) {
                    java.nio.ByteBuffer outBuf = codec.getOutputBuffer(outIdx);
                    if ((info.flags & android.media.MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0)
                        muxer.writeSampleData(muxTrack, outBuf, info);
                    codec.releaseOutputBuffer(outIdx, false);
                    if ((info.flags & android.media.MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0)
                        outputDone = true;
                }
            }
            return m4aFile;
        } catch (Exception e) {
            Log.e(TAG, "PCM WAV → M4A failed", e);
            if (m4aFile.exists()) m4aFile.delete();
            return null;
        } finally {
            try { if (raf   != null) raf.close();     } catch (Exception ignored) {}
            try { if (codec != null) { codec.stop(); codec.release(); } } catch (Exception ignored) {}
            try { if (muxer != null) { muxer.stop(); muxer.release(); } } catch (Exception ignored) {}
        }
    }

    /** Copy to a specific folder URI (allows caller to supply URI independently of this.folderUri). */
    private static String mimeFor(String name) {
        // Use generic octet-stream for audio — Samsung SAF providers reject specific audio MIMEs
        if (name.endsWith(".md")) return "text/markdown";
        return "application/octet-stream";
    }

    private Uri copyToFolder(Uri targetFolderUri, File src, String destName) {
        if (targetFolderUri == null) return null;
        try {
            DocumentFile dir = DocumentFile.fromTreeUri(this, targetFolderUri);
            if (dir == null) return null;
            DocumentFile existing = dir.findFile(destName);
            if (existing != null) existing.delete();
            DocumentFile dest = dir.createFile(mimeFor(destName), destName);
            if (dest == null) return null;
            try (java.io.FileInputStream in = new java.io.FileInputStream(src);
                 OutputStream out = getContentResolver().openOutputStream(dest.getUri())) {
                byte[] buf = new byte[64 * 1024]; int read;
                while ((read = in.read(buf)) != -1) out.write(buf, 0, read);
            }
            return dest.getUri();
        } catch (IOException e) {
            Log.e(TAG, "copyToFolder failed for " + destName, e);
            return null;
        }
    }

    private Uri copyRecordingToFolder(File src, String destName) {
        if (folderUri == null) return null;
        try {
            DocumentFile dir = DocumentFile.fromTreeUri(this, folderUri);
            if (dir == null) return null;
            DocumentFile existing = dir.findFile(destName);
            if (existing != null) existing.delete();
            DocumentFile dest = dir.createFile(mimeFor(destName), destName);
            if (dest == null) return null;
            try (java.io.FileInputStream in = new java.io.FileInputStream(src);
                 OutputStream out = getContentResolver().openOutputStream(dest.getUri())) {
                byte[] buf = new byte[64 * 1024];
                int read;
                while ((read = in.read(buf)) != -1) out.write(buf, 0, read);
            }
            Log.d(TAG, "Recording copied to folder: " + destName);
            return dest.getUri();
        } catch (IOException e) {
            Log.e(TAG, "Failed to copy recording to folder", e);
            return null;
        }
    }

    // ── Permissions ───────────────────────────────────────────────────────────

    private boolean hasRecordPermission() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void requestRecordPermission() {
        ActivityCompat.requestPermissions(this,
                new String[]{Manifest.permission.RECORD_AUDIO}, 200);
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        super.onRequestPermissionsResult(code, perms, results);
        if (code == 200 && results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) {
            if (isLiveMode()) startLiveRecording(); else startSimpleRecording();
        } else {
            Toast.makeText(this, "Microphone permission required", Toast.LENGTH_SHORT).show();
        }
    }

    // ── Model management ──────────────────────────────────────────────────────

    private void loadSavedModel() {
        String saved    = getSharedPreferences(PREFS, MODE_PRIVATE).getString(MODEL_PATH_KEY, null);
        String savedUri = getSharedPreferences(PREFS, MODE_PRIVATE).getString(MODEL_URI_KEY, null);
        if (saved != null && new File(saved).exists()) {
            modelPath = saved;
            tvStatus.setText("Model: " + new File(saved).getName());
            if (!isRecordMode || recordingDone) btnTranscribe.setEnabled(true);
        } else if (savedUri != null) {
            modelUri = Uri.parse(savedUri);
            tvStatus.setText("Model ready (stream)");
            if (!isRecordMode || recordingDone) btnTranscribe.setEnabled(true);
        }
    }

    private void checkAndOfferModelDownload() {
        if (modelPath != null || modelUri != null) return;

        // Auto-use any already-downloaded valid model
        for (String[] m : MODELS) {
            File f = new File(getFilesDir(), m[0]);
            if (f.exists() && f.length() > 1_000_000L && isValidGgmlFile(f)) {
                modelPath = f.getAbsolutePath();
                saveModelPath(modelPath);
                tvStatus.setText("Model: " + m[0]);
                if (!isRecordMode || recordingDone) btnTranscribe.setEnabled(true);
                return;
            }
        }
        showModelPicker();
    }

    private void showModelPicker() {
        String[] options = {
            MODELS[0][1] + (new File(getFilesDir(), MODELS[0][0]).exists()
                    && new File(getFilesDir(), MODELS[0][0]).length() > 1_000_000L ? " (ready)" : ""),
            MODELS[1][1] + (new File(getFilesDir(), MODELS[1][0]).exists()
                    && new File(getFilesDir(), MODELS[1][0]).length() > 1_000_000L ? " (ready)" : ""),
            "Select .bin file from storage"
        };
        new AlertDialog.Builder(this)
                .setTitle("Whisper Model")
                .setItems(options, (d, which) -> {
                    if (which < MODELS.length) {
                        File f = new File(getFilesDir(), MODELS[which][0]);
                        if (f.exists() && f.length() > 1_000_000L) {
                            modelPath = f.getAbsolutePath();
                            saveModelPath(modelPath);
                            tvStatus.setText("Model: " + MODELS[which][0]);
                            if (!isRecordMode || recordingDone) btnTranscribe.setEnabled(true);
                        } else {
                            downloadModel(which);
                        }
                    } else {
                        pickModelFile();
                    }
                }).show();
    }

    private void downloadModel(int modelIndex) {
        String filename = MODELS[modelIndex][0];
        String url      = MODELS[modelIndex][2];
        File outFile    = new File(getFilesDir(), filename);

        // If already downloaded and valid GGML binary, just select it
        if (outFile.exists() && outFile.length() > 1_000_000L && isValidGgmlFile(outFile)) {
            modelPath = outFile.getAbsolutePath();
            modelUri  = null;
            saveModelPath(modelPath);
            tvStatus.setText("Model: " + filename);
            if (!isRecordMode || recordingDone) btnTranscribe.setEnabled(true);
            return;
        }
        // Delete corrupt/partial file and re-download
        if (outFile.exists()) outFile.delete();

        tvStatus.setText("Downloading " + filename + "...");
        progressBar.setVisibility(View.VISIBLE);
        progressBar.setIndeterminate(false);
        progressBar.setProgress(0);
        btnTranscribe.setEnabled(false);
        btnSwitchModel.setEnabled(false);

        executor.submit(() -> {
            try {
                HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
                conn.setInstanceFollowRedirects(true);
                conn.setRequestProperty("User-Agent", "Transcribe-Android/1.0");
                conn.setConnectTimeout(30_000);
                conn.setReadTimeout(120_000);
                conn.connect();

                String ct = conn.getContentType();
                if (ct != null && ct.startsWith("text/")) {
                    throw new IOException("Got web page instead of model binary. Download manually.");
                }

                long total = conn.getContentLength();
                try (InputStream in = conn.getInputStream();
                     java.io.FileOutputStream fos = new java.io.FileOutputStream(outFile)) {
                    byte[] buf = new byte[64 * 1024];
                    int read; long downloaded = 0;
                    while ((read = in.read(buf)) != -1) {
                        fos.write(buf, 0, read);
                        downloaded += read;
                        if (total > 0) {
                            int pct = (int) (100L * downloaded / total);
                            mainHandler.post(() -> {
                                progressBar.setProgress(pct);
                                tvStatus.setText("Downloading " + filename + "... " + pct + "%");
                            });
                        }
                    }
                }

                if (outFile.length() < 1_000_000L) {
                    outFile.delete();
                    throw new IOException("File too small — download failed or redirected.");
                }

                mainHandler.post(() -> {
                    modelPath = outFile.getAbsolutePath();
                    saveModelPath(modelPath);
                    progressBar.setVisibility(View.GONE);
                    tvStatus.setText("Model: " + filename);
                    btnSwitchModel.setEnabled(true);
                    if (!isRecordMode || recordingDone) btnTranscribe.setEnabled(true);
                });
            } catch (Exception e) {
                outFile.delete();
                mainHandler.post(() -> {
                    progressBar.setVisibility(View.GONE);
                    tvStatus.setText("Download failed: " + e.getMessage());
                    btnSwitchModel.setEnabled(true);
                    Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private void pickModelFile() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        modelPickerLauncher.launch(intent);
    }

    private void handleModelSelected(Uri uri) {
        try { getContentResolver().takePersistableUriPermission(uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION); } catch (Exception ignored) {}
        DocumentFile doc = DocumentFile.fromSingleUri(this, uri);
        modelUri  = uri;
        modelPath = null;
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putString(MODEL_URI_KEY, uri.toString()).remove(MODEL_PATH_KEY).apply();
        tvStatus.setText("Model: " + (doc != null ? doc.getName() : "custom"));
        if (!isRecordMode || recordingDone) btnTranscribe.setEnabled(true);
    }

    private void saveModelPath(String path) {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putString(MODEL_PATH_KEY, path).remove(MODEL_URI_KEY).apply();
    }

    // ── Transcription ─────────────────────────────────────────────────────────

    private void startTranscription() {
        pauseMediaIfPlaying();
        btnTranscribe.setEnabled(false);
        btnSave.setEnabled(false);
        transcriptBuilder.setLength(0);
        lastSegmentEndCentis = 0;
        tvTranscript.setText("");
        tvStatus.setText("Converting audio...");
        progressBar.setVisibility(View.VISIBLE);
        progressBar.setIndeterminate(true);

        String language = LANGUAGES[spinnerLanguage.getSelectedItemPosition()];

        executor.submit(() -> {
            try {
                float[] samples = AudioConverter.convertToSamples(this, fileUri,
                        p -> mainHandler.post(() -> {
                            progressBar.setIndeterminate(false);
                            progressBar.setProgress((int)(p * 40));
                        }));

                if (samples == null || samples.length == 0) {
                    mainHandler.post(() -> {
                        tvStatus.setText("Failed to read audio");
                        progressBar.setVisibility(View.GONE);
                        btnTranscribe.setEnabled(true);
                    });
                    return;
                }

                mainHandler.post(() -> {
                    tvStatus.setText("Loading model...");
                    progressBar.setIndeterminate(true);
                });

                if (whisperContextPtr != 0) {
                    WhisperLib.freeContext(whisperContextPtr);
                    whisperContextPtr = 0;
                }
                if (modelPath != null) {
                    whisperContextPtr = WhisperLib.initContext(modelPath);
                } else if (modelUri != null) {
                    try (InputStream is = getContentResolver().openInputStream(modelUri)) {
                        whisperContextPtr = WhisperLib.initContextFromInputStream(is);
                    }
                }

                if (whisperContextPtr == 0) {
                    mainHandler.post(() -> {
                        progressBar.setVisibility(View.GONE);
                        btnTranscribe.setEnabled(true);
                        // Model file is corrupt — delete it and offer re-download
                        if (modelPath != null) {
                            File bad = new File(modelPath);
                            if (bad.exists()) bad.delete();
                            modelPath = null;
                            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                                    .remove(MODEL_PATH_KEY).apply();
                        }
                        tvStatus.setText("Model corrupt or wrong format");
                        new AlertDialog.Builder(this)
                                .setTitle("Model failed to load")
                                .setMessage("The model file is corrupt or incompatible. Download a fresh copy?")
                                .setPositiveButton("Download Base (141 MB)", (d, w) -> downloadModel(0))
                                .setNegativeButton("Cancel", null)
                                .show();
                    });
                    return;
                }

                mainHandler.post(() -> {
                    tvStatus.setText("Transcribing...");
                    progressBar.setIndeterminate(false);
                    progressBar.setProgress(40);
                });

                int threads = WhisperLib.preferredThreadCount();
                WhisperLib.fullTranscribe(whisperContextPtr, threads, samples, language,
                        new WhisperCallback() {
                            @Override
                            public void onNewSegment(long startCentis, long endCentis,
                                    String text, float noSpeechProb) {
                                String trimmed = text.trim();
                                if (trimmed.isEmpty()) return;

                                boolean isParagraphBreak = noSpeechProb > NO_SPEECH_THRESHOLD
                                        || (lastSegmentEndCentis > 0
                                                && startCentis - lastSegmentEndCentis > PARAGRAPH_GAP_CENTIS);
                                String chunk;
                                if (transcriptBuilder.length() == 0) {
                                    chunk = trimmed;
                                } else if (isParagraphBreak) {
                                    chunk = "\n\n" + trimmed;
                                } else {
                                    chunk = " " + trimmed;
                                }
                                lastSegmentEndCentis = endCentis;
                                transcriptBuilder.append(chunk);

                                mainHandler.post(() -> {
                                    tvTranscript.setText(transcriptBuilder.toString());
                                    scrollTranscript.post(() ->
                                            scrollTranscript.fullScroll(ScrollView.FOCUS_DOWN));
                                });
                            }

                            @Override
                            public void onProgress(int progress) {
                                mainHandler.post(() -> {
                                    progressBar.setProgress(40 + (int)(progress * 0.6));
                                    tvStatus.setText("Transcribing... " + progress + "%");
                                });
                            }

                            @Override
                            public void onComplete() {
                                mainHandler.post(() -> {
                                    progressBar.setProgress(100);
                                    progressBar.setVisibility(View.GONE);
                                    tvStatus.setText("Done — tap Save to write .md file");
                                    btnTranscribe.setEnabled(true);
                                    // Activate Save button: full red fill, white text
                                    btnSave.setEnabled(true);
                                    styleSaveButton();
                                });
                            }
                        });

            } catch (Throwable e) {
                Log.e(TAG, "Transcription error", e);
                mainHandler.post(() -> {
                    tvStatus.setText("Error: " + e.getClass().getSimpleName() + ": " + e.getMessage());
                    progressBar.setVisibility(View.GONE);
                    btnTranscribe.setEnabled(true);
                });
            }
        });
    }

    private void styleSaveButton() {
        com.google.android.material.button.MaterialButton mb =
                (com.google.android.material.button.MaterialButton) btnSave;
        mb.setBackgroundTintList(
                android.content.res.ColorStateList.valueOf(0xFFCF1A37));
        mb.setStrokeColor(
                android.content.res.ColorStateList.valueOf(0xFFCF1A37));
        mb.setStrokeWidth(0);
        mb.setTextColor(0xFFFFFFFF);
    }

    private static final int NOTIF_ID = 1;
    private static final String NOTIF_CHANNEL = "voicenotes_encode";

    private void showNotification(String title, String text) {
        android.app.NotificationManager nm =
                (android.app.NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            android.app.NotificationChannel ch = new android.app.NotificationChannel(
                    NOTIF_CHANNEL, "Encoding", android.app.NotificationManager.IMPORTANCE_LOW);
            nm.createNotificationChannel(ch);
        }
        android.app.Notification notif = new androidx.core.app.NotificationCompat.Builder(this, NOTIF_CHANNEL)
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle(title)
                .setContentText(text)
                .setOngoing(true)
                .build();
        nm.notify(NOTIF_ID, notif);
    }

    private void cancelNotification() {
        ((android.app.NotificationManager) getSystemService(NOTIFICATION_SERVICE)).cancel(NOTIF_ID);
    }

    private boolean encodingInProgress = false;
    private AudioManager audioManager;
    private AudioDeviceCallback audioDeviceCallback;
    private boolean pendingFinish      = false;

    /** Called for ALL back navigation: hardware button, gesture swipe, toolbar ← */
    private void handleBack() {
        // Stop any active recorder before leaving — checks the object, not isRecording
        // (isRecording is false when paused, but recorder is still active)
        if (liveRecorder != null) {
            isRecording = false;
            final LiveRecorder lr = liveRecorder;
            liveRecorder = null;
            try { lr.stop(recordingWavFile); }
            catch (Exception e) { Log.e(TAG, "LiveRecorder stop on back", e); }
            copyRecordingSync(recordingWavFile);
            finish();
            return;
        }
        if (mediaRecorder != null) {
            isRecording = false;
            try { mediaRecorder.stop(); } catch (Exception ignored) {}
            mediaRecorder.release();
            mediaRecorder = null;
            copyRecordingSync(recordingFile);
            finish();
            return;
        }

        // If there's an unsaved transcript, ask before leaving
        boolean hasUnsaved = transcriptBuilder.length() > 0 && !transcriptSaved;
        if (hasUnsaved) {
            new android.app.AlertDialog.Builder(this)
                    .setTitle("Unsaved transcription")
                    .setMessage("Save the transcription before leaving?")
                    .setPositiveButton("Save", (d, w) -> { saveMarkdown(); finish(); })
                    .setNegativeButton("Discard", (d, w) -> finish())
                    .setNeutralButton("Cancel", null)
                    .show();
            return;
        }

        finish();
    }

    private String readMarkdownBody(java.io.BufferedReader br) throws IOException {
        StringBuilder sb = new StringBuilder();
        String line; int headerLines = 0;
        while ((line = br.readLine()) != null) {
            if (headerLines < 4) { headerLines++; continue; }
            sb.append(line).append('\n');
        }
        return sb.toString().trim();
    }

    private void applyLoadedTranscript(String content) {
        if (content.isEmpty()) return;
        transcriptBuilder.setLength(0);
        transcriptBuilder.append(content);
        tvTranscript.setText(content);
        transcriptSaved = true;
        tvStatus.setText("Loaded previous transcription — re-transcribe to update");
        btnSave.setEnabled(true);
        styleSaveButton();
    }

    private void loadExistingMarkdown() {
        if (filePath == null) return;
        String base = filePath.contains(".")
                ? filePath.substring(0, filePath.lastIndexOf('.')) : filePath;
        String mdName = base.substring(base.lastIndexOf('/') + 1) + ".md";

        // Try SAF folder first
        if (folderUri != null) {
            DocumentFile dir = DocumentFile.fromTreeUri(this, folderUri);
            if (dir != null) {
                DocumentFile md = dir.findFile(mdName);
                if (md != null) {
                    try (java.io.InputStream is = getContentResolver().openInputStream(md.getUri());
                         java.io.BufferedReader br = new java.io.BufferedReader(
                                 new java.io.InputStreamReader(is))) {
                        applyLoadedTranscript(readMarkdownBody(br));
                    } catch (IOException ignored) {}
                    return;
                }
            }
        }

        // Fallback: same directory as the file
        File mdFile = new File(new File(filePath).getParent(), mdName);
        if (mdFile.exists()) {
            try (java.io.BufferedReader br = new java.io.BufferedReader(
                    new java.io.FileReader(mdFile))) {
                applyLoadedTranscript(readMarkdownBody(br));
            } catch (IOException ignored) {}
        }
    }

    private void checkForLeftoverRecording() {
        // Look for any .wav temp file left by a previous crash
        File[] wavFiles = getCacheDir().listFiles(
                f -> f.getName().endsWith(".wav") && f.length() > 44); // > header only
        if (wavFiles == null || wavFiles.length == 0) return;
        File leftover = wavFiles[0]; // take the most recent
        new android.app.AlertDialog.Builder(this)
                .setTitle("Recover previous recording?")
                .setMessage("Found an unsaved recording: " + leftover.getName()
                        + " (" + (leftover.length() / 1024) + " KB)\n\nSave it to your folder?")
                .setPositiveButton("Save", (d, w) -> {
                    encodingInProgress = true;
                    executor.submit(() -> {
                        Uri uri = copyRecordingToFolder(leftover, leftover.getName());
                        encodingInProgress = false;
                        leftover.delete();
                        mainHandler.post(() -> Toast.makeText(this,
                                uri != null ? "Recovered: " + leftover.getName() : "Recovery failed",
                                Toast.LENGTH_LONG).show());
                    });
                })
                .setNegativeButton("Discard", (d, w) -> leftover.delete())
                .show();
    }

    private void preloadWhisperModel() {
        if (whisperContextPtr != 0 || (modelPath == null && modelUri == null)) return;
        mainHandler.post(() -> tvStatus.setText("Loading model..."));
        long ptr = 0;
        try {
            if (modelPath != null) {
                ptr = WhisperLib.initContext(modelPath);
            } else {
                try (InputStream is = getContentResolver().openInputStream(modelUri)) {
                    ptr = WhisperLib.initContextFromInputStream(is);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Model preload failed", e);
        }
        final long finalPtr = ptr;
        mainHandler.post(() -> {
            if (finalPtr != 0) {
                whisperContextPtr = finalPtr;
                tvStatus.setText("Model ready — tap ● to record");
                btnMedia.setEnabled(true);
            } else {
                tvStatus.setText("Model failed to load");
            }
        });
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static boolean isValidGgmlFile(File f) {
        try (java.io.FileInputStream fis = new java.io.FileInputStream(f)) {
            byte[] magic = new byte[4];
            if (fis.read(magic) < 4) return false;
            // GGML magic: 0x67 0x67 0x6d 0x6c ("ggml") or 0x67 0x67 0x6d 0x66 ("ggmf")
            return (magic[0] == 0x67 && magic[1] == 0x67
                    && (magic[2] == 0x6d) && (magic[3] == 0x6c || magic[3] == 0x66));
        } catch (Exception e) {
            return false;
        }
    }

    // ── Save ─────────────────────────────────────────────────────────────────

    private void saveMarkdown() {
        if (transcriptBuilder.length() == 0) {
            Toast.makeText(this, "Nothing to save yet", Toast.LENGTH_SHORT).show();
            return;
        }
        // Use the displayed filename as title — always correct, user can rename it
        String displayName = tvFilename.getText().toString()
                .replace("  [t]", "").trim(); // strip [t] badge if present
        String base   = displayName.contains(".")
                ? displayName.substring(0, displayName.lastIndexOf('.')) : displayName;
        if (base.isEmpty()) base = "Untitled";
        String mdName = base + ".md";
        String date    = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(new Date());
        String lang    = LANGUAGES[spinnerLanguage.getSelectedItemPosition()];

        String markdown = "# " + base + "\n\n"
                + "*" + date + " · " + lang + "*\n\n"
                + "---\n\n"
                + transcriptBuilder.toString().trim() + "\n";

        byte[] bytes = markdown.getBytes(StandardCharsets.UTF_8);

        if (folderUri != null) {
            try {
                DocumentFile dir = DocumentFile.fromTreeUri(this, folderUri);
                if (dir != null) {
                    DocumentFile ex = dir.findFile(mdName);
                    if (ex != null) ex.delete();
                    DocumentFile nf = dir.createFile("text/markdown", mdName);
                    if (nf != null) {
                        try (OutputStream os = getContentResolver().openOutputStream(nf.getUri())) {
                            os.write(bytes);
                        }
                        tvStatus.setText("Saved: " + mdName);
                        Toast.makeText(this, "Saved " + mdName, Toast.LENGTH_SHORT).show();
                        transcriptSaved = true;
                        return;
                    }
                }
            } catch (IOException e) {
                Log.e(TAG, "SAF save failed", e);
            }
        }

        if (filePath != null) {
            File out = new File(new File(filePath).getParent(), mdName);
            try (java.io.FileOutputStream fos = new java.io.FileOutputStream(out)) {
                fos.write(bytes);
                tvStatus.setText("Saved: " + out.getName());
                Toast.makeText(this, "Saved " + out.getName(), Toast.LENGTH_SHORT).show();
                transcriptSaved = true;
            } catch (IOException e) {
                Toast.makeText(this, "Save failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
            }
        }
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    private void pauseMediaIfPlaying() {
        if (mediaPlayer != null && isPlaying) {
            mediaPlayer.pause();
            isPlaying = false;
            btnMedia.setText("▶");
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();

        if (audioManager != null && audioDeviceCallback != null)
            audioManager.unregisterAudioDeviceCallback(audioDeviceCallback);

        // Release media resources immediately on main thread
        if (mediaPlayer   != null) { try { mediaPlayer.release();   } catch (Exception ignored) {} mediaPlayer   = null; }
        if (mediaRecorder != null) { try { mediaRecorder.stop(); mediaRecorder.release(); } catch (Exception ignored) {} mediaRecorder = null; }
        if (liveRecorder  != null) { try { liveRecorder.stop(recordingWavFile != null
                ? recordingWavFile : new File(getCacheDir(), "tmp.wav")); } catch (Exception ignored) {} liveRecorder = null; }

        executor.shutdownNow();
        whisperExecutor.shutdown();

        final long ctxToFree = whisperContextPtr;
        whisperContextPtr = 0;

        // Copy any finalized WAV from getFilesDir() to SAF folder, then free Whisper context.
        // All on a background thread — never block the main thread.
        new Thread(() -> {
            // Copy finalized recordings to SAF folder
            Uri targetFolder = folderUri != null ? folderUri : parseSavedFolderUri();
            if (targetFolder != null) {
                File[] wavs = getFilesDir().listFiles(
                        f -> f.getName().endsWith(".wav")
                                && !f.getName().endsWith(".wav.tmp")
                                && f.length() > 44);
                if (wavs != null) {
                    for (File wav : wavs) {
                        Uri saved = copyToFolder(targetFolder, wav, wav.getName());
                        if (saved != null) wav.delete();
                    }
                }
            }
            // Wait for Whisper to finish its current chunk, then free context
            try { whisperExecutor.awaitTermination(60, java.util.concurrent.TimeUnit.SECONDS); }
            catch (InterruptedException ignored) {}
            if (ctxToFree != 0) WhisperLib.freeContext(ctxToFree);
        }, "cleanup").start();
    }
}

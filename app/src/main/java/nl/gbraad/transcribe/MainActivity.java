package nl.gbraad.transcribe;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.ActionBarDrawerToggle;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.view.GravityCompat;
import androidx.documentfile.provider.DocumentFile;
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import android.database.Cursor;
import android.provider.OpenableColumns;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class MainActivity extends AppCompatActivity {

    private static final Set<String> AUDIO_EXTENSIONS = new HashSet<>(Arrays.asList(
            "mp3", "m4a", "wav", "flac", "aac", "ogg", "opus", "aiff", "caf"
    ));
    private static final Set<String> VIDEO_EXTENSIONS = new HashSet<>(Arrays.asList(
            "mp4", "mkv", "avi", "mov", "webm", "3gp"
    ));
    // .md files are NOT shown as rows — a [t] badge on the audio row indicates one exists

    private static final int PERMISSION_CODE = 100;
    private static final String PREFS = "transcribe";

    private DrawerLayout drawerLayout;
    private TextView tvCurrentModel;
    private RecyclerView recyclerView;
    private TextView tvCurrentPath, tvEmpty;
    private FileListAdapter adapter;
    private final List<File> files = new ArrayList<>();
    private final HashMap<String, Uri> fileUriMap = new HashMap<>();
    private final HashMap<String, Long> fileSizeMap = new HashMap<>();
    private final HashSet<String> transcriptSet = new HashSet<>(); // base names with a .md

    private File currentFolder;
    private Uri currentFolderUri;

    private ActivityResultLauncher<Intent> folderPickerLauncher;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        checkForLeftoverRecording();
        cleanGetFilesDir(); // remove any stale recordings that already exist in SAF folder
        drawerLayout   = findViewById(R.id.drawer_layout);
        tvCurrentModel = findViewById(R.id.tv_current_model);
        recyclerView   = findViewById(R.id.recycler_files);
        tvCurrentPath  = findViewById(R.id.tv_current_path);
        tvEmpty        = findViewById(R.id.tv_empty);
        Button btnSelectFolder = findViewById(R.id.btn_select_folder);
        Button btnRefresh      = findViewById(R.id.btn_refresh);
        Button btnRecord       = findViewById(R.id.btn_record);
        Button btnModelTiny    = findViewById(R.id.btn_model_tiny);
        Button btnModelBase    = findViewById(R.id.btn_model_base);
        Button btnModelPick    = findViewById(R.id.btn_model_pick);
        android.widget.Spinner spinnerLang = findViewById(R.id.spinner_default_language);
        androidx.appcompat.widget.SwitchCompat switchLive =
                findViewById(R.id.switch_live_transcribe);
        android.widget.Spinner spinnerChunk = findViewById(R.id.spinner_chunk_size);

        // Set up Toolbar as ActionBar so DrawerToggle works
        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);

        // ActionBarDrawerToggle adds the ☰ hamburger and animates it
        ActionBarDrawerToggle toggle = new ActionBarDrawerToggle(
                this, drawerLayout, toolbar,
                android.R.string.ok, android.R.string.cancel);
        drawerLayout.addDrawerListener(toggle);
        toggle.syncState();

        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        adapter = new FileListAdapter(files, this::openFile);
        adapter.setSizeMap(fileSizeMap);
        adapter.setUriMap(fileUriMap);
        adapter.setTranscriptSet(transcriptSet);
        adapter.setDeleteListener((file, position) -> {
            if (position >= 0 && position < files.size()) {
                files.remove(position);
                fileSizeMap.remove(file.getName());
                fileUriMap.remove(file.getName());
                String base = file.getName().contains(".")
                        ? file.getName().substring(0, file.getName().lastIndexOf('.')) : file.getName();
                transcriptSet.remove(base); // may now expose orphaned .md
                adapter.notifyItemRemoved(position);
                updateEmpty();
                // Rescan so any orphaned .md becomes visible
                if (currentFolderUri != null) scanViaSAF(currentFolderUri);
                else if (currentFolder != null) scanDirect(currentFolder);
            }
        });
        recyclerView.setAdapter(adapter);

        folderPickerLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                        Uri uri = result.getData().getData();
                        if (uri != null) handleFolderSelected(uri);
                    }
                });

        modelPickerLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                        Uri uri = result.getData().getData();
                        if (uri != null) {
                            try { getContentResolver().takePersistableUriPermission(uri,
                                    Intent.FLAG_GRANT_READ_URI_PERMISSION); } catch (Exception ignored) {}
                            getSharedPreferences("transcribe", MODE_PRIVATE).edit()
                                    .putString("model_uri", uri.toString()).remove("model_path").apply();
                            updateModelLabel();
                        }
                    }
                });

        updateModelLabel();

        // Live transcription toggle
        boolean livePref = getSharedPreferences("transcribe", MODE_PRIVATE)
                .getBoolean("live_transcribe", true);
        switchLive.setChecked(livePref);
        switchLive.setOnCheckedChangeListener((v, checked) ->
                getSharedPreferences("transcribe", MODE_PRIVATE).edit()
                        .putBoolean("live_transcribe", checked).apply());

        // Chunk size spinner
        String[] chunkLabels = {"3 s", "5 s", "10 s", "15 s", "20 s"};
        int[]    chunkValues = {3, 5, 10, 15, 20};
        android.widget.ArrayAdapter<String> chunkAdapter = new android.widget.ArrayAdapter<>(
                this, R.layout.spinner_dropdown_item, chunkLabels);
        spinnerChunk.setAdapter(chunkAdapter);
        int savedChunk = getSharedPreferences("transcribe", MODE_PRIVATE)
                .getInt("chunk_secs", 5);
        for (int i = 0; i < chunkValues.length; i++) {
            if (chunkValues[i] == savedChunk) { spinnerChunk.setSelection(i); break; }
        }
        spinnerChunk.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(android.widget.AdapterView<?> p,
                    android.view.View v, int pos, long id) {
                getSharedPreferences("transcribe", MODE_PRIVATE).edit()
                        .putInt("chunk_secs", chunkValues[pos]).apply();
            }
            @Override public void onNothingSelected(android.widget.AdapterView<?> p) {}
        });

        // Language spinner in drawer — show full names, store codes
        String[] languages = TranscribeActivity.LANGUAGES;
        String[] languageNames = {"Auto-detect","English","German","Dutch","French","Spanish",
                "Italian","Portuguese","Russian","Chinese","Japanese","Korean","Arabic"};
        android.widget.ArrayAdapter<String> langAdapter = new android.widget.ArrayAdapter<>(
                this, R.layout.spinner_dropdown_item, languageNames);
        spinnerLang.setAdapter(langAdapter);
        String savedLang = getSharedPreferences("transcribe", MODE_PRIVATE)
                .getString("default_language", "auto");
        for (int i = 0; i < languages.length; i++) {
            if (languages[i].equals(savedLang)) { spinnerLang.setSelection(i); break; }
        }
        spinnerLang.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(android.widget.AdapterView<?> p,
                    android.view.View v, int pos, long id) {
                getSharedPreferences("transcribe", MODE_PRIVATE).edit()
                        .putString("default_language", languages[pos]).apply();
            }
            @Override public void onNothingSelected(android.widget.AdapterView<?> p) {}
        });

        btnRecord.setOnClickListener(v -> {
            Intent intent = new Intent(this, TranscribeActivity.class);
            intent.putExtra(TranscribeActivity.EXTRA_MODE, TranscribeActivity.MODE_RECORD);
            if (currentFolderUri != null)
                intent.putExtra("folder_uri", currentFolderUri.toString());
            startActivity(intent);
        });

        btnSelectFolder.setOnClickListener(v -> { drawerLayout.closeDrawers(); openFolderPicker(); });

        btnModelTiny.setOnClickListener(v -> { drawerLayout.closeDrawers(); downloadModel("ggml-tiny.bin",
            "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-tiny.bin"); });
        btnModelBase.setOnClickListener(v -> { drawerLayout.closeDrawers(); downloadModel("ggml-base.bin",
            "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-base.bin"); });
        btnModelPick.setOnClickListener(v -> { drawerLayout.closeDrawers(); pickModelFile(); });

        btnRefresh.setOnClickListener(v -> {
            if (currentFolderUri != null) scanViaSAF(currentFolderUri);
            else if (currentFolder != null) scanDirect(currentFolder);
        });

        new android.os.Handler().postDelayed(() -> {
            if (!checkPermissions()) {
                tvCurrentPath.setText("Grant storage permission, then select a folder");
                return;
            }
            String saved = getSharedPreferences(PREFS, MODE_PRIVATE).getString("folder_uri", null);
            if (saved != null) {
                Uri uri = Uri.parse(saved);
                currentFolderUri = uri;
                currentFolder = uriToFile(uri);
                scanViaSAF(uri);
            } else {
                tvCurrentPath.setText("Tap 'Select Folder' to choose a folder with audio/video files");
            }
        }, 300);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (currentFolderUri != null) scanViaSAF(currentFolderUri);
        else if (currentFolder != null) scanDirect(currentFolder);
        // SAF providers sometimes cache directory listings — rescan after a short delay
        // to pick up files written just before navigating back
        new android.os.Handler().postDelayed(() -> {
            if (currentFolderUri != null) scanViaSAF(currentFolderUri);
        }, 600);
    }

    /**
     * Shows finalized .wav files from getFilesDir() directly in the list — no copying needed.
     * They're already on device; the user can play and transcribe them immediately.
     * (They live in the app's internal files dir, not the user's SAF folder.)
     */
    private void syncInternalRecordings() {
        File[] internal = getFilesDir().listFiles(f -> {
            String n = f.getName();
            return (n.endsWith(".wav") || n.endsWith(".m4a"))
                    && !n.endsWith(".wav.tmp")
                    && f.length() > 1000;
        });
        if (internal == null || internal.length == 0) return;
        boolean changed = false;
        for (File wav : internal) {
            if (!fileUriMap.containsKey(wav.getName())) {
                files.add(wav);
                fileSizeMap.put(wav.getName(), wav.length());
                // No SAF URI entry — FileListAdapter falls back to Uri.fromFile()
                changed = true;
            }
        }
        if (changed) {
            adapter.notifyDataSetChanged();
            updateEmpty();
            tvCurrentPath.setText(files.size() + " file(s)");
        }
    }

    /** Delete any audio files from getFilesDir() that are already in the SAF folder. */
    private void cleanGetFilesDir() {
        File[] stale = getFilesDir().listFiles(
                f -> (f.getName().endsWith(".wav") || f.getName().endsWith(".m4a"))
                        && f.length() > 44);
        if (stale == null || stale.length == 0) return;
        new Thread(() -> {
            for (File f : stale) {
                // If a matching file exists in the SAF folder, delete the internal copy
                if (currentFolderUri != null) {
                    DocumentFile dir = DocumentFile.fromTreeUri(this, currentFolderUri);
                    if (dir != null && dir.findFile(f.getName()) != null) {
                        f.delete();
                    }
                }
            }
        }).start();
    }

    private void checkForLeftoverRecording() {
        File[] wavFiles = getCacheDir().listFiles(
                f -> f.getName().endsWith(".wav") && f.length() > 100_000);
        if (wavFiles == null || wavFiles.length == 0) return;

        // Sort newest first
        java.util.Arrays.sort(wavFiles, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));

        // Build list string for single dialog
        StringBuilder msg = new StringBuilder("Unfinished recordings found:\n");
        for (File f : wavFiles) msg.append("• ").append(f.getName())
                .append(" (").append(f.length() / 1024).append(" KB)\n");
        msg.append("\nSave all to your folder?");

        new android.app.AlertDialog.Builder(this)
                .setTitle("Recover recordings")
                .setMessage(msg.toString())
                .setPositiveButton("Save all", (d, w) -> new Thread(() -> {
                    int saved = 0;
                    for (File f : wavFiles) {
                        if (currentFolderUri != null) {
                            DocumentFile dir = DocumentFile.fromTreeUri(this, currentFolderUri);
                            if (dir != null) {
                                DocumentFile df = dir.createFile("audio/x-wav", f.getName());
                                if (df != null) {
                                    try (java.io.FileInputStream in = new java.io.FileInputStream(f);
                                         java.io.OutputStream out = getContentResolver()
                                                 .openOutputStream(df.getUri())) {
                                        byte[] buf = new byte[64 * 1024]; int r;
                                        while ((r = in.read(buf)) != -1) out.write(buf, 0, r);
                                        saved++;
                                    } catch (java.io.IOException ignored) {}
                                }
                            }
                        }
                        f.delete();
                    }
                    final int finalSaved = saved;
                    runOnUiThread(() -> {
                        Toast.makeText(this,
                                finalSaved > 0 ? "Recovered " + finalSaved + " recording(s)"
                                        : "Recovery failed — select a folder first",
                                Toast.LENGTH_LONG).show();
                        if (currentFolderUri != null) scanViaSAF(currentFolderUri);
                    });
                }).start())
                .setNegativeButton("Discard all", (d, w) -> {
                    for (File f : wavFiles) f.delete();
                })
                .show();
    }

    private void openFolderPicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION |
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION |
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        folderPickerLauncher.launch(intent);
    }

    private void handleFolderSelected(Uri uri) {
        try {
            getContentResolver().takePersistableUriPermission(uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION |
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        } catch (Exception e) {
            android.util.Log.w("MainActivity", "Could not take persistable permission", e);
        }

        currentFolderUri = uri;
        currentFolder = uriToFile(uri);

        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putString("folder_uri", uri.toString()).apply();

        scanViaSAF(uri);
    }

    private void scanViaSAF(Uri treeUri) {
        files.clear();
        fileUriMap.clear();
        fileSizeMap.clear();
        transcriptSet.clear();

        DocumentFile dir = DocumentFile.fromTreeUri(this, treeUri);
        if (dir == null || !dir.exists()) {
            tvCurrentPath.setText("Cannot access folder");
            updateEmpty();
            return;
        }

        tvCurrentPath.setText("Scanning: " + dir.getName());

        // Two-pass: first collect everything, then add orphaned .md at end
        java.util.Map<String, DocumentFile> mdDocs = new java.util.HashMap<>();
        java.util.Set<String> audioBaseNames = new java.util.HashSet<>();

        for (DocumentFile doc : dir.listFiles()) {
            String name = doc.getName();
            if (name == null || !doc.isFile()) continue;
            String ext = extension(name);
            if (ext.equals("md")) {
                String base = name.substring(0, name.length() - 3);
                transcriptSet.add(base);
                mdDocs.put(base, doc);
                continue;
            }
            if (AUDIO_EXTENSIONS.contains(ext) || VIDEO_EXTENSIONS.contains(ext)) {
                String base = name.contains(".") ? name.substring(0, name.lastIndexOf('.')) : name;
                audioBaseNames.add(base);
                File file = currentFolder != null
                        ? new File(currentFolder, name)
                        : new File(name);
                files.add(file);
                fileUriMap.put(name, doc.getUri());
                fileSizeMap.put(name, querySize(doc.getUri()));
            }
        }

        // Add orphaned .md files (no matching audio/video) so user can delete them
        for (java.util.Map.Entry<String, DocumentFile> e : mdDocs.entrySet()) {
            if (!audioBaseNames.contains(e.getKey())) {
                String mdName = e.getKey() + ".md";
                File file = currentFolder != null
                        ? new File(currentFolder, mdName) : new File(mdName);
                files.add(file);
                fileUriMap.put(mdName, e.getValue().getUri());
                fileSizeMap.put(mdName, querySize(e.getValue().getUri()));
            }
        }

        adapter.notifyDataSetChanged();
        updateEmpty();
        tvCurrentPath.setText(files.isEmpty()
                ? dir.getName()
                : files.size() + " file(s) in " + dir.getName());
    }

    private void scanDirect(File folder) {
        files.clear();
        fileUriMap.clear();
        transcriptSet.clear();
        if (!folder.exists()) { updateEmpty(); return; }

        File[] all = folder.listFiles();
        if (all != null) {
            for (File f : all) {
                String ext = extension(f.getName());
                if (ext.equals("md")) {
                    transcriptSet.add(f.getName().substring(0, f.getName().length() - 3));
                } else if (AUDIO_EXTENSIONS.contains(ext) || VIDEO_EXTENSIONS.contains(ext)) {
                    files.add(f);
                }
            }
        }
        adapter.notifyDataSetChanged();
        updateEmpty();
        tvCurrentPath.setText(files.size() + " file(s)");
    }

    private ActivityResultLauncher<Intent> modelPickerLauncher;

    private void pickModelFile() {
        if (modelPickerLauncher == null) {
            Toast.makeText(this, "Restart app and try again", Toast.LENGTH_SHORT).show();
            return;
        }
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        modelPickerLauncher.launch(i);
    }

    private void downloadModel(String filename, String url) {
        File outFile = new File(getFilesDir(), filename);
        if (outFile.exists() && outFile.length() > 1_000_000L) {
            saveModelPath(outFile.getAbsolutePath());
            updateModelLabel();
            Toast.makeText(this, filename + " already downloaded", Toast.LENGTH_SHORT).show();
            return;
        }
        tvCurrentModel.setText("Downloading " + filename + "...");
        new Thread(() -> {
            try {
                java.net.HttpURLConnection conn =
                        (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
                conn.setInstanceFollowRedirects(true);
                conn.setRequestProperty("User-Agent", "Transcribe-Android/1.0");
                conn.setConnectTimeout(30_000);
                conn.setReadTimeout(120_000);
                conn.connect();
                String ct = conn.getContentType();
                if (ct != null && ct.startsWith("text/"))
                    throw new java.io.IOException("Got HTML — check connection");
                long total = conn.getContentLength();
                try (java.io.InputStream in = conn.getInputStream();
                     java.io.FileOutputStream fos = new java.io.FileOutputStream(outFile)) {
                    byte[] buf = new byte[64 * 1024]; int read; long dl = 0;
                    while ((read = in.read(buf)) != -1) {
                        fos.write(buf, 0, read); dl += read;
                        if (total > 0) {
                            int pct = (int)(100L * dl / total);
                            runOnUiThread(() -> tvCurrentModel.setText("Downloading " + filename + "... " + pct + "%"));
                        }
                    }
                }
                if (outFile.length() < 1_000_000L) { outFile.delete(); throw new java.io.IOException("File too small"); }
                runOnUiThread(() -> { saveModelPath(outFile.getAbsolutePath()); updateModelLabel();
                    Toast.makeText(this, filename + " ready", Toast.LENGTH_SHORT).show(); });
            } catch (Exception e) {
                outFile.delete();
                runOnUiThread(() -> { tvCurrentModel.setText("Download failed"); Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show(); });
            }
        }).start();
    }

    private void saveModelPath(String path) {
        getSharedPreferences("transcribe", MODE_PRIVATE).edit()
                .putString("model_path", path).remove("model_uri").apply();
    }

    private void updateModelLabel() {
        String p = getSharedPreferences("transcribe", MODE_PRIVATE).getString("model_path", null);
        tvCurrentModel.setText(p != null ? new File(p).getName() : "None");
    }

    private void openFile(File file) {
        Intent intent = new Intent(this, TranscribeActivity.class);
        intent.putExtra("file_path", file.getAbsolutePath());
        Uri contentUri = fileUriMap.get(file.getName());
        if (contentUri != null) intent.putExtra("content_uri", contentUri.toString());
        if (currentFolderUri != null)
            intent.putExtra("folder_uri", currentFolderUri.toString());
        if (file.getName().toLowerCase().endsWith(".md"))
            intent.putExtra(TranscribeActivity.EXTRA_MODE, TranscribeActivity.MODE_TEXT);
        startActivity(intent);
    }

    private void updateEmpty() {
        boolean empty = files.isEmpty();
        recyclerView.setVisibility(empty ? View.GONE : View.VISIBLE);
        tvEmpty.setVisibility(empty ? View.VISIBLE : View.GONE);
    }

    private String extension(String name) {
        int dot = name.lastIndexOf('.');
        return dot >= 0 ? name.substring(dot + 1).toLowerCase() : "";
    }

    private File uriToFile(Uri uri) {
        String s = uri.toString();
        if (s.contains("/tree/primary:")) {
            String rel = Uri.decode(s.substring(s.lastIndexOf("/tree/primary:") + 14));
            return new File(Environment.getExternalStorageDirectory(), rel);
        }
        if (s.contains("/tree/primary%3A")) {
            String rel = Uri.decode(s.substring(s.lastIndexOf("/tree/primary%3A") + 16));
            return new File(Environment.getExternalStorageDirectory(), rel);
        }
        return null;
    }

    private boolean checkPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return Environment.isExternalStorageManager();
        }
        int r = ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE);
        int w = ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE);
        return r == PackageManager.PERMISSION_GRANTED && w == PackageManager.PERMISSION_GRANTED;
    }

    private long querySize(Uri uri) {
        try (Cursor c = getContentResolver().query(uri,
                new String[]{OpenableColumns.SIZE}, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int col = c.getColumnIndex(OpenableColumns.SIZE);
                if (col >= 0 && !c.isNull(col)) return c.getLong(col);
            }
        } catch (Exception ignored) {}
        return 0;
    }

    private void requestPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                Intent i = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:" + getPackageName()));
                startActivity(i);
            } catch (Exception e) {
                startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
            }
        } else {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.READ_EXTERNAL_STORAGE,
                                 Manifest.permission.WRITE_EXTERNAL_STORAGE},
                    PERMISSION_CODE);
        }
        Toast.makeText(this, "Grant storage permission, then return here", Toast.LENGTH_LONG).show();
    }
}

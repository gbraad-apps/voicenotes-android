package nl.gbraad.transcribe;

import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.media.MediaPlayer;
import android.net.Uri;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.documentfile.provider.DocumentFile;
import androidx.recyclerview.widget.RecyclerView;

import java.io.File;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class FileListAdapter extends RecyclerView.Adapter<FileListAdapter.ViewHolder> {

    public interface OnFileClickListener {
        void onFileClick(File file);
    }

    public interface OnFileDeleteListener {
        void onFileDelete(File file, int position);
    }

    private final List<File> files;
    private final OnFileClickListener transcribeListener;
    private OnFileDeleteListener deleteListener;
    private Map<String, Long> sizeMap = new HashMap<>();
    private Map<String, Uri> uriMap   = new HashMap<>();

    public FileListAdapter(List<File> files, OnFileClickListener transcribeListener) {
        this.files = files;
        this.transcribeListener = transcribeListener;
    }

    private java.util.Set<String> transcriptSet = new java.util.HashSet<>();

    public void setSizeMap(Map<String, Long> map) { this.sizeMap = map; }
    public void setUriMap(Map<String, Uri> map)   { this.uriMap  = map; }
    public void setTranscriptSet(java.util.Set<String> set) { this.transcriptSet = set; }
    public void setDeleteListener(OnFileDeleteListener l) { this.deleteListener = l; }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_file, parent, false);
        return new ViewHolder(v);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        holder.bind(files.get(position), position, transcribeListener, deleteListener, sizeMap, uriMap, transcriptSet);
    }

    @Override
    public int getItemCount() { return files.size(); }

    class ViewHolder extends RecyclerView.ViewHolder {
        TextView tvFilename, tvFilesize;
        Button btnPlay, btnDelete, btnTranscribeFile;
        private MediaPlayer preview;
        private boolean deleteArmed = false;

        ViewHolder(@NonNull View itemView) {
            super(itemView);
            tvFilename        = itemView.findViewById(R.id.tv_filename);
            tvFilesize        = itemView.findViewById(R.id.tv_filesize);
            btnPlay           = itemView.findViewById(R.id.btn_play);
            btnDelete         = itemView.findViewById(R.id.btn_delete);
            btnTranscribeFile = itemView.findViewById(R.id.btn_transcribe_file);
        }

        void bind(File file, int position, OnFileClickListener transcribeListener,
                  OnFileDeleteListener deleteListener, Map<String, Long> sizeMap,
                  Map<String, Uri> uriMap, java.util.Set<String> transcriptSet) {

            deleteArmed = false;
            btnDelete.setText("✕");
            btnDelete.setBackgroundTintList(
                    android.content.res.ColorStateList.valueOf(0xFF333333));

            String name = file.getName();
            String base = name.contains(".") ? name.substring(0, name.lastIndexOf('.')) : name;
            boolean hasTranscript = transcriptSet.contains(base);

            // Show [t] badge inline after filename when transcript exists
            tvFilename.setText(hasTranscript ? name + "  [t]" : name);

            long bytes = sizeMap.containsKey(file.getName())
                    ? sizeMap.get(file.getName()) : file.length();
            long sizeMB = bytes / (1024 * 1024);
            long sizeKB = bytes / 1024;
            tvFilesize.setText(bytes == 0 ? "—" : sizeMB > 0 ? sizeMB + " MB" : sizeKB + " KB");


            boolean isMd = name.toLowerCase().endsWith(".md");
            btnPlay.setEnabled(!isMd);
            btnPlay.setAlpha(isMd ? 0.3f : 1f);

            // Tap filename → rename
            tvFilename.setOnClickListener(v -> showRenameDialog(v.getContext(), file, uriMap));

            // ▶ play preview (disabled for .md)
            btnPlay.setOnClickListener(v -> { if (!isMd) togglePreview(v.getContext(), file, uriMap); });

            // × delete — first tap arms, second tap confirms
            btnDelete.setOnClickListener(v -> {
                if (!deleteArmed) {
                    deleteArmed = true;
                    btnDelete.setText("?");
                    btnDelete.setBackgroundTintList(
                            android.content.res.ColorStateList.valueOf(0xFFCF1A37));
                    btnDelete.postDelayed(() -> {
                        if (deleteArmed) {
                            deleteArmed = false;
                            btnDelete.setText("✕");
                            btnDelete.setBackgroundTintList(
                                    android.content.res.ColorStateList.valueOf(0xFF333333));
                        }
                    }, 3000);
                } else {
                    deleteArmed = false;
                    stopPreview();
                    Uri uri = uriMap.get(file.getName());
                    if (uri != null) {
                        DocumentFile doc = DocumentFile.fromSingleUri(v.getContext(), uri);
                        if (doc != null) doc.delete();
                    } else {
                        file.delete();
                    }
                    if (deleteListener != null) deleteListener.onFileDelete(file, getAdapterPosition());
                }
            });

            // → open for transcription
            btnTranscribeFile.setOnClickListener(v -> {
                stopPreview();
                if (transcribeListener != null) transcribeListener.onFileClick(file);
            });
        }

        private void togglePreview(Context ctx, File file, Map<String, Uri> uriMap) {
            if (preview != null && preview.isPlaying()) {
                stopPreview();
                return;
            }
            stopPreview();
            preview = new MediaPlayer();
            try {
                Uri uri = uriMap.containsKey(file.getName())
                        ? uriMap.get(file.getName()) : Uri.fromFile(file);
                preview.setDataSource(ctx, uri);
                preview.prepareAsync();
                preview.setOnPreparedListener(mp -> {
                    mp.start();
                    btnPlay.setText("||");
                    btnPlay.setBackgroundTintList(
                            android.content.res.ColorStateList.valueOf(0xFFCF1A37));
                });
                preview.setOnCompletionListener(mp -> stopPreview());
            } catch (Exception e) {
                Toast.makeText(ctx, "Cannot play file", Toast.LENGTH_SHORT).show();
                stopPreview();
            }
        }

        private void stopPreview() {
            if (preview != null) {
                try { preview.stop(); } catch (Exception ignored) {}
                preview.release();
                preview = null;
            }
            btnPlay.setText("▶");
            btnPlay.setBackgroundTintList(
                    android.content.res.ColorStateList.valueOf(0xFF333333));
        }

        private void showRenameDialog(Context ctx, File file, Map<String, Uri> uriMap) {
            String current = file.getName();
            String base = current.contains(".") ? current.substring(0, current.lastIndexOf('.')) : current;
            String ext  = current.contains(".") ? current.substring(current.lastIndexOf('.')) : "";
            EditText input = new EditText(ctx);
            input.setText(base);
            input.selectAll();
            new AlertDialog.Builder(ctx)
                    .setTitle("Rename")
                    .setView(input)
                    .setPositiveButton("Rename", (d, w) -> {
                        String newBase = input.getText().toString().trim();
                        if (newBase.isEmpty() || newBase.equals(base)) return;
                        Uri uri = uriMap.get(current);
                        if (uri != null) {
                            DocumentFile doc = DocumentFile.fromSingleUri(ctx, uri);
                            if (doc != null) doc.renameTo(newBase + ext);
                        } else {
                            file.renameTo(new File(file.getParent(), newBase + ext));
                        }
                        tvFilename.setText(newBase + ext);
                    })
                    .setNegativeButton("Cancel", null)
                    .show();
        }
    }
}

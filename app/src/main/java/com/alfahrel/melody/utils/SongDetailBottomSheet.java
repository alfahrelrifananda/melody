package com.alfahrel.melody.utils;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.alfahrel.melody.R;
import com.alfahrel.melody.ui.music.MusicItem;
import com.bumptech.glide.Glide;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;

import java.io.File;

public class SongDetailBottomSheet extends BottomSheetDialogFragment {

    private static final String ARG_MUSIC_ITEM = "music_item";

    public static SongDetailBottomSheet newInstance(MusicItem musicItem) {
        SongDetailBottomSheet sheet = new SongDetailBottomSheet();
        Bundle args = new Bundle();
        args.putParcelable(ARG_MUSIC_ITEM, musicItem);
        sheet.setArguments(args);
        return sheet;
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.dialog_song_details, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        MusicItem musicItem = null;
        if (getArguments() != null) {
            musicItem = getArguments().getParcelable(ARG_MUSIC_ITEM);
        }

        if (musicItem == null) {
            dismiss();
            return;
        }

        ImageView albumArt       = view.findViewById(R.id.detailAlbumArt);
        TextView  titleText      = view.findViewById(R.id.detailTitleText);
        TextView  artistText     = view.findViewById(R.id.detailArtistText);
        TextView  albumText      = view.findViewById(R.id.detailAlbumText);
        TextView  durationText   = view.findViewById(R.id.detailDurationText);
        TextView  fileSizeText   = view.findViewById(R.id.detailFileSizeText);
        TextView  pathText       = view.findViewById(R.id.detailPathText);

        titleText.setText(musicItem.getTitle());
        artistText.setText(musicItem.getArtist());
        albumText.setText(musicItem.getAlbum());
        durationText.setText(formatDuration(musicItem.getDuration()));
        pathText.setText(musicItem.getPath());

        // File size
        File file = new File(musicItem.getPath());
        fileSizeText.setText(file.exists() ? formatFileSize(file.length()) : "Unknown");

        // Album art
        Glide.with(this)
                .load(musicItem.getAlbumArtUri())
                .placeholder(R.drawable.ic_outline_music_note_24)
                .error(R.drawable.ic_outline_music_note_24)
                .into(albumArt);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private String formatDuration(long duration) {
        long seconds = (duration / 1000) % 60;
        long minutes = (duration / (1000 * 60)) % 60;
        long hours   =  duration / (1000 * 60 * 60);
        return hours > 0
                ? String.format("%02d:%02d:%02d", hours, minutes, seconds)
                : String.format("%02d:%02d", minutes, seconds);
    }

    private String formatFileSize(long size) {
        if (size <= 0) return "0 B";
        final String[] units = {"B", "KB", "MB", "GB"};
        int i = (int) (Math.log10(size) / Math.log10(1024));
        return String.format("%.2f %s", size / Math.pow(1024, i), units[i]);
    }
}
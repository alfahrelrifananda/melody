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

public class SongOptionsBottomSheet extends BottomSheetDialogFragment {

    private static final String ARG_MUSIC_ITEM = "music_item";

    public interface SongOptionsListener {
        void onAddToCollection(MusicItem item);
        void onViewDetails(MusicItem item);
        void onDelete(MusicItem item);
        void onPin(MusicItem item);
    }

    private SongOptionsListener listener;

    public static SongOptionsBottomSheet newInstance(MusicItem musicItem) {
        SongOptionsBottomSheet sheet = new SongOptionsBottomSheet();
        Bundle args = new Bundle();
        args.putParcelable(ARG_MUSIC_ITEM, musicItem);
        sheet.setArguments(args);
        return sheet;
    }

    public void setListener(SongOptionsListener listener) {
        this.listener = listener;
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.bottom_sheet_song_options, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        MusicItem item = null;
        if (getArguments() != null) {
            item = getArguments().getParcelable(ARG_MUSIC_ITEM);
        }
        if (item == null) { dismiss(); return; }

        final MusicItem musicItem = item;

        // Header
        ImageView albumArt = view.findViewById(R.id.optionsAlbumArt);
        TextView  title    = view.findViewById(R.id.optionsSongTitle);
        TextView  artist   = view.findViewById(R.id.optionsArtistName);

        title.setText(musicItem.getTitle());
        artist.setText(musicItem.getArtist());

        Glide.with(this)
                .load(musicItem.getAlbumArtUri())
                .placeholder(R.drawable.ic_outline_music_note_24)
                .error(R.drawable.ic_outline_music_note_24)
                .into(albumArt);

        // Pin label reflects current state
        TextView pinLabel = view.findViewById(R.id.optionPinLabel);
        if (pinLabel != null) {
            pinLabel.setText(musicItem.isPinned() ? "Unpin from Home" : "Pin to Home");
        }

        // Actions
        bindAction(view, R.id.optionAddToCollection, () -> {
            if (listener != null) listener.onAddToCollection(musicItem);
            dismiss();
        });

        bindAction(view, R.id.optionViewDetails, () -> {
            if (listener != null) listener.onViewDetails(musicItem);
            dismiss();
        });

        bindAction(view, R.id.optionPinSong, () -> {
            if (listener != null) listener.onPin(musicItem);
            dismiss();
        });

        bindAction(view, R.id.optionDelete, () -> {
            if (listener != null) listener.onDelete(musicItem);
            dismiss();
        });
    }

    private void bindAction(View root, int id, Runnable action) {
        View row = root.findViewById(id);
        if (row != null) row.setOnClickListener(v -> action.run());
    }
}
package com.alfahrel.melody.ui.pages.all;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.alfahrel.melody.R;
import com.alfahrel.melody.ui.music.MusicItem;
import com.bumptech.glide.Glide;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

public class AllSongsAdapter extends RecyclerView.Adapter<AllSongsAdapter.VH> {

    public interface OnSongClick { void onClick(MusicItem song); }
    public interface OnSongMore  { void onMore(MusicItem song); }

    private final List<MusicItem> items;
    private final OnSongClick onClick;
    private final OnSongMore  onMore;

    public AllSongsAdapter(List<MusicItem> items, OnSongClick onClick, OnSongMore onMore) {
        this.items   = items;
        this.onClick = onClick;
        this.onMore  = onMore;
    }

    @Override public int getItemCount() { return items.size(); }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_all_songs_row, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int position) {
        MusicItem song = items.get(position);
        Glide.with(h.itemView.getContext())
                .load(song.getAlbumArtUri())
                .placeholder(R.drawable.ic_outline_music_note_24)
                .error(R.drawable.ic_outline_music_note_24)
                .into(h.art);
        h.title.setText(song.getTitle());
        h.artist.setText(song.getArtist());
        h.duration.setText(fmt(song.getDuration()));
        h.itemView.setOnClickListener(v -> onClick.onClick(song));
        h.btnMore.setOnClickListener(v -> onMore.onMore(song));
    }

    private String fmt(long ms) {
        long m = TimeUnit.MILLISECONDS.toMinutes(ms);
        long s = TimeUnit.MILLISECONDS.toSeconds(ms) % 60;
        return String.format(Locale.getDefault(), "%d:%02d", m, s);
    }

    static class VH extends RecyclerView.ViewHolder {
        final ImageView  art;
        final TextView   title, artist, duration;
        final ImageButton btnMore;
        VH(@NonNull View v) {
            super(v);
            art      = v.findViewById(R.id.rowArt);
            title    = v.findViewById(R.id.rowTitle);
            artist   = v.findViewById(R.id.rowArtist);
            duration = v.findViewById(R.id.rowDuration);
            btnMore  = v.findViewById(R.id.rowMore);
        }
    }
}
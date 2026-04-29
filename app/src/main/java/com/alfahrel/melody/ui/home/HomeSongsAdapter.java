package com.alfahrel.melody.ui.home;

import android.content.Context;
import android.net.Uri;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.alfahrel.melody.R;
import com.alfahrel.melody.ui.music.MusicItem;
import com.bumptech.glide.Glide;
import com.google.android.material.button.MaterialButton;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

public class HomeSongsAdapter extends RecyclerView.Adapter<HomeSongsAdapter.SectionViewHolder> {

    public interface OnSongClickListener     { void onSongClick(MusicItem song); }
    public interface OnSongMoreClickListener { void onSongMore(MusicItem song); }
    public interface OnSeeAllClickListener   { void onSeeAll(); }

    private final List<MusicItem> songs;
    private final OnSongClickListener     onSongClick;
    private final OnSongMoreClickListener onSongMore;
    private final OnSeeAllClickListener   onSeeAll;

    public HomeSongsAdapter(List<MusicItem> songs,
                            OnSongClickListener onSongClick,
                            OnSongMoreClickListener onSongMore,
                            OnSeeAllClickListener onSeeAll) {
        this.songs      = songs;
        this.onSongClick = onSongClick;
        this.onSongMore  = onSongMore;
        this.onSeeAll    = onSeeAll;
    }

    @Override public int getItemCount() { return 1; }

    @NonNull
    @Override
    public SectionViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.home_section_songs, parent, false);
        return new SectionViewHolder(v);
    }

    @Override
    public void onBindViewHolder(@NonNull SectionViewHolder holder, int position) {
        holder.bind(songs, onSongClick, onSongMore, onSeeAll);
    }

    static class SectionViewHolder extends RecyclerView.ViewHolder {
        private final ViewGroup songsContainer;
        private final TextView  btnSeeAll;

        SectionViewHolder(@NonNull View itemView) {
            super(itemView);
            songsContainer = itemView.findViewById(R.id.songsContainer);
            btnSeeAll      = itemView.findViewById(R.id.btnSeeAllSongs);
        }

        void bind(List<MusicItem> songs,
                  OnSongClickListener onSongClick,
                  OnSongMoreClickListener onSongMore,
                  OnSeeAllClickListener onSeeAll) {

            Context ctx = itemView.getContext();
            if (ctx == null) {
                Log.e("HomeSongsAdapter", "Context is null!");
                return;
            }

            songsContainer.removeAllViews();

            if (songs.isEmpty()) {
                itemView.setVisibility(View.GONE);
                return;
            }
            itemView.setVisibility(View.VISIBLE);

            for (int i = 0; i < songs.size(); i++) {
                MusicItem song = songs.get(i);
                if (song == null) {
                    Log.e("HomeSongsAdapter", "Found null song in list!");
                    continue;
                }

                View row = LayoutInflater.from(ctx)
                        .inflate(R.layout.item_music, songsContainer, false);

                ImageView art        = row.findViewById(R.id.album_art_image);
                TextView  title      = row.findViewById(R.id.song_title);
                TextView  subtitle   = row.findViewById(R.id.artist_name);
                MaterialButton btnMore = row.findViewById(R.id.info_button);

                if (art == null) {
                    Log.e("HomeSongsAdapter", "ImageView 'art' is null!");
                    continue;
                }

                Uri albumArtUri = song.getAlbumArtUri();
                Log.d("HomeSongsAdapter", "Loading song: " + song.getTitle() +
                        ", art: " + art +
                        ", uri: " + albumArtUri);

                if (albumArtUri != null) {
                    Glide.with(ctx)
                            .load(albumArtUri)
                            .placeholder(R.drawable.ic_outline_music_note_24)
                            .error(R.drawable.ic_outline_music_note_24)
                            .into(art);
                } else {
                    art.setImageResource(R.drawable.ic_outline_music_note_24);
                }

                title.setText(song.getTitle());
                subtitle.setText(song.getArtist() + "  •  " + formatDuration(song.getDuration()));

                row.setOnClickListener(v -> onSongClick.onSongClick(song));
                btnMore.setOnClickListener(v -> onSongMore.onSongMore(song));

                songsContainer.addView(row);
            }

            btnSeeAll.setOnClickListener(v -> onSeeAll.onSeeAll());
        }

        private String formatDuration(long ms) {
            long min = TimeUnit.MILLISECONDS.toMinutes(ms);
            long sec = TimeUnit.MILLISECONDS.toSeconds(ms) % 60;
            return String.format(Locale.getDefault(), "%d:%02d", min, sec);
        }
    }
}
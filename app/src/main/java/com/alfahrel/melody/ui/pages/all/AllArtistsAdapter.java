package com.alfahrel.melody.ui.pages.all;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.alfahrel.melody.R;
import com.alfahrel.melody.ui.artist.ArtistItem;
import com.bumptech.glide.Glide;
import com.bumptech.glide.load.resource.bitmap.CircleCrop;

import java.util.List;

public class AllArtistsAdapter extends RecyclerView.Adapter<AllArtistsAdapter.VH> {

    public interface OnArtistClick { void onClick(ArtistItem artist); }

    private final List<ArtistItem> items;
    private final OnArtistClick    onClick;

    public AllArtistsAdapter(List<ArtistItem> items, OnArtistClick onClick) {
        this.items   = items;
        this.onClick = onClick;
    }

    @Override public int getItemCount() { return items.size(); }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_all_artists_row, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int position) {
        ArtistItem artist = items.get(position);
        Glide.with(h.itemView.getContext())
                .load(artist.getArtistImageUri())
                .placeholder(R.drawable.ic_outline_music_note_24)
                .error(R.drawable.ic_outline_music_note_24)
                .transform(new CircleCrop())
                .into(h.avatar);
        h.name.setText(artist.getArtistName());
        h.songs.setText(artist.getFormattedSongCount());
        h.itemView.setOnClickListener(v -> onClick.onClick(artist));
    }

    static class VH extends RecyclerView.ViewHolder {
        final ImageView avatar;
        final TextView  name, songs;
        VH(@NonNull View v) {
            super(v);
            avatar = v.findViewById(R.id.rowAvatar);
            name   = v.findViewById(R.id.rowName);
            songs  = v.findViewById(R.id.rowSongs);
        }
    }
}
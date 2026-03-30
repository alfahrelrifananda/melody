package com.alfahrel.melody.ui.pages.all;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.alfahrel.melody.R;
import com.alfahrel.melody.ui.album.AlbumItem;
import com.bumptech.glide.Glide;

import java.util.List;

public class AllAlbumsAdapter extends RecyclerView.Adapter<AllAlbumsAdapter.VH> {

    public interface OnAlbumClick { void onClick(AlbumItem album); }

    private final List<AlbumItem> items;
    private final OnAlbumClick onClick;

    public AllAlbumsAdapter(List<AlbumItem> items, OnAlbumClick onClick) {
        this.items   = items;
        this.onClick = onClick;
    }

    @Override public int getItemCount() { return items.size(); }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_all_albums_card, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int position) {
        AlbumItem album = items.get(position);
        Glide.with(h.itemView.getContext())
                .load(album.getAlbumArtUri())
                .placeholder(R.drawable.ic_outline_music_note_24)
                .error(R.drawable.ic_outline_music_note_24)
                .into(h.art);
        h.name.setText(album.getAlbumName());
        h.artist.setText(album.getArtistName());
        h.count.setText(album.getSongCount() + (album.getSongCount() == 1 ? " song" : " songs"));
        h.itemView.setOnClickListener(v -> onClick.onClick(album));
    }

    static class VH extends RecyclerView.ViewHolder {
        final ImageView art;
        final TextView  name, artist, count;
        VH(@NonNull View v) {
            super(v);
            art    = v.findViewById(R.id.cardArt);
            name   = v.findViewById(R.id.cardName);
            artist = v.findViewById(R.id.cardArtist);
            count  = v.findViewById(R.id.cardCount);
        }
    }
}

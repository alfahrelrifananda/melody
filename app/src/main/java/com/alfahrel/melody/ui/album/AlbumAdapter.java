package com.alfahrel.melody.ui.album;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.request.RequestOptions;
import com.alfahrel.melody.R;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;

import java.util.List;

public class AlbumAdapter extends RecyclerView.Adapter<AlbumAdapter.AlbumViewHolder> {

    private List<AlbumItem> albumList;
    private Context context;
    private OnAlbumItemClickListener listener;

    public interface OnAlbumItemClickListener {
        void onAlbumItemClick(AlbumItem albumItem);
        void onPlayButtonClick(AlbumItem albumItem);
    }

    public AlbumAdapter(List<AlbumItem> albumList, Context context) {
        this.albumList = albumList;
        this.context = context;
    }

    public void setOnAlbumItemClickListener(OnAlbumItemClickListener listener) {
        this.listener = listener;
    }

    @NonNull
    @Override
    public AlbumViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_album, parent, false);
        return new AlbumViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull AlbumViewHolder holder, int position) {
        AlbumItem albumItem = albumList.get(position);

        holder.albumNameTextView.setText(albumItem.getAlbumName());
        holder.artistNameTextView.setText(albumItem.getArtistName());
        holder.songCountTextView.setText(albumItem.getFormattedSongCount());

        Glide.with(context)
                .load(albumItem.getAlbumArtUri())
                .apply(new RequestOptions()
                        .placeholder(R.drawable.ic_outline_album_24)
                        .error(R.drawable.ic_outline_album_24)
                        .centerCrop())
                .into(holder.albumArtImageView);

        holder.itemView.setOnClickListener(v -> {
            if (listener != null) {
                listener.onAlbumItemClick(albumItem);
            }
        });

        holder.playButton.setOnClickListener(v -> {
            if (listener != null) {
                listener.onPlayButtonClick(albumItem);
            }
        });
    }

    @Override
    public int getItemCount() {
        return albumList.size();
    }

    public static class AlbumViewHolder extends RecyclerView.ViewHolder {
        MaterialCardView cardView;
        ImageView albumArtImageView;
        TextView albumNameTextView;
        TextView artistNameTextView;
        TextView songCountTextView;
        MaterialButton playButton;

        public AlbumViewHolder(@NonNull View itemView) {
            super(itemView);
            cardView = itemView.findViewById(R.id.album_card);
            albumArtImageView = itemView.findViewById(R.id.album_art_image);
            albumNameTextView = itemView.findViewById(R.id.album_name);
            artistNameTextView = itemView.findViewById(R.id.artist_name);
            songCountTextView = itemView.findViewById(R.id.song_count);
            playButton = itemView.findViewById(R.id.play_button);
        }
    }
}
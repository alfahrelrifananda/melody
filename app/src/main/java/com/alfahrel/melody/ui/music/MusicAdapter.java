package com.alfahrel.melody.ui.music;

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

public class MusicAdapter extends RecyclerView.Adapter<MusicAdapter.MusicViewHolder> {

    private List<MusicItem> musicList;
    private Context context;
    private OnMusicItemClickListener listener;
    private OnMusicItemLongClickListener longClickListener;

    public interface OnMusicItemClickListener {
        void onMusicItemClick(MusicItem musicItem);
        void onOptionClick(MusicItem musicItem);
    }

    public interface OnMusicItemLongClickListener {
        boolean onMusicItemLongClick(MusicItem musicItem);
    }

    public MusicAdapter(List<MusicItem> musicList, Context context) {
        this.musicList = musicList;
        this.context = context;
    }

    public void setOnMusicItemLongClickListener(OnMusicItemLongClickListener listener) {
        this.longClickListener = listener;
    }

    public void setOnMusicItemClickListener(OnMusicItemClickListener listener) {
        this.listener = listener;
    }

    @NonNull
    @Override
    public MusicViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_music, parent, false);
        return new MusicViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull MusicViewHolder holder, int position) {
        MusicItem musicItem = musicList.get(position);

        // Song number
        holder.songNumberTextView.setText(String.valueOf(position + 1));

        holder.titleTextView.setText(musicItem.getTitle());
        holder.artistTextView.setText(musicItem.getArtist());

        Glide.with(context)
                .load(musicItem.getAlbumArtUri())
                .apply(new RequestOptions()
                        .placeholder(R.drawable.ic_outline_music_note_24)
                        .error(R.drawable.ic_outline_music_note_24)
                        .centerCrop())
                .into(holder.albumArtImageView);

        holder.itemView.setOnClickListener(v -> {
            if (listener != null) listener.onMusicItemClick(musicItem);
        });

        holder.itemView.setOnLongClickListener(v -> {
            if (longClickListener != null) return longClickListener.onMusicItemLongClick(musicItem);
            return false;
        });

        holder.infoButton.setOnClickListener(v -> {
            if (listener != null) listener.onOptionClick(musicItem);
        });
    }

    @Override
    public int getItemCount() {
        return musicList.size();
    }

    public static class MusicViewHolder extends RecyclerView.ViewHolder {
        MaterialCardView cardView;
        ImageView albumArtImageView;
        TextView titleTextView;
        TextView artistTextView;
        TextView songNumberTextView; // added
        MaterialButton infoButton;

        public MusicViewHolder(@NonNull View itemView) {
            super(itemView);
            cardView = itemView.findViewById(R.id.music_card);
            albumArtImageView = itemView.findViewById(R.id.album_art_image);
            titleTextView = itemView.findViewById(R.id.song_title);
            artistTextView = itemView.findViewById(R.id.artist_name);
            songNumberTextView = itemView.findViewById(R.id.song_number);
            infoButton = itemView.findViewById(R.id.info_button);
        }
    }
}
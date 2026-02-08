package com.alfahrel.melody.ui.artist;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.alfahrel.melody.R;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;

import java.util.List;
import java.util.Locale;

public class ArtistAdapter extends RecyclerView.Adapter<ArtistAdapter.ArtistViewHolder> {

    private List<ArtistItem> artistList;
    private Context context;
    private OnArtistItemClickListener listener;

    public interface OnArtistItemClickListener {
        void onArtistItemClick(ArtistItem artistItem);
        void onPlayButtonClick(ArtistItem artistItem);
    }

    public ArtistAdapter(List<ArtistItem> artistList, Context context) {
        this.artistList = artistList;
        this.context = context;
    }

    public void setOnArtistItemClickListener(OnArtistItemClickListener listener) {
        this.listener = listener;
    }

    @NonNull
    @Override
    public ArtistViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_artist, parent, false);
        return new ArtistViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ArtistViewHolder holder, int position) {
        ArtistItem artistItem = artistList.get(position);

        holder.artistNameTextView.setText(artistItem.getArtistName());
        holder.songCountTextView.setText(artistItem.getFormattedSongCount());
        holder.durationTextView.setText(formatDuration(artistItem.getTotalDuration()));


        holder.itemView.setOnClickListener(v -> {
            if (listener != null) {
                listener.onArtistItemClick(artistItem);
            }
        });

        holder.playButton.setOnClickListener(v -> {
            if (listener != null) {
                listener.onPlayButtonClick(artistItem);
            }
        });
    }

    @Override
    public int getItemCount() {
        return artistList.size();
    }

    private String formatDuration(long durationMs) {
        if (durationMs <= 0) return "0m";

        long totalMinutes = durationMs / (1000 * 60);

        if (totalMinutes < 60) {
            return totalMinutes + "m";
        } else {
            long hours = totalMinutes / 60;
            long minutes = totalMinutes % 60;

            if (minutes == 0) {
                return hours + "h";
            } else {
                return String.format(Locale.getDefault(), "%dh %dm", hours, minutes);
            }
        }
    }

    public static class ArtistViewHolder extends RecyclerView.ViewHolder {
        MaterialCardView cardView;
        TextView artistNameTextView;
        TextView songCountTextView;
        TextView durationTextView;
        MaterialButton playButton;

        public ArtistViewHolder(@NonNull View itemView) {
            super(itemView);
            cardView = itemView.findViewById(R.id.artist_card);
            artistNameTextView = itemView.findViewById(R.id.artist_name);
            songCountTextView = itemView.findViewById(R.id.song_count);
            durationTextView = itemView.findViewById(R.id.duration);
            playButton = itemView.findViewById(R.id.play_button);
        }
    }
}
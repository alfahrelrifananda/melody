package com.alfahrel.melody.widget;

import android.content.Context;
import android.net.Uri;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.alfahrel.melody.R;

import java.util.ArrayList;
import java.util.List;

/**
 * RecyclerView adapter for {@link WidgetConfigActivity}.
 *
 * Displays a mixed list of Collections and Albums. Each row shows:
 *   • Cover art thumbnail
 *   • Source name  (collection name / album title)
 *   • Subtitle     ("N songs" / "Artist • N songs")
 *   • A small badge indicating the source type ("Collection" / "Album")
 *
 * Tapping any row fires {@link OnSourceSelectedListener#onSourceSelected(WidgetSource)}.
 */
public class WidgetConfigAdapter extends RecyclerView.Adapter<WidgetConfigAdapter.ViewHolder> {

    // -------------------------------------------------------------------------
    // Listener interface
    // -------------------------------------------------------------------------

    public interface OnSourceSelectedListener {
        void onSourceSelected(WidgetSource source);
    }

    // -------------------------------------------------------------------------
    // Fields
    // -------------------------------------------------------------------------

    private final List<WidgetSource>       items    = new ArrayList<>();
    private final OnSourceSelectedListener listener;

    // -------------------------------------------------------------------------
    // Constructor
    // -------------------------------------------------------------------------

    public WidgetConfigAdapter(OnSourceSelectedListener listener) {
        this.listener = listener;
    }

    // -------------------------------------------------------------------------
    // Data
    // -------------------------------------------------------------------------

    public void setData(List<WidgetSource> sources) {
        items.clear();
        if (sources != null) items.addAll(sources);
        notifyDataSetChanged();
    }

    // -------------------------------------------------------------------------
    // RecyclerView.Adapter overrides
    // -------------------------------------------------------------------------

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_widget_source, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        holder.bind(items.get(position), listener);
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    // -------------------------------------------------------------------------
    // ViewHolder
    // -------------------------------------------------------------------------

    static class ViewHolder extends RecyclerView.ViewHolder {

        private final ImageView coverImage;
        private final TextView  nameText;
        private final TextView  subtitleText;
        private final TextView  typeBadge;

        ViewHolder(@NonNull View itemView) {
            super(itemView);
            coverImage   = itemView.findViewById(R.id.item_source_cover);
            nameText     = itemView.findViewById(R.id.item_source_name);
            subtitleText = itemView.findViewById(R.id.item_source_subtitle);
            typeBadge    = itemView.findViewById(R.id.item_source_type_badge);
        }

        void bind(WidgetSource source, OnSourceSelectedListener listener) {
            Context ctx = itemView.getContext();

            nameText.setText(source.name);
            subtitleText.setText(source.subtitle);

            // Badge label
            if (MelodyWidget.TYPE_COLLECTION.equals(source.type)) {
                typeBadge.setText(ctx.getString(R.string.widget_badge_collection));
            } else {
                typeBadge.setText(ctx.getString(R.string.widget_badge_album));
            }

            // Cover art
            if (source.coverUri != null && !source.coverUri.isEmpty()) {
                Glide.with(ctx)
                        .load(Uri.parse(source.coverUri))
                        .placeholder(R.drawable.ic_outline_music_note_24)
                        .error(R.drawable.ic_outline_music_note_24)
                        .centerCrop()
                        .into(coverImage);
            } else {
                coverImage.setImageResource(R.drawable.ic_outline_music_note_24);
            }

            // Click
            itemView.setOnClickListener(v -> {
                if (listener != null) listener.onSourceSelected(source);
            });
        }
    }
}
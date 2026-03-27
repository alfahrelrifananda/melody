package com.alfahrel.melody.utils;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.alfahrel.melody.R;
import com.bumptech.glide.Glide;

import java.util.ArrayList;
import java.util.List;

public class PinnedStripAdapter extends RecyclerView.Adapter<PinnedStripAdapter.ViewHolder> {

    public interface OnPinnedItemClickListener {
        void onPinnedItemClick(PinnedItem item);
    }

    private List<PinnedItem> allItems   = new ArrayList<>();
    private List<PinnedItem> shownItems = new ArrayList<>();
    private PinnedItem.Type  activeFilter = null; // null = All
    private OnPinnedItemClickListener listener;

    public void setOnPinnedItemClickListener(OnPinnedItemClickListener listener) {
        this.listener = listener;
    }

    public void setItems(List<PinnedItem> items) {
        this.allItems = items != null ? items : new ArrayList<>();
        applyFilter(activeFilter);
    }

    public void setFilter(PinnedItem.Type type) {
        this.activeFilter = type;
        applyFilter(type);
    }

    public PinnedItem.Type getActiveFilter() {
        return activeFilter;
    }

    private void applyFilter(PinnedItem.Type type) {
        shownItems.clear();
        for (PinnedItem item : allItems) {
            if (type == null || item.getType() == type) {
                shownItems.add(item);
            }
        }
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_collection_strip, parent, false);
        return new ViewHolder(v);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        PinnedItem item = shownItems.get(position);

        holder.name.setText(item.getName());
        holder.subtitle.setText(item.getSubtitle());

        if (item.getCoverUri() != null) {
            holder.placeholder.setVisibility(View.GONE);
            holder.cover.setVisibility(View.VISIBLE);
            Glide.with(holder.itemView.getContext())
                    .load(item.getCoverUri())
                    .centerCrop()
                    .placeholder(R.drawable.ic_outline_music_note_24)
                    .error(R.drawable.ic_outline_music_note_24)
                    .into(holder.cover);
        } else {
            holder.cover.setVisibility(View.INVISIBLE);
            holder.placeholder.setVisibility(View.VISIBLE);
        }

        holder.itemView.setOnClickListener(v -> {
            if (listener != null) listener.onPinnedItemClick(item);
        });
        holder.itemView.setOnLongClickListener(v -> {
            if (longClickListener != null) longClickListener.onPinnedItemLongClick(item);
            return true;
        });
    }

    @Override
    public int getItemCount() {
        return shownItems.size();
    }

    public interface OnPinnedItemLongClickListener {
        void onPinnedItemLongClick(PinnedItem item);
    }

    private OnPinnedItemLongClickListener longClickListener;

    public void setOnPinnedItemLongClickListener(OnPinnedItemLongClickListener listener) {
        this.longClickListener = listener;
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        ImageView cover;
        View      placeholder;
        TextView  name;
        TextView  subtitle;

        ViewHolder(@NonNull View itemView) {
            super(itemView);
            cover       = itemView.findViewById(R.id.stripCollectionCover);
            placeholder = itemView.findViewById(R.id.stripCoverPlaceholder);
            name        = itemView.findViewById(R.id.stripCollectionName);
            subtitle    = itemView.findViewById(R.id.stripCollectionCount);
        }
    }
}
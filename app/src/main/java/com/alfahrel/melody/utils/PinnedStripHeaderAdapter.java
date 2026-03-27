package com.alfahrel.melody.utils;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.alfahrel.melody.R;
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;

import java.util.ArrayList;
import java.util.List;

public class PinnedStripHeaderAdapter extends RecyclerView.Adapter<PinnedStripHeaderAdapter.HeaderViewHolder> {

    private List<PinnedItem> pinnedItems = new ArrayList<>();
    private PinnedStripAdapter.OnPinnedItemClickListener clickListener;
    private PinnedStripAdapter.OnPinnedItemLongClickListener longClickListener;

    public PinnedStripHeaderAdapter(
            PinnedStripAdapter.OnPinnedItemClickListener clickListener,
            PinnedStripAdapter.OnPinnedItemLongClickListener longClickListener) {
        this.clickListener = clickListener;
        this.longClickListener = longClickListener;
    }

    public void updateItems(List<PinnedItem> items) {
        boolean hadItems = hasItems();
        this.pinnedItems = items != null ? items : new ArrayList<>();
        boolean hasNow = hasItems();

        if (!hadItems && hasNow) notifyItemInserted(0);
        else if (hadItems && !hasNow) notifyItemRemoved(0);
        else if (hadItems) notifyItemChanged(0);
    }

    public boolean hasItems() {
        return pinnedItems != null && !pinnedItems.isEmpty();
    }

    @Override
    public int getItemCount() {
        return hasItems() ? 1 : 0;
    }

    @NonNull
    @Override
    public HeaderViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_pinned_strip_header, parent, false);
        return new HeaderViewHolder(v);
    }

    @Override
    public void onBindViewHolder(@NonNull HeaderViewHolder holder, int position) {
        holder.bind(pinnedItems, clickListener, longClickListener);
    }

    static class HeaderViewHolder extends RecyclerView.ViewHolder {
        private final ChipGroup chipGroup;
        private final RecyclerView stripRv;
        private PinnedStripAdapter stripAdapter;

        HeaderViewHolder(@NonNull View itemView) {
            super(itemView);
            chipGroup = itemView.findViewById(R.id.pinnedChipGroup);
            stripRv   = itemView.findViewById(R.id.pinnedStripRecyclerView);
        }

        void bind(List<PinnedItem> items,
                  PinnedStripAdapter.OnPinnedItemClickListener clickListener,
                  PinnedStripAdapter.OnPinnedItemLongClickListener longClickListener) {

            stripAdapter = new PinnedStripAdapter();
            stripAdapter.setOnPinnedItemClickListener(clickListener);
            stripAdapter.setItems(items);
            stripAdapter.setOnPinnedItemLongClickListener(longClickListener);
            stripRv.setLayoutManager(new GridLayoutManager(itemView.getContext(), 2));
            stripRv.setAdapter(stripAdapter);

            // Build chips dynamically based on which types are present
            chipGroup.removeAllViews();
            addChip("All", null, true);

            boolean hasCollections = false, hasAlbums = false, hasArtists = false, hasSongs = false;
            for (PinnedItem p : items) {
                switch (p.getType()) {
                    case COLLECTION: hasCollections = true; break;
                    case ALBUM:      hasAlbums      = true; break;
                    case ARTIST:     hasArtists     = true; break;
                    case SONG:       hasSongs       = true; break;
                }
            }
            if (hasCollections) addChip("Collections", PinnedItem.Type.COLLECTION, false);
            if (hasAlbums)      addChip("Albums",      PinnedItem.Type.ALBUM,      false);
            if (hasArtists)     addChip("Artists",     PinnedItem.Type.ARTIST,     false);
            if (hasSongs)       addChip("Songs",       PinnedItem.Type.SONG,       false);
        }

        private void addChip(String label, PinnedItem.Type type, boolean checked) {
            Chip chip = new Chip(itemView.getContext());
            chip.setText(label);
            chip.setCheckable(true);
            chip.setChecked(checked);
            chip.setChipBackgroundColorResource(com.google.android.material.R.color.m3_chip_background_color);
            chip.setOnClickListener(v -> {
                for (int i = 0; i < chipGroup.getChildCount(); i++) {
                    ((Chip) chipGroup.getChildAt(i)).setChecked(false);
                }
                chip.setChecked(true);
                stripAdapter.setFilter(type);
            });
            chipGroup.addView(chip);
        }
    }
}
package com.alfahrel.melody.ui.pages.nowplaying;

import android.net.Uri;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.alfahrel.melody.R;
import com.alfahrel.melody.ui.collection.Collection;
import com.alfahrel.melody.ui.collection.CollectionManager;
import com.bumptech.glide.Glide;
import com.google.android.material.card.MaterialCardView;

import java.util.List;

public class AddToCollectionAdapter extends RecyclerView.Adapter<AddToCollectionAdapter.CollectionViewHolder> {

    private List<Collection> collections;
    private long currentSongId;
    private CollectionManager collectionManager;
    private OnCollectionClickListener clickListener;

    public interface OnCollectionClickListener {
        void onCollectionClick(Collection collection);
    }

    public AddToCollectionAdapter(List<Collection> collections, long currentSongId,
                                  CollectionManager collectionManager,
                                  OnCollectionClickListener clickListener) {
        this.collections = collections;
        this.currentSongId = currentSongId;
        this.collectionManager = collectionManager;
        this.clickListener = clickListener;
    }

    @NonNull
    @Override
    public CollectionViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_collection_picker, parent, false);
        return new CollectionViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull CollectionViewHolder holder, int position) {
        holder.bind(collections.get(position));
    }

    @Override
    public int getItemCount() {
        return collections.size();
    }

    class CollectionViewHolder extends RecyclerView.ViewHolder {

        private final ImageView cover;
        private final View      coverPlaceholder;
        private final TextView  collectionName;
        private final TextView  songCount;
        private final ImageView checkIcon;

        CollectionViewHolder(@NonNull View itemView) {
            super(itemView);
            cover            = itemView.findViewById(R.id.itemCollectionCover);
            coverPlaceholder = itemView.findViewById(R.id.itemCoverPlaceholder);
            collectionName   = itemView.findViewById(R.id.itemCollectionName);
            songCount        = itemView.findViewById(R.id.itemCollectionSongCount);
            checkIcon        = itemView.findViewById(R.id.itemCollectionCheck);
        }

        void bind(Collection collection) {
            collectionName.setText(collection.getName());

            int count = collection.getMusicIds() != null
                    ? collection.getMusicIds().size() : 0;
            songCount.setText(count + (count == 1 ? " song" : " songs"));

            // Cover image
            String uri = collection.getCoverImageUri();
            if (uri != null && !uri.isEmpty()) {
                cover.setVisibility(View.VISIBLE);
                coverPlaceholder.setVisibility(View.GONE);
                Glide.with(itemView.getContext())
                        .load(Uri.parse(uri))
                        .centerCrop()
                        .placeholder(R.drawable.ic_outline_music_note_24)
                        .into(cover);
            } else {
                cover.setVisibility(View.GONE);
                coverPlaceholder.setVisibility(View.VISIBLE);
                Glide.with(itemView.getContext()).clear(cover);
            }

            boolean isInCollection = collectionManager.isSongInCollection(
                    collection.getId(), currentSongId);

            checkIcon.setVisibility(isInCollection ? View.VISIBLE : View.GONE);
            itemView.setAlpha(isInCollection ? 0.5f : 1.0f);

            itemView.setOnClickListener(v -> {
                if (!isInCollection && clickListener != null) {
                    clickListener.onCollectionClick(collection);
                }
            });
        }
    }
}
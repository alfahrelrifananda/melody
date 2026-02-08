package com.alfahrel.melody.ui.pages.nowplaying;


import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.recyclerview.widget.RecyclerView;

import com.alfahrel.melody.R;
import com.alfahrel.melody.ui.collection.Collection;
import com.alfahrel.melody.ui.collection.CollectionManager;

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

    @Override
    public CollectionViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_collection_select, parent, false);
        return new CollectionViewHolder(view);
    }

    @Override
    public void onBindViewHolder(CollectionViewHolder holder, int position) {
        Collection collection = collections.get(position);
        holder.bind(collection);
    }

    @Override
    public int getItemCount() {
        return collections.size();
    }

    class CollectionViewHolder extends RecyclerView.ViewHolder {

        private android.widget.ImageView collectionIcon;
        private android.widget.TextView collectionName;
        private android.widget.TextView songCount;
        private android.widget.ImageView checkIcon;
        private com.google.android.material.card.MaterialCardView cardView;

        public CollectionViewHolder(View itemView) {
            super(itemView);
            cardView = itemView.findViewById(R.id.collectionSelectCard);
            collectionIcon = itemView.findViewById(R.id.collectionIcon);
            collectionName = itemView.findViewById(R.id.collectionName);
            songCount = itemView.findViewById(R.id.songCount);
            checkIcon = itemView.findViewById(R.id.checkIcon);
        }

        void bind(Collection collection) {
            collectionName.setText(collection.getName());
            int count = collection.getSongCount();
            songCount.setText(count + (count == 1 ? " song" : " songs"));

            boolean isInCollection = collectionManager.isSongInCollection(
                    collection.getId(), currentSongId);

            if (isInCollection) {
                checkIcon.setVisibility(View.VISIBLE);
                cardView.setAlpha(0.6f);
            } else {
                checkIcon.setVisibility(View.GONE);
                cardView.setAlpha(1.0f);
            }

            cardView.setOnClickListener(v -> {
                if (!isInCollection && clickListener != null) {
                    clickListener.onCollectionClick(collection);
                }
            });
        }
    }
}
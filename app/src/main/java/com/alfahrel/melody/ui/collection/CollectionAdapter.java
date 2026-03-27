package com.alfahrel.melody.ui.collection;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.alfahrel.melody.R;
import com.bumptech.glide.Glide;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;

import java.util.ArrayList;
import java.util.List;

public class CollectionAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    private static final int VIEW_TYPE_COLLECTION = 0;
    private static final int VIEW_TYPE_ADD_BUTTON = 1;

    private List<Collection> collections;
    private Context context;
    private OnCollectionClickListener clickListener;
    private OnCollectionLongClickListener longClickListener;
    private OnAddCollectionClickListener addClickListener;

    public interface OnCollectionClickListener {
        void onCollectionClick(Collection collection);
    }

    public interface OnCollectionLongClickListener {
        void onCollectionLongClick(Collection collection);
    }

    public interface OnAddCollectionClickListener {
        void onAddCollectionClick();
    }

    public CollectionAdapter(List<Collection> collections, Context context) {
        this.collections = collections != null ? collections : new ArrayList<>();
        this.context = context;
    }

    public CollectionAdapter(List<Collection> collections,
                             OnCollectionClickListener clickListener,
                             OnCollectionLongClickListener longClickListener) {
        this.collections = collections != null ? collections : new ArrayList<>();
        this.clickListener = clickListener;
        this.longClickListener = longClickListener;
    }

    public CollectionAdapter(List<Collection> collections,
                             Context context,
                             OnCollectionClickListener clickListener,
                             OnCollectionLongClickListener longClickListener) {
        this.collections = collections != null ? collections : new ArrayList<>();
        this.context = context;
        this.clickListener = clickListener;
        this.longClickListener = longClickListener;
    }

    public void setOnCollectionClickListener(OnCollectionClickListener l)    { this.clickListener = l; }
    public void setOnCollectionLongClickListener(OnCollectionLongClickListener l) { this.longClickListener = l; }
    public void setOnAddCollectionClickListener(OnAddCollectionClickListener l)   { this.addClickListener = l; }

    @Override
    public int getItemViewType(int position) {
        return position < collections.size() ? VIEW_TYPE_COLLECTION : VIEW_TYPE_ADD_BUTTON;
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        if (context == null) context = parent.getContext();
        if (viewType == VIEW_TYPE_COLLECTION) {
            View v = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_collection, parent, false);
            return new CollectionViewHolder(v);
        } else {
            View v = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_add_collection, parent, false);
            return new AddButtonViewHolder(v);
        }
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        if (holder instanceof CollectionViewHolder) {
            ((CollectionViewHolder) holder).bind(collections.get(position));
        } else if (holder instanceof AddButtonViewHolder) {
            ((AddButtonViewHolder) holder).bind();
        }
    }

    @Override
    public int getItemCount() {
        return collections.isEmpty() ? 0 : collections.size() + 1;
    }

    public void updateCollections(List<Collection> newCollections) {
        this.collections = newCollections != null ? newCollections : new ArrayList<>();
        notifyDataSetChanged();
    }

    class CollectionViewHolder extends RecyclerView.ViewHolder {
        private final MaterialCardView cardView;
        private final TextView textName;
        private final TextView textSongCount;
        private final ImageView imageCoverThumb;
        private final View coverPlaceholder;
        private final ImageButton btnMenu;

        CollectionViewHolder(@NonNull View itemView) {
            super(itemView);
            cardView         = itemView.findViewById(R.id.collectionCard);
            textName         = itemView.findViewById(R.id.textCollectionName);
            textSongCount    = itemView.findViewById(R.id.textSongCount);
            imageCoverThumb  = itemView.findViewById(R.id.imageCoverThumb);
            coverPlaceholder = itemView.findViewById(R.id.coverPlaceholder);
            btnMenu          = itemView.findViewById(R.id.btnCollectionMenu); // 👈 add this
        }

        void bind(Collection collection) {
            textName.setText(collection.getName());

            int count = collection.getMusicIds() != null ? collection.getMusicIds().size() : 0;
            textSongCount.setText(count + (count == 1 ? " song" : " songs"));

            String uri = collection.getCoverImageUri();
            if (uri != null && !uri.isEmpty()) {
                imageCoverThumb.setVisibility(View.VISIBLE);
                if (coverPlaceholder != null) coverPlaceholder.setVisibility(View.GONE);
                Glide.with(itemView.getContext())
                        .load(Uri.parse(uri))
                        .centerCrop()
                        .placeholder(R.drawable.ic_outline_music_note_24)
                        .error(R.drawable.ic_outline_music_note_24)
                        .into(imageCoverThumb);
            } else {
                imageCoverThumb.setVisibility(View.INVISIBLE);
                Glide.with(itemView.getContext()).clear(imageCoverThumb);
                if (coverPlaceholder != null) coverPlaceholder.setVisibility(View.VISIBLE);
            }

            cardView.setOnClickListener(v -> {
                if (clickListener != null) clickListener.onCollectionClick(collection);
            });

            btnMenu.setOnClickListener(v -> {
                if (longClickListener != null) longClickListener.onCollectionLongClick(collection);
            });
        }
    }

    class AddButtonViewHolder extends RecyclerView.ViewHolder {
        private final MaterialButton addButton;

        AddButtonViewHolder(@NonNull View itemView) {
            super(itemView);
            addButton = itemView.findViewById(R.id.btnAddCollection);
        }

        void bind() {
            addButton.setOnClickListener(v -> {
                if (addClickListener != null) addClickListener.onAddCollectionClick();
            });
        }
    }
}
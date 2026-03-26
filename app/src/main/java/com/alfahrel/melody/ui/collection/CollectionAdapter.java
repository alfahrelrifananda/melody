package com.alfahrel.melody.ui.collection;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.alfahrel.melody.R;
import com.bumptech.glide.Glide;
import com.bumptech.glide.load.resource.bitmap.RoundedCorners;
import com.bumptech.glide.request.RequestOptions;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.imageview.ShapeableImageView;

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

    public void setOnCollectionClickListener(OnCollectionClickListener listener) {
        this.clickListener = listener;
    }

    public void setOnCollectionLongClickListener(OnCollectionLongClickListener listener) {
        this.longClickListener = listener;
    }

    public void setOnAddCollectionClickListener(OnAddCollectionClickListener listener) {
        this.addClickListener = listener;
    }

    @Override
    public int getItemViewType(int position) {
        return position < collections.size() ? VIEW_TYPE_COLLECTION : VIEW_TYPE_ADD_BUTTON;
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        if (context == null) context = parent.getContext();

        if (viewType == VIEW_TYPE_COLLECTION) {
            View view = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_collection, parent, false);
            return new CollectionViewHolder(view);
        } else {
            View view = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_add_collection, parent, false);
            return new AddButtonViewHolder(view);
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

    // ─── CollectionViewHolder ────────────────────────────────────────────────

    class CollectionViewHolder extends RecyclerView.ViewHolder {
        private final MaterialCardView cardView;
        private final TextView textName;
        private final TextView textSongCount;
        private final ShapeableImageView imageCoverThumb; // NEW

        public CollectionViewHolder(@NonNull View itemView) {
            super(itemView);
            cardView       = itemView.findViewById(R.id.collectionCard);
            textName       = itemView.findViewById(R.id.textCollectionName);
            textSongCount  = itemView.findViewById(R.id.textSongCount);
            imageCoverThumb = itemView.findViewById(R.id.imageCoverThumb); // NEW
        }

        public void bind(Collection collection) {
            textName.setText(collection.getName());

            int count = collection.getMusicIds() != null ? collection.getMusicIds().size() : 0;
            textSongCount.setText(count + (count == 1 ? " song" : " songs"));

            // NEW: load cover image with Glide, fall back to music note icon
            if (imageCoverThumb != null) {
                String uri = collection.getCoverImageUri();
                if (uri != null && !uri.isEmpty()) {
                    imageCoverThumb.setPadding(0, 0, 0, 0);
                    imageCoverThumb.clearColorFilter();
                    Glide.with(imageCoverThumb.getContext())
                            .load(Uri.parse(uri))
                            .apply(new RequestOptions().centerCrop())
                            .placeholder(R.drawable.ic_music_note_24)
                            .error(R.drawable.ic_music_note_24)
                            .into(imageCoverThumb);
                } else {
                    // Reset to placeholder state
                    int padding = (int) (12 * itemView.getContext().getResources().getDisplayMetrics().density);
                    imageCoverThumb.setPadding(padding, padding, padding, padding);
                    Glide.with(imageCoverThumb.getContext()).clear(imageCoverThumb);
                    imageCoverThumb.setImageResource(R.drawable.ic_music_note_24);
                    imageCoverThumb.setColorFilter(
                            androidx.core.content.ContextCompat.getColor(
                                    itemView.getContext(),
                                    com.google.android.material.R.color.material_on_surface_emphasis_medium
                            )
                    );
                }
            }

            cardView.setOnClickListener(v -> {
                if (clickListener != null) {
                    clickListener.onCollectionClick(collection);
                } else if (context != null) {
                    Intent intent = new Intent(context, CollectionDetailActivity.class);
                    intent.putExtra("collection", collection);
                    context.startActivity(intent);
                }
            });

            cardView.setOnLongClickListener(v -> {
                if (longClickListener != null) {
                    longClickListener.onCollectionLongClick(collection);
                    return true;
                }
                return false;
            });
        }
    }

    // ─── AddButtonViewHolder ─────────────────────────────────────────────────

    class AddButtonViewHolder extends RecyclerView.ViewHolder {
        private final MaterialButton addButton;

        public AddButtonViewHolder(@NonNull View itemView) {
            super(itemView);
            addButton = itemView.findViewById(R.id.btnAddCollection);
        }

        public void bind() {
            addButton.setOnClickListener(v -> {
                if (addClickListener != null) addClickListener.onAddCollectionClick();
            });
        }
    }
}
package com.alfahrel.melody.ui.home;

import android.content.Context;
import android.content.SharedPreferences;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.alfahrel.melody.R;
import com.alfahrel.melody.ui.album.AlbumFragment;
import com.alfahrel.melody.ui.album.AlbumItem;
import com.alfahrel.melody.utils.GsonHelper;
import com.bumptech.glide.Glide;
import com.google.gson.reflect.TypeToken;

import java.lang.reflect.Type;
import java.util.List;

/**
 * Section adapter for Albums on the Home screen.
 * – Renders a header row + horizontal RecyclerView of album cards.
 * – Each card uses item_album.xml (same as AllAlbumsActivity).
 * – Long-press toggles the pin state and persists it to SharedPreferences.
 */
public class HomeAlbumsAdapter extends RecyclerView.Adapter<HomeAlbumsAdapter.SectionViewHolder> {

    public interface OnAlbumClickListener  { void onAlbumClick(AlbumItem album); }
    public interface OnSeeAllClickListener { void onSeeAll(); }

    private final List<AlbumItem>       albums;
    private final OnAlbumClickListener  onAlbumClick;
    private final OnSeeAllClickListener onSeeAll;

    // Keep a reference so we can push updates to the inner RV directly.
    private AlbumCardAdapter innerAdapter;

    public HomeAlbumsAdapter(List<AlbumItem> albums,
                             OnAlbumClickListener onAlbumClick,
                             OnSeeAllClickListener onSeeAll) {
        this.albums       = albums;
        this.onAlbumClick = onAlbumClick;
        this.onSeeAll     = onSeeAll;
    }

    /**
     * Call this after the data list has been updated.
     * Updates the inner card adapter directly so visibility is re-evaluated on every refresh.
     */
    public void updateData() {
        if (innerAdapter != null) {
            innerAdapter.notifyDataSetChanged();
        }
        notifyDataSetChanged();
    }

    @Override public int getItemCount() { return 1; }

    @NonNull
    @Override
    public SectionViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.home_section_albums, parent, false);
        return new SectionViewHolder(v);
    }

    @Override
    public void onBindViewHolder(@NonNull SectionViewHolder holder, int position) {
        if (albums.isEmpty()) {
            holder.itemView.setVisibility(View.GONE);
            return;
        }
        holder.itemView.setVisibility(View.VISIBLE);

        if (innerAdapter == null) {
            innerAdapter = new AlbumCardAdapter(albums, onAlbumClick, this::togglePin);
            holder.innerRv.setLayoutManager(
                    new LinearLayoutManager(holder.itemView.getContext(),
                            LinearLayoutManager.HORIZONTAL, false));
            holder.innerRv.setAdapter(innerAdapter);
            holder.innerRv.setNestedScrollingEnabled(false);
        } else {
            innerAdapter.notifyDataSetChanged();
            if (holder.innerRv.getAdapter() == null) {
                holder.innerRv.setLayoutManager(
                        new LinearLayoutManager(holder.itemView.getContext(),
                                LinearLayoutManager.HORIZONTAL, false));
                holder.innerRv.setAdapter(innerAdapter);
                holder.innerRv.setNestedScrollingEnabled(false);
            }
        }

        holder.btnSeeAll.setOnClickListener(v -> onSeeAll.onSeeAll());
    }

    // ── Pin toggle ────────────────────────────────────────────────────────────

    private void togglePin(AlbumItem album, Context context) {
        // Flip pin state on the in-memory item
        boolean nowPinned = !album.isPinned();
        album.setPinned(nowPinned);

        // Persist the change into the same SharedPreferences that AlbumFragment uses
        SharedPreferences prefs = context.getSharedPreferences(
                AlbumFragment.PREFS_NAME, Context.MODE_PRIVATE);
        String json = prefs.getString(AlbumFragment.KEY_ALBUMS, null);

        if (json != null) {
            Type type = new TypeToken<List<AlbumItem>>() {}.getType();
            List<AlbumItem> saved = GsonHelper.get().fromJson(json, type);
            if (saved != null) {
                for (AlbumItem a : saved) {
                    if (a.getAlbumId() == album.getAlbumId()) {
                        a.setPinned(nowPinned);
                        break;
                    }
                }
                prefs.edit()
                        .putString(AlbumFragment.KEY_ALBUMS, GsonHelper.get().toJson(saved))
                        .apply();
            }
        } else {
            // No saved list yet — persist just the current home list
            prefs.edit()
                    .putString(AlbumFragment.KEY_ALBUMS, GsonHelper.get().toJson(albums))
                    .apply();
        }

        // Notify the rest of the app (HomeFragment's stripUpdateReceiver picks this up)
        android.content.Intent intent = new android.content.Intent("com.alfahrel.melody.ALBUM_CHANGED");
        intent.setPackage(context.getPackageName());
        context.sendBroadcast(intent);

        Toast.makeText(context,
                nowPinned ? "\"" + album.getAlbumName() + "\" pinned to Home"
                        : "\"" + album.getAlbumName() + "\" unpinned from Home",
                Toast.LENGTH_SHORT).show();

        if (innerAdapter != null) innerAdapter.notifyDataSetChanged();
    }

    // ── Inner card adapter ────────────────────────────────────────────────────

    static class AlbumCardAdapter extends RecyclerView.Adapter<AlbumCardAdapter.CardVH> {

        interface OnAlbumLongPress { void onLongPress(AlbumItem album, Context context); }

        private final List<AlbumItem>      items;
        private final OnAlbumClickListener clickListener;
        private final OnAlbumLongPress     longPressListener;

        AlbumCardAdapter(List<AlbumItem> items,
                         OnAlbumClickListener clickListener,
                         OnAlbumLongPress longPressListener) {
            this.items             = items;
            this.clickListener     = clickListener;
            this.longPressListener = longPressListener;
        }

        @Override public int getItemCount() { return items.size(); }

        @NonNull
        @Override
        public CardVH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_album, parent, false);
            float density = parent.getContext().getResources().getDisplayMetrics().density;
            int widthPx  = (int) (185 * density);
            int marginPx = (int) (2 * density);
            RecyclerView.LayoutParams lp = new RecyclerView.LayoutParams(widthPx, RecyclerView.LayoutParams.WRAP_CONTENT);
            lp.setMargins(marginPx, 0, marginPx, 0);
            v.setLayoutParams(lp);
            return new CardVH(v);
        }

        @Override
        public void onBindViewHolder(@NonNull CardVH holder, int position) {
            AlbumItem album = items.get(position);

            Glide.with(holder.itemView.getContext())
                    .load(album.getAlbumArtUri())
                    .placeholder(R.drawable.ic_outline_music_note_24)
                    .error(R.drawable.ic_outline_music_note_24)
                    .into(holder.art);

            holder.name.setText(album.getAlbumName());
            holder.artist.setText(album.getArtistName());

            int count = album.getSongCount();
            holder.count.setText(count + (count == 1 ? " song" : " songs"));

            holder.itemView.setOnClickListener(v -> clickListener.onAlbumClick(album));
            holder.itemView.setOnLongClickListener(v -> {
                longPressListener.onLongPress(album, v.getContext());
                return true;
            });
        }

        static class CardVH extends RecyclerView.ViewHolder {
            final ImageView art;
            final TextView  name, artist, count;

            CardVH(@NonNull View v) {
                super(v);
                art    = v.findViewById(R.id.album_art_image);
                name   = v.findViewById(R.id.album_name);
                artist = v.findViewById(R.id.artist_name);
                count  = v.findViewById(R.id.song_count);
            }
        }
    }

    // ── Section view holder ───────────────────────────────────────────────────

    static class SectionViewHolder extends RecyclerView.ViewHolder {
        final RecyclerView innerRv;
        final TextView     btnSeeAll;

        SectionViewHolder(@NonNull View v) {
            super(v);
            innerRv   = v.findViewById(R.id.albumsHorizontalRv);
            btnSeeAll = v.findViewById(R.id.btnSeeAllAlbums);
        }
    }
}
package com.alfahrel.melody.ui.home;

import android.content.Context;
import android.content.SharedPreferences;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.alfahrel.melody.R;
import com.alfahrel.melody.ui.artist.ArtistFragment;
import com.alfahrel.melody.ui.artist.ArtistItem;
import com.alfahrel.melody.utils.GsonHelper;
import com.google.gson.reflect.TypeToken;

import java.lang.reflect.Type;
import java.util.List;

/**
 * Section adapter for Artists on the Home screen.
 * – Renders a header row + vertical RecyclerView of artist rows.
 * – Each row uses item_artist.xml (same as AllArtistsActivity).
 * – Long-press toggles the pin state and persists it to SharedPreferences.
 */
public class HomeArtistsAdapter extends RecyclerView.Adapter<HomeArtistsAdapter.SectionViewHolder> {

    public interface OnArtistClickListener { void onArtistClick(ArtistItem artist); }
    public interface OnSeeAllClickListener { void onSeeAll(); }

    private final List<ArtistItem>      artists;
    private final OnArtistClickListener onArtistClick;
    private final OnSeeAllClickListener onSeeAll;

    private ArtistCardAdapter innerAdapter;

    public HomeArtistsAdapter(List<ArtistItem> artists,
                              OnArtistClickListener onArtistClick,
                              OnSeeAllClickListener onSeeAll) {
        this.artists       = artists;
        this.onArtistClick = onArtistClick;
        this.onSeeAll      = onSeeAll;
    }

    public void updateData() {
        if (innerAdapter != null) innerAdapter.notifyDataSetChanged();
        notifyDataSetChanged();
    }

    @Override public int getItemCount() { return 1; }

    @NonNull
    @Override
    public SectionViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.home_section_artists, parent, false);
        return new SectionViewHolder(v);
    }

    @Override
    public void onBindViewHolder(@NonNull SectionViewHolder holder, int position) {
        if (artists.isEmpty()) {
            holder.itemView.setVisibility(View.GONE);
            return;
        }
        holder.itemView.setVisibility(View.VISIBLE);

        if (innerAdapter == null) {
            innerAdapter = new ArtistCardAdapter(artists, onArtistClick, this::togglePin);
            // Vertical, non-scrolling — the outer RecyclerView handles scrolling
            holder.innerRv.setLayoutManager(new LinearLayoutManager(holder.itemView.getContext()));
            holder.innerRv.setAdapter(innerAdapter);
            holder.innerRv.setNestedScrollingEnabled(false);
        } else {
            innerAdapter.notifyDataSetChanged();
            if (holder.innerRv.getAdapter() == null) {
                holder.innerRv.setLayoutManager(new LinearLayoutManager(holder.itemView.getContext()));
                holder.innerRv.setAdapter(innerAdapter);
                holder.innerRv.setNestedScrollingEnabled(false);
            }
        }

        holder.btnSeeAll.setOnClickListener(v -> onSeeAll.onSeeAll());
    }

    // ── Pin toggle ────────────────────────────────────────────────────────────

    private void togglePin(ArtistItem artist, Context context) {
        boolean nowPinned = !artist.isPinned();
        artist.setPinned(nowPinned);

        SharedPreferences prefs = context.getSharedPreferences(
                ArtistFragment.PREFS_NAME, Context.MODE_PRIVATE);
        String json = prefs.getString(ArtistFragment.KEY_ARTISTS, null);

        if (json != null) {
            Type type = new TypeToken<List<ArtistItem>>() {}.getType();
            List<ArtistItem> saved = GsonHelper.get().fromJson(json, type);
            if (saved != null) {
                for (ArtistItem a : saved) {
                    if (a.getArtistName().equals(artist.getArtistName())) {
                        a.setPinned(nowPinned);
                        break;
                    }
                }
                prefs.edit()
                        .putString(ArtistFragment.KEY_ARTISTS, GsonHelper.get().toJson(saved))
                        .apply();
            }
        } else {
            prefs.edit()
                    .putString(ArtistFragment.KEY_ARTISTS, GsonHelper.get().toJson(artists))
                    .apply();
        }

        android.content.Intent intent = new android.content.Intent("com.alfahrel.melody.ARTIST_CHANGED");
        intent.setPackage(context.getPackageName());
        context.sendBroadcast(intent);

        Toast.makeText(context,
                nowPinned ? "\"" + artist.getArtistName() + "\" pinned to Home"
                        : "\"" + artist.getArtistName() + "\" unpinned from Home",
                Toast.LENGTH_SHORT).show();

        if (innerAdapter != null) innerAdapter.notifyDataSetChanged();
    }

    // ── Inner card adapter ────────────────────────────────────────────────────

    static class ArtistCardAdapter extends RecyclerView.Adapter<ArtistCardAdapter.CardVH> {

        interface OnArtistLongPress { void onLongPress(ArtistItem artist, Context context); }

        private final List<ArtistItem>      items;
        private final OnArtistClickListener clickListener;
        private final OnArtistLongPress     longPressListener;

        ArtistCardAdapter(List<ArtistItem> items,
                          OnArtistClickListener clickListener,
                          OnArtistLongPress longPressListener) {
            this.items             = items;
            this.clickListener     = clickListener;
            this.longPressListener = longPressListener;
        }

        @Override public int getItemCount() { return items.size(); }

        @NonNull
        @Override
        public CardVH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_artist, parent, false);
            return new CardVH(v);
        }

        @Override
        public void onBindViewHolder(@NonNull CardVH holder, int position) {
            ArtistItem artist = items.get(position);

            holder.name.setText(artist.getArtistName());
            holder.songCount.setText(artist.getFormattedSongCount());

            long totalMs = artist.getTotalDuration();
            long totalMin = totalMs / 1000 / 60;
            long hours = totalMin / 60;
            long mins  = totalMin % 60;
            String durationStr = hours > 0 ? hours + "h " + mins + "m" : mins + "m";
            holder.duration.setText(durationStr);

            holder.itemView.setOnClickListener(v -> clickListener.onArtistClick(artist));
            holder.itemView.setOnLongClickListener(v -> {
                longPressListener.onLongPress(artist, v.getContext());
                return true;
            });
        }

        static class CardVH extends RecyclerView.ViewHolder {
            final TextView name, songCount, duration;

            CardVH(@NonNull View v) {
                super(v);
                name      = v.findViewById(R.id.artist_name);
                songCount = v.findViewById(R.id.song_count);
                duration  = v.findViewById(R.id.duration);
            }
        }
    }

    // ── Section view holder ───────────────────────────────────────────────────

    static class SectionViewHolder extends RecyclerView.ViewHolder {
        final RecyclerView innerRv;
        final TextView     btnSeeAll;

        SectionViewHolder(@NonNull View v) {
            super(v);
            innerRv   = v.findViewById(R.id.artistsHorizontalRv);
            btnSeeAll = v.findViewById(R.id.btnSeeAllArtists);
        }
    }
}
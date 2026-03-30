package com.alfahrel.melody.ui.search;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.alfahrel.melody.R;
import com.alfahrel.melody.ui.artist.ArtistAdapter;
import com.alfahrel.melody.ui.artist.ArtistDetailActivity;
import com.alfahrel.melody.ui.artist.ArtistFragment;
import com.alfahrel.melody.ui.artist.ArtistItem;
import com.alfahrel.melody.utils.GsonHelper;
import com.google.gson.reflect.TypeToken;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;

public class SearchArtistsFragment extends Fragment {

    private RecyclerView  recyclerView;
    private LinearLayout  emptyState;
    private ArtistAdapter adapter;
    private final List<ArtistItem> artists = new ArrayList<>();

    public static SearchArtistsFragment newInstance(List<ArtistItem> artists) {
        SearchArtistsFragment fragment = new SearchArtistsFragment();
        Bundle args = new Bundle();
        args.putParcelableArrayList("artists", new ArrayList<>(artists));
        fragment.setArguments(args);
        return fragment;
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_search_artists, container, false);
        recyclerView = view.findViewById(R.id.recyclerView);
        emptyState   = view.findViewById(R.id.emptyState);

        adapter = new ArtistAdapter(artists, getContext());
        recyclerView.setLayoutManager(new LinearLayoutManager(getContext()));
        recyclerView.setAdapter(adapter);

        adapter.setOnArtistItemClickListener(new ArtistAdapter.OnArtistItemClickListener() {
            @Override public void onArtistItemClick(ArtistItem artist) {
                Intent i = new Intent(requireContext(), ArtistDetailActivity.class);
                i.putExtra("artist_item", artist);
                startActivity(i);
            }
            @Override public void onPlayButtonClick(ArtistItem artist) {}
            // Long-press in ArtistAdapter already calls onPinClick, so this handles both
            @Override public void onPinClick(ArtistItem artist) { togglePin(artist); }
        });

        Bundle args = getArguments();
        if (args != null && args.containsKey("artists")) {
            List<ArtistItem> passedArtists = args.getParcelableArrayList("artists");
            if (passedArtists != null && !passedArtists.isEmpty()) updateArtists(passedArtists);
        }

        return view;
    }

    public void updateArtists(List<ArtistItem> newArtists) {
        artists.clear();
        artists.addAll(newArtists);
        if (adapter != null)      adapter.notifyDataSetChanged();
        if (emptyState != null)   emptyState.setVisibility(artists.isEmpty() ? View.VISIBLE : View.GONE);
        if (recyclerView != null) recyclerView.setVisibility(artists.isEmpty() ? View.GONE : View.VISIBLE);
    }

    // ── Pin ───────────────────────────────────────────────────────────────────

    private void togglePin(ArtistItem artist) {
        boolean nowPinned = !artist.isPinned();
        artist.setPinned(nowPinned);

        SharedPreferences prefs = requireContext()
                .getSharedPreferences(ArtistFragment.PREFS_NAME, Context.MODE_PRIVATE);
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

        broadcast("com.alfahrel.melody.ARTIST_CHANGED");
        adapter.notifyDataSetChanged();
        Toast.makeText(requireContext(),
                nowPinned ? "\"" + artist.getArtistName() + "\" pinned to Home"
                        : "\"" + artist.getArtistName() + "\" unpinned from Home",
                Toast.LENGTH_SHORT).show();
    }

    // ── Helper ────────────────────────────────────────────────────────────────

    private void broadcast(String action) {
        if (getContext() == null) return;
        Intent i = new Intent(action);
        i.setPackage(requireContext().getPackageName());
        requireContext().sendBroadcast(i);
    }
}
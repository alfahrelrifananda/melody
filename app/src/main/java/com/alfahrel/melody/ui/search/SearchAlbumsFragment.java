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
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.alfahrel.melody.R;
import com.alfahrel.melody.ui.album.AlbumAdapter;
import com.alfahrel.melody.ui.album.AlbumDetailActivity;
import com.alfahrel.melody.ui.album.AlbumFragment;
import com.alfahrel.melody.ui.album.AlbumItem;
import com.alfahrel.melody.utils.GsonHelper;
import com.google.gson.reflect.TypeToken;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;

public class SearchAlbumsFragment extends Fragment {

    private RecyclerView recyclerView;
    private LinearLayout emptyState;
    private AlbumAdapter adapter;
    private final List<AlbumItem> albums = new ArrayList<>();

    public static SearchAlbumsFragment newInstance(List<AlbumItem> albums) {
        SearchAlbumsFragment fragment = new SearchAlbumsFragment();
        Bundle args = new Bundle();
        args.putParcelableArrayList("albums", new ArrayList<>(albums));
        fragment.setArguments(args);
        return fragment;
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_search_albums, container, false);
        recyclerView = view.findViewById(R.id.recyclerView);
        emptyState   = view.findViewById(R.id.emptyState);

        adapter = new AlbumAdapter(albums, getContext());
        recyclerView.setLayoutManager(new GridLayoutManager(getContext(), 2));
        recyclerView.setAdapter(adapter);

        adapter.setOnAlbumItemClickListener(new AlbumAdapter.OnAlbumItemClickListener() {
            @Override public void onAlbumItemClick(AlbumItem album) {
                Intent i = new Intent(requireContext(), AlbumDetailActivity.class);
                i.putExtra("album_item", album);
                startActivity(i);
            }
            @Override public void onPlayButtonClick(AlbumItem album) {}
            // Long-press in AlbumAdapter already calls onPinClick, so this handles both
            @Override public void onPinClick(AlbumItem album) { togglePin(album); }
        });

        Bundle args = getArguments();
        if (args != null && args.containsKey("albums")) {
            List<AlbumItem> passedAlbums = args.getParcelableArrayList("albums");
            if (passedAlbums != null && !passedAlbums.isEmpty()) updateAlbums(passedAlbums);
        }

        return view;
    }

    public void updateAlbums(List<AlbumItem> newAlbums) {
        albums.clear();
        albums.addAll(newAlbums);
        if (adapter != null)      adapter.notifyDataSetChanged();
        if (emptyState != null)   emptyState.setVisibility(albums.isEmpty() ? View.VISIBLE : View.GONE);
        if (recyclerView != null) recyclerView.setVisibility(albums.isEmpty() ? View.GONE : View.VISIBLE);
    }

    // ── Pin ───────────────────────────────────────────────────────────────────

    private void togglePin(AlbumItem album) {
        boolean nowPinned = !album.isPinned();
        album.setPinned(nowPinned);

        SharedPreferences prefs = requireContext()
                .getSharedPreferences(AlbumFragment.PREFS_NAME, Context.MODE_PRIVATE);
        String json = prefs.getString(AlbumFragment.KEY_ALBUMS, null);
        if (json != null) {
            Type type = new TypeToken<List<AlbumItem>>() {}.getType();
            List<AlbumItem> saved = GsonHelper.get().fromJson(json, type);
            if (saved != null) {
                for (AlbumItem a : saved) {
                    if (a.getAlbumId() == album.getAlbumId()) { a.setPinned(nowPinned); break; }
                }
                prefs.edit()
                        .putString(AlbumFragment.KEY_ALBUMS, GsonHelper.get().toJson(saved))
                        .apply();
            }
        } else {
            // No list saved yet — persist the current search list
            prefs.edit()
                    .putString(AlbumFragment.KEY_ALBUMS, GsonHelper.get().toJson(albums))
                    .apply();
        }

        broadcast("com.alfahrel.melody.ALBUM_CHANGED");
        adapter.notifyDataSetChanged();
        Toast.makeText(requireContext(),
                nowPinned ? "\"" + album.getAlbumName() + "\" pinned to Home"
                        : "\"" + album.getAlbumName() + "\" unpinned from Home",
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
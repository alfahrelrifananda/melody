package com.alfahrel.melody.ui.pages.all;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.MenuItem;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.GridLayoutManager;

import com.alfahrel.melody.databinding.ActivityAllAlbumsBinding;
import com.alfahrel.melody.ui.album.AlbumAdapter;
import com.alfahrel.melody.ui.album.AlbumDetailActivity;
import com.alfahrel.melody.ui.album.AlbumFragment;
import com.alfahrel.melody.ui.album.AlbumItem;
import com.alfahrel.melody.utils.GsonHelper;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class AllAlbumsActivity extends AppCompatActivity {

    private ActivityAllAlbumsBinding binding;
    private AlbumAdapter adapter;
    private final List<AlbumItem> allAlbums      = new ArrayList<>();
    private final List<AlbumItem> filteredAlbums = new ArrayList<>();

    private Gson gson;
    private SharedPreferences prefs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityAllAlbumsBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        gson  = GsonHelper.get();
        prefs = getSharedPreferences(AlbumFragment.PREFS_NAME, MODE_PRIVATE);

        setupToolbar();
        setupRecyclerView();

        List<AlbumItem> passedAlbums = getIntent().getParcelableArrayListExtra("albums");
        if (passedAlbums != null && !passedAlbums.isEmpty()) {
            allAlbums.addAll(passedAlbums);
            applyFilter("");
        } else {
            loadAlbums();
        }

        setupSearch();
    }
    private void setupToolbar() {
        setSupportActionBar(binding.toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle("All Albums");
        }
    }

    private void setupRecyclerView() {
        adapter = new AlbumAdapter(filteredAlbums, this);
        binding.recyclerView.setLayoutManager(new GridLayoutManager(this, 2));
        binding.recyclerView.setAdapter(adapter);
        binding.recyclerView.setItemViewCacheSize(20);

        adapter.setOnAlbumItemClickListener(new AlbumAdapter.OnAlbumItemClickListener() {
            @Override public void onAlbumItemClick(AlbumItem album) {
                Intent i = new Intent(AllAlbumsActivity.this, AlbumDetailActivity.class);
                i.putExtra("album_item", album);
                startActivity(i);
            }
            @Override public void onPlayButtonClick(AlbumItem album) {}
            @Override public void onPinClick(AlbumItem album) { togglePin(album); }
        });
    }

    private void setupSearch() {
        binding.searchEditText.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
            @Override public void onTextChanged(CharSequence s, int st, int b, int c) { applyFilter(s.toString()); }
            @Override public void afterTextChanged(Editable s) {}
        });
    }

    private void loadAlbums() {
        String json = prefs.getString(AlbumFragment.KEY_ALBUMS, null);
        if (json != null) {
            Type type = new TypeToken<List<AlbumItem>>(){}.getType();
            List<AlbumItem> saved = gson.fromJson(json, type);
            if (saved != null) {
                allAlbums.addAll(saved);
                allAlbums.sort((a, b) -> a.getAlbumName().compareToIgnoreCase(b.getAlbumName()));
            }
        }
        applyFilter("");
    }

    private void applyFilter(String query) {
        filteredAlbums.clear();
        if (query.trim().isEmpty()) {
            filteredAlbums.addAll(allAlbums);
        } else {
            String q = query.toLowerCase(Locale.getDefault());
            for (AlbumItem a : allAlbums)
                if (a.getAlbumName().toLowerCase(Locale.getDefault()).contains(q)
                        || a.getArtistName().toLowerCase(Locale.getDefault()).contains(q))
                    filteredAlbums.add(a);
        }
        adapter.notifyDataSetChanged();
        binding.emptyState.setVisibility(filteredAlbums.isEmpty() ? View.VISIBLE : View.GONE);
        binding.recyclerView.setVisibility(filteredAlbums.isEmpty() ? View.GONE : View.VISIBLE);
    }

    private void togglePin(AlbumItem album) {
        for (AlbumItem a : allAlbums) {
            if (a.getAlbumId() == album.getAlbumId()) { a.setPinned(!a.isPinned()); break; }
        }
        prefs.edit().putString(AlbumFragment.KEY_ALBUMS, gson.toJson(allAlbums)).apply();
        try {
            Intent i = new Intent("com.alfahrel.melody.ALBUM_CHANGED");
            i.setPackage(getPackageName());
            sendBroadcast(i);
        } catch (Exception e) { e.printStackTrace(); }
        applyFilter(binding.searchEditText.getText() != null
                ? binding.searchEditText.getText().toString() : "");
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        if (item.getItemId() == android.R.id.home) { finish(); return true; }
        return super.onOptionsItemSelected(item);
    }
}
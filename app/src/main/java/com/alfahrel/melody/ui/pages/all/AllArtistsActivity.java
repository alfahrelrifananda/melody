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
import androidx.recyclerview.widget.LinearLayoutManager;

import com.alfahrel.melody.databinding.ActivityAllAlbumsBinding;
import com.alfahrel.melody.databinding.ActivityAllArtistsBinding;
import com.alfahrel.melody.ui.album.AlbumFragment;
import com.alfahrel.melody.ui.album.AlbumItem;
import com.alfahrel.melody.ui.artist.ArtistAdapter;
import com.alfahrel.melody.ui.artist.ArtistDetailActivity;
import com.alfahrel.melody.ui.artist.ArtistFragment;
import com.alfahrel.melody.ui.artist.ArtistItem;
import com.alfahrel.melody.utils.GsonHelper;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class AllArtistsActivity extends AppCompatActivity {

    private ActivityAllArtistsBinding binding;
    private ArtistAdapter adapter;
    private final List<ArtistItem> allArtists      = new ArrayList<>();
    private final List<ArtistItem> filteredArtists = new ArrayList<>();

    private Gson gson;
    private SharedPreferences prefs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityAllArtistsBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        gson  = GsonHelper.get();
        prefs = getSharedPreferences(AlbumFragment.PREFS_NAME, MODE_PRIVATE);

        setupToolbar();
        setupRecyclerView();

        List<ArtistItem> passedArtists = getIntent().getParcelableArrayListExtra("artists");
        if (passedArtists != null && !passedArtists.isEmpty()) {
            allArtists.addAll(passedArtists);
            applyFilter("");
        } else {
            loadArtists();
        }

        setupSearch();
    }

    private void setupToolbar() {
        setSupportActionBar(binding.toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle("All Artists");
        }
    }

    private void setupRecyclerView() {
        adapter = new ArtistAdapter(filteredArtists, this);
        binding.recyclerView.setLayoutManager(new LinearLayoutManager(this));
        binding.recyclerView.setAdapter(adapter);
        binding.recyclerView.setItemViewCacheSize(20);

        adapter.setOnArtistItemClickListener(new ArtistAdapter.OnArtistItemClickListener() {
            @Override public void onArtistItemClick(ArtistItem artist) {
                Intent i = new Intent(AllArtistsActivity.this, ArtistDetailActivity.class);
                i.putExtra("artist_item", artist);
                startActivity(i);
            }
            @Override public void onPlayButtonClick(ArtistItem artist) {}
            @Override public void onPinClick(ArtistItem artist) { togglePin(artist); }
        });
    }

    private void setupSearch() {
        binding.searchEditText.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
            @Override public void onTextChanged(CharSequence s, int st, int b, int c) { applyFilter(s.toString()); }
            @Override public void afterTextChanged(Editable s) {}
        });
    }

    private void loadArtists() {
        String json = prefs.getString(ArtistFragment.KEY_ARTISTS, null);
        if (json != null) {
            Type type = new TypeToken<List<ArtistItem>>(){}.getType();
            List<ArtistItem> saved = gson.fromJson(json, type);
            if (saved != null) {
                allArtists.addAll(saved);
                allArtists.sort((a, b) -> a.getArtistName().compareToIgnoreCase(b.getArtistName()));
            }
        }
        applyFilter("");
    }

    private void applyFilter(String query) {
        filteredArtists.clear();
        if (query.trim().isEmpty()) {
            filteredArtists.addAll(allArtists);
        } else {
            String q = query.toLowerCase(Locale.getDefault());
            for (ArtistItem a : allArtists)
                if (a.getArtistName().toLowerCase(Locale.getDefault()).contains(q))
                    filteredArtists.add(a);
        }
        adapter.notifyDataSetChanged();
        binding.emptyState.setVisibility(filteredArtists.isEmpty() ? View.VISIBLE : View.GONE);
        binding.recyclerView.setVisibility(filteredArtists.isEmpty() ? View.GONE : View.VISIBLE);
    }

    private void togglePin(ArtistItem artist) {
        for (ArtistItem a : allArtists) {
            if (a.getArtistName().equals(artist.getArtistName())) { a.setPinned(!a.isPinned()); break; }
        }
        prefs.edit().putString(ArtistFragment.KEY_ARTISTS, gson.toJson(allArtists)).apply();
        try {
            Intent i = new Intent("com.alfahrel.melody.ARTIST_CHANGED");
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
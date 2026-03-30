package com.alfahrel.melody.ui.pages.all;

import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.view.MenuItem;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.alfahrel.melody.R;
import com.alfahrel.melody.databinding.ActivityAllArtistsBinding;
import com.alfahrel.melody.service.MusicService;
import com.alfahrel.melody.ui.album.AlbumFragment;
import com.alfahrel.melody.ui.artist.ArtistAdapter;
import com.alfahrel.melody.ui.artist.ArtistDetailActivity;
import com.alfahrel.melody.ui.artist.ArtistFragment;
import com.alfahrel.melody.ui.artist.ArtistItem;
import com.alfahrel.melody.ui.music.MusicItem;
import com.alfahrel.melody.ui.pages.nowplaying.NowPlayingActivity;
import com.alfahrel.melody.utils.GsonHelper;
import com.bumptech.glide.Glide;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class AllArtistsActivity extends AppCompatActivity {

    private static final String TAG = "AllArtistsActivity";

    private ActivityAllArtistsBinding binding;
    private ArtistAdapter adapter;
    private final List<ArtistItem> allArtists      = new ArrayList<>();
    private final List<ArtistItem> filteredArtists = new ArrayList<>();

    private Gson gson;
    private SharedPreferences prefs;

    // Mini player
    private MaterialCardView miniPlayerContainer;
    private ImageView miniAlbumArt;
    private TextView miniSongTitle;
    private TextView miniArtistName;
    private MaterialButton miniPlayPauseButton;
    private MaterialButton miniNextButton;
    private MaterialButton miniCloseButton;
    private MusicItem currentPlayingItem;
    private boolean isPlaying = false;
    private boolean isMiniPlayerVisible = false;
    private boolean isReceiverRegistered = false;
    private boolean isActivityDestroyed = false;

    // ── Broadcast receiver ────────────────────────────────────────────────────

    private final BroadcastReceiver musicUpdateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (isActivityDestroyed || isFinishing() || isDestroyed()) return;
            try {
                String action = intent.getAction();
                if (action == null) return;
                switch (action) {
                    case MusicService.ACTION_MUSIC_UPDATED:
                        MusicItem item = intent.getParcelableExtra("music_item");
                        boolean playing = intent.getBooleanExtra("is_playing", false);
                        if (item != null) { showMiniPlayer(item); updateMiniPlayerState(playing); }
                        break;
                    case MusicService.ACTION_PLAYBACK_STATE_CHANGED:
                        updateMiniPlayerState(intent.getBooleanExtra("is_playing", false));
                        break;
                    case MusicService.ACTION_HIDE_MINI_PLAYER:
                        hideMiniPlayer();
                        break;
                }
            } catch (Exception e) {
                Log.e(TAG, "Broadcast error", e);
            }
        }
    };

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityAllArtistsBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        gson  = GsonHelper.get();
        prefs = getSharedPreferences(AlbumFragment.PREFS_NAME, MODE_PRIVATE);

        setupToolbar();
        setupRecyclerView();
        initializeMiniPlayer();

        List<ArtistItem> passedArtists = getIntent().getParcelableArrayListExtra("artists");
        if (passedArtists != null && !passedArtists.isEmpty()) {
            allArtists.addAll(passedArtists);
            applyFilter("");
        } else {
            loadArtists();
        }

        setupSearch();
        registerMusicUpdateReceiver();
    }

    @Override
    protected void onResume() {
        super.onResume();
        try { sendServiceAction(MusicService.ACTION_REQUEST_STATE); } catch (Exception ignored) {}
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (miniPlayerContainer != null) miniPlayerContainer.clearAnimation();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        isActivityDestroyed = true;
        if (isReceiverRegistered) {
            try { unregisterReceiver(musicUpdateReceiver); } catch (Exception ignored) {}
            isReceiverRegistered = false;
        }
        try {
            if (!isDestroyed()) Glide.with(this).clear(miniAlbumArt);
        } catch (Exception ignored) {}
    }

    // ── Setup ─────────────────────────────────────────────────────────────────

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

    // ── Load / filter ─────────────────────────────────────────────────────────

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

    // ── Mini player ───────────────────────────────────────────────────────────

    private void initializeMiniPlayer() {
        miniPlayerContainer = binding.miniPlayerContainer;
        miniAlbumArt        = binding.miniAlbumArt;
        miniSongTitle       = binding.miniSongTitle;
        miniArtistName      = binding.miniArtistName;
        miniPlayPauseButton = binding.miniPlayPauseButton;
        miniNextButton      = binding.miniNextButton;
        miniCloseButton     = binding.miniCloseButton;

        miniPlayerContainer.setOnClickListener(v -> openNowPlayingActivity());
        miniPlayPauseButton.setOnClickListener(v -> sendServiceAction(MusicService.ACTION_TOGGLE_PLAY_PAUSE));
        miniNextButton.setOnClickListener(v -> sendServiceAction(MusicService.ACTION_NEXT));
        miniCloseButton.setOnClickListener(v -> { sendServiceAction(MusicService.ACTION_STOP); hideMiniPlayer(); });
    }

    private void showMiniPlayer(MusicItem item) {
        if (isActivityDestroyed || item == null) return;
        currentPlayingItem = item;
        miniSongTitle.setText(item.getTitle());
        miniArtistName.setText(item.getArtist());
        try {
            Glide.with(this).load(item.getAlbumArtUri())
                    .placeholder(R.drawable.ic_outline_music_note_24)
                    .error(R.drawable.ic_outline_music_note_24)
                    .into(miniAlbumArt);
        } catch (Exception ignored) {}

        if (!isMiniPlayerVisible) {
            isMiniPlayerVisible = true;
            miniPlayerContainer.setVisibility(View.VISIBLE);
            miniPlayerContainer.setTranslationY(miniPlayerContainer.getHeight());
            miniPlayerContainer.animate().translationY(0).setDuration(300).start();
        }
        updateMiniPlayerPlayButton();
    }

    private void hideMiniPlayer() {
        if (!isMiniPlayerVisible || miniPlayerContainer == null) return;
        isMiniPlayerVisible = false;
        miniPlayerContainer.animate().translationY(miniPlayerContainer.getHeight()).setDuration(300)
                .withEndAction(() -> { if (!isActivityDestroyed) miniPlayerContainer.setVisibility(View.GONE); })
                .start();
    }

    private void updateMiniPlayerState(boolean playing) {
        isPlaying = playing;
        updateMiniPlayerPlayButton();
    }

    private void updateMiniPlayerPlayButton() {
        if (miniPlayPauseButton == null) return;
        miniPlayPauseButton.setIconResource(
                isPlaying ? R.drawable.ic_baseline_pause_24 : R.drawable.ic_baseline_play_arrow_24);
    }

    private void openNowPlayingActivity() {
        if (isActivityDestroyed || currentPlayingItem == null) return;
        Intent intent = new Intent(this, NowPlayingActivity.class);
        intent.putExtra("music_item", currentPlayingItem);
        startActivity(intent);
        overridePendingTransition(R.anim.slide_in_bottom, R.anim.slide_out_top);
    }

    private void sendServiceAction(String action) {
        if (isActivityDestroyed) return;
        Intent i = new Intent(this, MusicService.class);
        i.setAction(action);
        startService(i);
    }

    // ── Receiver ──────────────────────────────────────────────────────────────

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private void registerMusicUpdateReceiver() {
        if (isReceiverRegistered) return;
        IntentFilter f = new IntentFilter();
        f.addAction(MusicService.ACTION_MUSIC_UPDATED);
        f.addAction(MusicService.ACTION_PLAYBACK_STATE_CHANGED);
        f.addAction(MusicService.ACTION_HIDE_MINI_PLAYER);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            registerReceiver(musicUpdateReceiver, f, Context.RECEIVER_NOT_EXPORTED);
        else
            registerReceiver(musicUpdateReceiver, f);
        isReceiverRegistered = true;
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        if (item.getItemId() == android.R.id.home) { finish(); return true; }
        return super.onOptionsItemSelected(item);
    }
}
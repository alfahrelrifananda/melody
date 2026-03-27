package com.alfahrel.melody.ui.collection;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Canvas;
import android.net.Uri;
import android.os.Bundle;
import android.os.Parcelable;
import android.provider.MediaStore;
import android.util.Log;
import android.view.MenuItem;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.alfahrel.melody.utils.GsonHelper;
import com.bumptech.glide.Glide;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import com.alfahrel.melody.R;
import com.alfahrel.melody.ui.music.MusicAdapter;
import com.alfahrel.melody.ui.music.MusicItem;
import com.alfahrel.melody.ui.pages.nowplaying.NowPlayingActivity;
import com.alfahrel.melody.service.MusicService;
import com.alfahrel.melody.utils.SongDetailBottomSheet;
import com.alfahrel.melody.utils.SongOptionsBottomSheet;
import com.google.android.material.snackbar.Snackbar;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class CollectionDetailActivity extends AppCompatActivity {

    private static final String TAG = "CollectionDetailActivity";

    // ── Song pin persistence (mirrors MusicFragment) ──────────────────────────
    private static final String SONG_PREFS_NAME = "SongsPrefs";
    private static final String KEY_SONGS       = "songs_full";
    private Gson gson;
    private SharedPreferences songPrefs;

    private MaterialToolbar toolbar;
    private ImageView collectionCoverImageView;
    private TextView collectionNameTextView;
    private TextView songCountTextView;
    private TextView totalDurationTextView;
    private RecyclerView songsRecyclerView;
    private View loadingLayout;
    private View emptyState;
    private MaterialButton shuffleCollectionButton;
    private MaterialButton deleteCollectionButton;

    private MaterialCardView miniPlayerContainer;
    private ImageView miniAlbumArt;
    private TextView miniSongTitle;
    private TextView miniArtistName;
    private MaterialButton miniPlayPauseButton;
    private MaterialButton miniNextButton;
    private MaterialButton miniCloseButton;

    private Collection collection;
    private List<MusicItem> collectionSongs = new ArrayList<>();
    private MusicAdapter musicAdapter;
    private ExecutorService executorService;
    private CollectionManager collectionManager;
    private boolean isLoading = false;

    private MusicItem currentPlayingItem;
    private boolean isPlaying = false;
    private boolean isMiniPlayerVisible = false;
    private boolean isReceiverRegistered = false;
    private boolean isActivityDestroyed = false;

    private BroadcastReceiver musicUpdateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (isActivityDestroyed || isFinishing() || isDestroyed()) return;
            try {
                String action = intent.getAction();
                if (action != null) {
                    switch (action) {
                        case MusicService.ACTION_MUSIC_UPDATED:
                            MusicItem musicItem = intent.getParcelableExtra("music_item");
                            boolean playing = intent.getBooleanExtra("is_playing", false);
                            if (musicItem != null) {
                                showMiniPlayer(musicItem);
                                updateMiniPlayerState(playing);
                            }
                            break;
                        case MusicService.ACTION_PLAYBACK_STATE_CHANGED:
                            updateMiniPlayerState(intent.getBooleanExtra("is_playing", false));
                            break;
                        case MusicService.ACTION_HIDE_MINI_PLAYER:
                            hideMiniPlayer();
                            break;
                        case "MINI_PLAYER_VISIBILITY_CHANGED":
                            adjustRecyclerViewPadding(
                                    intent.getBooleanExtra("is_visible", false),
                                    intent.getIntExtra("height", 0));
                            break;
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "Error handling broadcast: " + e.getMessage(), e);
            }
        }
    };

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_collection_detail);

        try {
            executorService   = Executors.newSingleThreadExecutor();
            collectionManager = new CollectionManager(this);
            gson              = GsonHelper.get();
            songPrefs         = getSharedPreferences(SONG_PREFS_NAME, Context.MODE_PRIVATE);

            if (!getCollectionDataFromIntent()) { finish(); return; }
            if (!initializeViews())             { finish(); return; }
            if (!initializeMiniPlayer())        { finish(); return; }

            setupToolbar();
            setupCollectionHeader();
            setupRecyclerView();
            setupShuffleButton();
            setupDeleteButton();

            loadCollectionSongs();
            registerMusicUpdateReceiver();

        } catch (Exception e) {
            Log.e(TAG, "Error in onCreate: " + e.getMessage(), e);
            finish();
        }
    }

    // ── Init helpers ──────────────────────────────────────────────────────────

    private boolean getCollectionDataFromIntent() {
        collection = getIntent().getParcelableExtra("collection");
        if (collection == null) {
            Toast.makeText(this, "Error: Collection not found", Toast.LENGTH_SHORT).show();
            return false;
        }
        return true;
    }

    private boolean initializeViews() {
        try {
            toolbar                  = findViewById(R.id.toolbar);
            collectionCoverImageView = findViewById(R.id.collectionCoverImageView);
            collectionNameTextView   = findViewById(R.id.collectionNameTextView);
            songCountTextView        = findViewById(R.id.songCountTextView);
            totalDurationTextView    = findViewById(R.id.totalDurationTextView);
            songsRecyclerView        = findViewById(R.id.songsRecyclerView);
            loadingLayout            = findViewById(R.id.loadingLayout);
            emptyState               = findViewById(R.id.emptyState);
            shuffleCollectionButton  = findViewById(R.id.shuffleCollectionButton);
            deleteCollectionButton   = findViewById(R.id.deleteCollectionButton);

            if (toolbar == null || collectionNameTextView == null ||
                    songCountTextView == null || songsRecyclerView == null ||
                    loadingLayout == null || emptyState == null ||
                    shuffleCollectionButton == null || deleteCollectionButton == null) {
                Log.e(TAG, "One or more views are null");
                return false;
            }
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Error initializing views: " + e.getMessage(), e);
            return false;
        }
    }

    private void setupToolbar() {
        try {
            setSupportActionBar(toolbar);
            if (getSupportActionBar() != null) {
                getSupportActionBar().setDisplayHomeAsUpEnabled(true);
                getSupportActionBar().setDisplayShowHomeEnabled(true);
                getSupportActionBar().setTitle("");
            }
        } catch (Exception e) {
            Log.e(TAG, "Error setting up toolbar", e);
        }
    }

    private void setupCollectionHeader() {
        try {
            collectionNameTextView.setText(collection.getName());
            if (collectionCoverImageView != null) {
                String uri = collection.getCoverImageUri();
                if (uri != null && !uri.isEmpty()) {
                    Glide.with(this)
                            .load(Uri.parse(uri))
                            .centerCrop()
                            .placeholder(R.drawable.ic_outline_music_note_24)
                            .error(R.drawable.ic_outline_music_note_24)
                            .into(collectionCoverImageView);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error setting up collection header", e);
        }
    }

    // ── Static utility for CollectionAdapter ─────────────────────────────────

    public static void bindCoverThumb(
            com.google.android.material.imageview.ShapeableImageView imageView,
            View placeholder,
            String coverUri) {

        if (coverUri != null && !coverUri.isEmpty()) {
            placeholder.setVisibility(View.GONE);
            imageView.setVisibility(View.VISIBLE);
            imageView.setPadding(0, 0, 0, 0);
            imageView.clearColorFilter();
            Glide.with(imageView.getContext())
                    .load(Uri.parse(coverUri))
                    .centerCrop()
                    .placeholder(R.drawable.ic_outline_music_note_24)
                    .into(imageView);
        } else {
            imageView.setVisibility(View.INVISIBLE);
            placeholder.setVisibility(View.VISIBLE);
        }
    }

    // ── Song pin ──────────────────────────────────────────────────────────────

    private void toggleSongPin(MusicItem song) {
        executorService.execute(() -> {
            boolean nowPinned = false;
            for (MusicItem s : collectionSongs) {
                if (s.getId() == song.getId()) {
                    nowPinned = !s.isPinned();
                    s.setPinned(nowPinned);
                    break;
                }
            }
            // Persist into the shared SongsPrefs so MusicFragment can read it
            saveSongPinnedState();
            final boolean pinned = nowPinned;
            runOnUiThread(() -> {
                if (musicAdapter != null) musicAdapter.notifyDataSetChanged();
                Toast.makeText(this,
                        pinned ? "Pinned to Home" : "Unpinned from Home",
                        Toast.LENGTH_SHORT).show();
                broadcastCollectionChange();
            });
        });
    }

    /** Merges pin state of collectionSongs back into the SongsPrefs list. */
    private void saveSongPinnedState() {
        String json = songPrefs.getString(KEY_SONGS, null);
        List<MusicItem> all = new ArrayList<>();
        if (json != null) {
            Type type = new TypeToken<List<MusicItem>>(){}.getType();
            List<MusicItem> saved = gson.fromJson(json, type);
            if (saved != null) all = saved;
        }
        // Build a map of updated pin states from collectionSongs
        Map<Long, Boolean> pinMap = new HashMap<>();
        for (MusicItem s : collectionSongs) pinMap.put(s.getId(), s.isPinned());
        // Apply to the full list
        for (MusicItem s : all) {
            if (pinMap.containsKey(s.getId())) s.setPinned(pinMap.get(s.getId()));
        }
        songPrefs.edit().putString(KEY_SONGS, gson.toJson(all)).apply();
    }

    /** Restore pin state for loaded songs from SongsPrefs. */
    private void restoreSongPinnedState(List<MusicItem> list) {
        String json = songPrefs.getString(KEY_SONGS, null);
        if (json == null) return;
        Type type = new TypeToken<List<MusicItem>>(){}.getType();
        List<MusicItem> saved = gson.fromJson(json, type);
        if (saved == null) return;
        Map<Long, Boolean> pinMap = new HashMap<>();
        for (MusicItem s : saved) pinMap.put(s.getId(), s.isPinned());
        for (MusicItem s : list) {
            Boolean pinned = pinMap.get(s.getId());
            if (pinned != null) s.setPinned(pinned);
        }
    }

    // ── Shuffle & Delete ──────────────────────────────────────────────────────

    private void setupShuffleButton() {
        shuffleCollectionButton.setOnClickListener(v -> {
            if (collectionSongs.isEmpty()) {
                Toast.makeText(this, "No songs to shuffle", Toast.LENGTH_SHORT).show();
                return;
            }
            int randomIndex = new Random().nextInt(collectionSongs.size());
            MusicItem randomSong = collectionSongs.get(randomIndex);

            Intent playlistIntent = new Intent(this, MusicService.class);
            playlistIntent.setAction(MusicService.ACTION_SET_PLAYLIST);
            playlistIntent.putParcelableArrayListExtra("playlist", new ArrayList<>(collectionSongs));
            playlistIntent.putExtra("start_index", randomIndex);
            startService(playlistIntent);

            new android.os.Handler().postDelayed(() -> {
                Intent shuffleIntent = new Intent(this, MusicService.class);
                shuffleIntent.setAction(MusicService.ACTION_TOGGLE_SHUFFLE);
                startService(shuffleIntent);

                new android.os.Handler().postDelayed(() -> {
                    Intent playIntent = new Intent(this, MusicService.class);
                    playIntent.setAction(MusicService.ACTION_PLAY);
                    playIntent.putExtra("music_item", randomSong);
                    startService(playIntent);
                }, 50);
            }, 50);
        });
    }

    private void setupDeleteButton() {
        deleteCollectionButton.setOnClickListener(v ->
                new AlertDialog.Builder(this)
                        .setTitle("Delete Collection")
                        .setMessage("Are you sure you want to delete \"" + collection.getName() + "\"? This cannot be undone.")
                        .setPositiveButton("Delete", (dialog, which) -> {
                            if (collectionManager.deleteCollection(collection.getId())) {
                                Toast.makeText(this, "Collection deleted", Toast.LENGTH_SHORT).show();
                                finish();
                            } else {
                                Toast.makeText(this, "Failed to delete collection", Toast.LENGTH_SHORT).show();
                            }
                        })
                        .setNegativeButton("Cancel", null)
                        .show());
    }

    // ── RecyclerView & ItemTouchHelper ────────────────────────────────────────

    private void setupRecyclerView() {
        try {
            musicAdapter = new MusicAdapter(collectionSongs, this);
            songsRecyclerView.setLayoutManager(new LinearLayoutManager(this));
            songsRecyclerView.setAdapter(musicAdapter);

            musicAdapter.setOnMusicItemClickListener(new MusicAdapter.OnMusicItemClickListener() {
                @Override public void onMusicItemClick(MusicItem musicItem) {
                    startMusicServiceAndOpenNowPlaying(musicItem);
                }
                @Override public void onOptionClick(MusicItem musicItem) {
                    showSongOptionsSheet(musicItem);
                }
            });

            // No long-press listener – vert icon (onOptionClick) handles everything.

            new ItemTouchHelper(new CollectionItemTouchHelperCallback())
                    .attachToRecyclerView(songsRecyclerView);

        } catch (Exception e) {
            Log.e(TAG, "Error setting up RecyclerView", e);
        }
    }

    // ── Song options bottom sheet ─────────────────────────────────────────────

    private void showSongOptionsSheet(MusicItem musicItem) {
        if (musicItem == null) return;
        SongOptionsBottomSheet sheet = SongOptionsBottomSheet.newInstance(musicItem);
        sheet.setListener(new SongOptionsBottomSheet.SongOptionsListener() {
            @Override public void onAddToCollection(MusicItem item) {
                // Collections inside a collection detail – delegate to CollectionManager directly
                Toast.makeText(CollectionDetailActivity.this,
                        "Use the main library to add songs to collections",
                        Toast.LENGTH_SHORT).show();
            }
            @Override public void onViewDetails(MusicItem item) {
                SongDetailBottomSheet.newInstance(item)
                        .show(getSupportFragmentManager(), "song_detail");
            }
            @Override public void onDelete(MusicItem item) {
                showRemoveFromCollectionDialog(item);
            }
            @Override public void onPin(MusicItem item) {
                toggleSongPin(item);
            }
        });
        sheet.show(getSupportFragmentManager(), "song_options");
    }

    private void showRemoveFromCollectionDialog(MusicItem musicItem) {
        new AlertDialog.Builder(this)
                .setTitle("Remove song")
                .setMessage("Remove \"" + musicItem.getTitle() + "\" from this collection?")
                .setPositiveButton("Remove", (dialog, which) -> removeSongFromCollection(musicItem))
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void removeSongFromCollection(MusicItem musicItem) {
        if (collectionManager.removeSongFromCollection(collection.getId(), musicItem.getId())) {
            collectionSongs.remove(musicItem);
            if (musicAdapter != null) musicAdapter.notifyDataSetChanged();
            updateSongCountAndDuration();
            updateUI();
            broadcastCollectionChange();
            Toast.makeText(this, "Song removed from collection", Toast.LENGTH_SHORT).show();
        } else {
            Toast.makeText(this, "Failed to remove song", Toast.LENGTH_SHORT).show();
        }
    }

    // ── ItemTouchHelper ───────────────────────────────────────────────────────

    private class CollectionItemTouchHelperCallback extends ItemTouchHelper.Callback {
        private int dragFrom = -1, dragTo = -1;

        @Override public boolean isLongPressDragEnabled() { return true; }
        @Override public boolean isItemViewSwipeEnabled()  { return true; }

        @Override
        public int getMovementFlags(RecyclerView rv, RecyclerView.ViewHolder vh) {
            return makeMovementFlags(
                    ItemTouchHelper.UP | ItemTouchHelper.DOWN,
                    ItemTouchHelper.START | ItemTouchHelper.END);
        }

        @Override
        public boolean onMove(RecyclerView rv, RecyclerView.ViewHolder vh, RecyclerView.ViewHolder target) {
            int from = vh.getAdapterPosition(), to = target.getAdapterPosition();
            if (dragFrom == -1) dragFrom = from;
            dragTo = to;
            if (from < to) for (int i = from; i < to; i++) java.util.Collections.swap(collectionSongs, i, i + 1);
            else           for (int i = from; i > to; i--) java.util.Collections.swap(collectionSongs, i, i - 1);
            if (musicAdapter != null) musicAdapter.notifyItemMoved(from, to);
            return true;
        }

        @Override
        public void onSwiped(RecyclerView.ViewHolder vh, int direction) {
            int position = vh.getAdapterPosition();
            if (position < 0 || position >= collectionSongs.size()) return;

            MusicItem removed = collectionSongs.remove(position);
            if (musicAdapter != null) musicAdapter.notifyItemRemoved(position);

            if (collectionManager.removeSongFromCollection(collection.getId(), removed.getId())) {
                updateSongCountAndDuration();
                updateUI();

                Snackbar.make(songsRecyclerView, "Removed from collection", Snackbar.LENGTH_LONG)
                        .setAction("UNDO", v -> {
                            if (collectionManager.addSongToCollection(collection.getId(), removed.getId())) {
                                collectionSongs.add(position, removed);
                                if (musicAdapter != null) musicAdapter.notifyItemInserted(position);
                                updateSongCountAndDuration();
                                updateUI();
                                broadcastCollectionChange();
                            }
                        }).show();

                broadcastCollectionChange();
            } else {
                Toast.makeText(CollectionDetailActivity.this, "Failed to remove song", Toast.LENGTH_SHORT).show();
                collectionSongs.add(position, removed);
                if (musicAdapter != null) musicAdapter.notifyItemInserted(position);
            }
        }

        @Override
        public void onSelectedChanged(RecyclerView.ViewHolder vh, int actionState) {
            super.onSelectedChanged(vh, actionState);
            if (actionState == ItemTouchHelper.ACTION_STATE_DRAG && vh != null) {
                vh.itemView.setAlpha(0.7f);
                vh.itemView.setScaleX(1.05f);
                vh.itemView.setScaleY(1.05f);
                vh.itemView.setElevation(8f);
            }
        }

        @Override
        public void clearView(RecyclerView rv, RecyclerView.ViewHolder vh) {
            super.clearView(rv, vh);
            vh.itemView.setAlpha(1f); vh.itemView.setScaleX(1f);
            vh.itemView.setScaleY(1f); vh.itemView.setElevation(0f);
            if (dragFrom != -1 && dragTo != -1 && dragFrom != dragTo) updateCollectionOrder();
            dragFrom = dragTo = -1;
        }

        @Override
        public void onChildDraw(Canvas c, RecyclerView rv, RecyclerView.ViewHolder vh,
                                float dX, float dY, int actionState, boolean isCurrentlyActive) {
            if (actionState == ItemTouchHelper.ACTION_STATE_SWIPE) {
                vh.itemView.setAlpha(1f - Math.abs(dX) / vh.itemView.getWidth());
                vh.itemView.setTranslationX(dX);
            } else {
                super.onChildDraw(c, rv, vh, dX, dY, actionState, isCurrentlyActive);
            }
        }
    }

    private void updateCollectionOrder() {
        List<Long> newOrder = new ArrayList<>();
        for (MusicItem item : collectionSongs) newOrder.add(item.getId());
        collection.setMusicIds(newOrder);
        collectionManager.updateCollection(collection);
    }

    // ── Load songs ────────────────────────────────────────────────────────────

    private void loadCollectionSongs() {
        if (!hasStoragePermission()) {
            Toast.makeText(this, "Storage permission required", Toast.LENGTH_SHORT).show();
            return;
        }
        if (isLoading) return;
        isLoading = true;
        showLoading(true);

        executorService.execute(() -> {
            List<MusicItem> temp     = new ArrayList<>();
            List<Long>      musicIds = collection.getMusicIds();

            if (musicIds == null || musicIds.isEmpty()) {
                runOnUiThread(() -> { showLoading(false); isLoading = false; updateUI(); });
                return;
            }

            Uri      musicUri   = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;
            String[] projection = {
                    MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE,
                    MediaStore.Audio.Media.ARTIST, MediaStore.Audio.Media.ALBUM,
                    MediaStore.Audio.Media.DURATION, MediaStore.Audio.Media.DATA,
                    MediaStore.Audio.Media.ALBUM_ID
            };

            ContentResolver cr = getContentResolver();
            for (Long id : musicIds) {
                try (android.database.Cursor c = cr.query(musicUri, projection,
                        MediaStore.Audio.Media._ID + "=?", new String[]{ String.valueOf(id) }, null)) {
                    if (c != null && c.moveToFirst()) {
                        long   mid      = c.getLong(c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID));
                        String title    = c.getString(c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE));
                        String artist   = c.getString(c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST));
                        String album    = c.getString(c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM));
                        long   duration = c.getLong(c.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION));
                        String path     = c.getString(c.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA));
                        long   albumId  = c.getLong(c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID));
                        Uri    artUri   = Uri.parse("content://media/external/audio/albumart/" + albumId);
                        temp.add(new MusicItem(mid, title, artist, album, duration, path, artUri));
                    }
                } catch (Exception e) { Log.e(TAG, "Error loading song id=" + id, e); }
            }

            restoreSongPinnedState(temp);

            runOnUiThread(() -> {
                showLoading(false);
                isLoading = false;
                collectionSongs.clear();
                collectionSongs.addAll(temp);
                updateSongCountAndDuration();
                if (musicAdapter != null) musicAdapter.notifyDataSetChanged();
                updateUI();
            });
        });
    }

    // ── UI helpers ────────────────────────────────────────────────────────────

    private void updateSongCountAndDuration() {
        int  count   = collectionSongs.size();
        long totalMs = 0;
        for (MusicItem s : collectionSongs) totalMs += s.getDuration();

        songCountTextView.setText(count + (count == 1 ? " song" : " songs"));
        if (totalDurationTextView != null) totalDurationTextView.setText(formatTotalDuration(totalMs));
    }

    private String formatTotalDuration(long ms) {
        long s = ms / 1000, h = s / 3600, m = (s % 3600) / 60, sec = s % 60;
        if (h > 0)  return String.format("%dh %dm", h, m);
        if (m > 0)  return String.format("%dm %ds", m, sec);
        return String.format("%ds", sec);
    }

    private void updateUI() {
        boolean empty = collectionSongs.isEmpty();
        emptyState.setVisibility(empty ? View.VISIBLE : View.GONE);
        songsRecyclerView.setVisibility(empty ? View.GONE : View.VISIBLE);
    }

    private void showLoading(boolean show) {
        loadingLayout.setVisibility(show ? View.VISIBLE : View.GONE);
        if (show) {
            songsRecyclerView.setVisibility(View.GONE);
            emptyState.setVisibility(View.GONE);
        }
    }

    private void adjustRecyclerViewPadding(boolean visible, int height) {
        if (songsRecyclerView != null) {
            songsRecyclerView.setPadding(
                    songsRecyclerView.getPaddingLeft(),
                    songsRecyclerView.getPaddingTop(),
                    songsRecyclerView.getPaddingRight(),
                    visible ? height : 0);
        }
    }

    private void broadcastCollectionChange() {
        try {
            Intent i = new Intent("com.alfahrel.melody.COLLECTION_CHANGED");
            i.setPackage(getPackageName());
            sendBroadcast(i);
        } catch (Exception e) { Log.e(TAG, "Broadcast error", e); }
    }

    private boolean hasStoragePermission() {
        String perm = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU
                ? Manifest.permission.READ_MEDIA_AUDIO : Manifest.permission.READ_EXTERNAL_STORAGE;
        return ContextCompat.checkSelfPermission(this, perm) == PackageManager.PERMISSION_GRANTED;
    }

    // ── Playback helpers ──────────────────────────────────────────────────────

    private void startMusicServiceAndOpenNowPlaying(MusicItem item) {
        startMusicServiceWithPlaylist(item);
        new android.os.Handler().postDelayed(() -> openNowPlaying(item), 200);
    }

    private void startMusicServiceWithPlaylist(MusicItem selected) {
        if (collectionSongs.isEmpty()) return;
        int idx = 0;
        for (int i = 0; i < collectionSongs.size(); i++) {
            if (collectionSongs.get(i).getId() == selected.getId()) { idx = i; break; }
        }
        Intent pl = new Intent(this, MusicService.class);
        pl.setAction(MusicService.ACTION_SET_PLAYLIST);
        pl.putParcelableArrayListExtra("playlist", new ArrayList<>(collectionSongs));
        pl.putExtra("start_index", idx);
        startService(pl);

        Intent play = new Intent(this, MusicService.class);
        play.setAction(MusicService.ACTION_PLAY);
        play.putExtra("music_item", selected);
        startService(play);
    }

    private void openNowPlaying(MusicItem item) {
        Intent i = new Intent(this, NowPlayingActivity.class);
        i.putExtra("music_item", (Parcelable) item);
        startActivity(i);
        overridePendingTransition(R.anim.slide_in_bottom, R.anim.slide_out_top);
    }

    private void openNowPlayingActivity() {
        if (isActivityDestroyed || currentPlayingItem == null) return;
        try {
            Intent i = new Intent(this, NowPlayingActivity.class);
            i.putExtra("music_item", currentPlayingItem);
            startActivity(i);
            overridePendingTransition(R.anim.slide_in_bottom, R.anim.slide_out_top);
        } catch (Exception e) { Log.e(TAG, "Error opening NowPlaying", e); }
    }

    // ── Mini player ───────────────────────────────────────────────────────────

    private boolean initializeMiniPlayer() {
        try {
            miniPlayerContainer = findViewById(R.id.miniPlayerContainer);
            miniAlbumArt        = findViewById(R.id.miniAlbumArt);
            miniSongTitle       = findViewById(R.id.miniSongTitle);
            miniArtistName      = findViewById(R.id.miniArtistName);
            miniPlayPauseButton = findViewById(R.id.miniPlayPauseButton);
            miniNextButton      = findViewById(R.id.miniNextButton);
            miniCloseButton     = findViewById(R.id.miniCloseButton);

            if (miniPlayerContainer == null || miniAlbumArt == null ||
                    miniSongTitle == null || miniArtistName == null ||
                    miniPlayPauseButton == null || miniNextButton == null || miniCloseButton == null)
                return false;

            miniPlayerContainer.setOnClickListener(v -> { if (!isActivityDestroyed) openNowPlayingActivity(); });
            miniPlayPauseButton.setOnClickListener(v -> sendServiceAction(MusicService.ACTION_TOGGLE_PLAY_PAUSE));
            miniNextButton.setOnClickListener(v -> sendServiceAction(MusicService.ACTION_NEXT));
            miniCloseButton.setOnClickListener(v -> { sendServiceAction(MusicService.ACTION_STOP); hideMiniPlayer(); });

            return true;
        } catch (Exception e) {
            Log.e(TAG, "Error initializing mini player", e);
            return false;
        }
    }

    private void sendServiceAction(String action) {
        if (isActivityDestroyed) return;
        try {
            Intent i = new Intent(this, MusicService.class);
            i.setAction(action);
            startService(i);
        } catch (Exception e) { Log.e(TAG, "Service error: " + action, e); }
    }

    public void showMiniPlayer(MusicItem item) {
        if (isActivityDestroyed || isFinishing() || item == null) return;
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

    public void hideMiniPlayer() {
        if (isActivityDestroyed || !isMiniPlayerVisible || miniPlayerContainer == null) return;
        isMiniPlayerVisible = false;
        miniPlayerContainer.animate().translationY(miniPlayerContainer.getHeight()).setDuration(300)
                .withEndAction(() -> { if (!isActivityDestroyed) miniPlayerContainer.setVisibility(View.GONE); })
                .start();
    }

    public void updateMiniPlayerState(boolean playing) {
        if (isActivityDestroyed) return;
        isPlaying = playing;
        updateMiniPlayerPlayButton();
    }

    private void updateMiniPlayerPlayButton() {
        if (isActivityDestroyed || miniPlayPauseButton == null) return;
        miniPlayPauseButton.setIconResource(
                isPlaying ? R.drawable.ic_baseline_pause_24 : R.drawable.ic_baseline_play_arrow_24);
    }

    // ── BroadcastReceiver ─────────────────────────────────────────────────────

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private void registerMusicUpdateReceiver() {
        if (isReceiverRegistered) return;
        try {
            IntentFilter f = new IntentFilter();
            f.addAction(MusicService.ACTION_MUSIC_UPDATED);
            f.addAction(MusicService.ACTION_PLAYBACK_STATE_CHANGED);
            f.addAction(MusicService.ACTION_HIDE_MINI_PLAYER);
            f.addAction("MINI_PLAYER_VISIBILITY_CHANGED");

            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU)
                registerReceiver(musicUpdateReceiver, f, Context.RECEIVER_NOT_EXPORTED);
            else
                registerReceiver(musicUpdateReceiver, f);

            isReceiverRegistered = true;
        } catch (Exception e) { Log.e(TAG, "Error registering receiver", e); }
    }

    // ── Activity lifecycle ────────────────────────────────────────────────────

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) { onBackPressed(); return true; }
        return super.onOptionsItemSelected(item);
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
        if (isReceiverRegistered && musicUpdateReceiver != null) {
            try { unregisterReceiver(musicUpdateReceiver); }
            catch (Exception e) { Log.w(TAG, "Receiver unregister error", e); }
            finally { isReceiverRegistered = false; }
        }
        if (executorService != null && !executorService.isShutdown()) executorService.shutdown();
        currentPlayingItem  = null;
        musicUpdateReceiver = null;
        collectionSongs     = null;
        try { if (!isDestroyed()) Glide.with(this).clear(miniAlbumArt); } catch (Exception ignored) {}
    }
}
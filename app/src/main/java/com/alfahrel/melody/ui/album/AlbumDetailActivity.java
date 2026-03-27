package com.alfahrel.melody.ui.album;

import static com.alfahrel.melody.ui.collection.CollectionFragment.ACTION_COLLECTION_CREATED;
import static com.alfahrel.melody.ui.collection.CollectionFragment.ACTION_SONG_ADDED_TO_COLLECTION;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.RecoverableSecurityException;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Parcelable;
import android.provider.MediaStore;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.IntentSenderRequest;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.alfahrel.melody.utils.GsonHelper;
import com.alfahrel.melody.utils.SongDetailBottomSheet;
import com.alfahrel.melody.utils.SongOptionsBottomSheet;
import com.bumptech.glide.Glide;
import com.bumptech.glide.request.RequestOptions;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputEditText;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import com.alfahrel.melody.R;
import com.alfahrel.melody.ui.music.MusicAdapter;
import com.alfahrel.melody.ui.music.MusicItem;
import com.alfahrel.melody.ui.pages.nowplaying.NowPlayingActivity;
import com.alfahrel.melody.ui.pages.nowplaying.AddToCollectionAdapter;
import com.alfahrel.melody.ui.collection.Collection;
import com.alfahrel.melody.ui.collection.CollectionManager;
import com.alfahrel.melody.service.MusicService;

import java.io.File;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class AlbumDetailActivity extends AppCompatActivity {

    private static final String TAG = "AlbumDetailActivity";

    // ── Song pin persistence (mirrors MusicFragment) ──────────────────────────
    private static final String SONG_PREFS_NAME = "SongsPrefs";
    private static final String KEY_SONGS       = "songs_full";
    private Gson gson;
    private SharedPreferences songPrefs;

    private MaterialToolbar toolbar;
    private ImageView albumArtImageView;
    private TextView albumTitleTextView;
    private TextView albumArtistTextView;
    private TextView songCountTextView;
    private RecyclerView songsRecyclerView;
    private View loadingLayout;
    private View emptyState;
    private MaterialButton shuffleAlbumButton;
    private TextView totalDurationTextView;

    private MaterialCardView miniPlayerContainer;
    private ImageView miniAlbumArt;
    private TextView miniSongTitle;
    private TextView miniArtistName;
    private MaterialButton miniPlayPauseButton;
    private MaterialButton miniNextButton;
    private MaterialButton miniCloseButton;

    private AlbumItem albumItem;
    private List<MusicItem> albumSongs = new ArrayList<>();
    private MusicAdapter musicAdapter;
    private ExecutorService executorService;
    private boolean isLoading = false;
    private CollectionManager collectionManager;

    private MusicItem currentPlayingItem;
    private boolean isPlaying = false;
    private boolean isMiniPlayerVisible = false;
    private boolean isReceiverRegistered = false;
    private boolean isActivityDestroyed = false;

    private MusicItem pendingDeleteItem = null;
    private ActivityResultLauncher<IntentSenderRequest> deletePermissionLauncher;

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

    // ── Delete permission launcher ────────────────────────────────────────────

    private void setupDeletePermissionLauncher() {
        deletePermissionLauncher = registerForActivityResult(
                new ActivityResultContracts.StartIntentSenderForResult(),
                result -> {
                    if (result.getResultCode() == RESULT_OK) {
                        if (pendingDeleteItem != null) {
                            deleteSongAfterPermission(pendingDeleteItem);
                            pendingDeleteItem = null;
                        }
                    } else {
                        Toast.makeText(this, "Permission denied to delete file", Toast.LENGTH_SHORT).show();
                        pendingDeleteItem = null;
                    }
                });
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_album_detail);

        try {
            executorService   = Executors.newSingleThreadExecutor();
            collectionManager = new CollectionManager(this);
            gson              = GsonHelper.get();
            songPrefs         = getSharedPreferences(SONG_PREFS_NAME, Context.MODE_PRIVATE);

            if (!getAlbumDataFromIntent()) { finish(); return; }
            if (!initializeViews())        { finish(); return; }
            if (!initializeMiniPlayer())   { finish(); return; }

            setupToolbar();
            setupAlbumHeader();
            setupRecyclerView();
            setupShuffleButton();
            loadAlbumSongs();
            setupDeletePermissionLauncher();
            registerMusicUpdateReceiver();

        } catch (Exception e) {
            Log.e(TAG, "Error in onCreate: " + e.getMessage(), e);
            finish();
        }
    }

    // ── Song pin ──────────────────────────────────────────────────────────────

    private void toggleSongPin(MusicItem song) {
        executorService.execute(() -> {
            boolean nowPinned = false;
            for (MusicItem s : albumSongs) {
                if (s.getId() == song.getId()) {
                    nowPinned = !s.isPinned();
                    s.setPinned(nowPinned);
                    break;
                }
            }
            saveSongPinnedState();
            final boolean pinned = nowPinned;
            runOnUiThread(() -> {
                if (musicAdapter != null) musicAdapter.notifyDataSetChanged();
                Toast.makeText(this,
                        pinned ? "Pinned to Home" : "Unpinned from Home",
                        Toast.LENGTH_SHORT).show();
            });
        });
    }

    private void saveSongPinnedState() {
        String json = songPrefs.getString(KEY_SONGS, null);
        List<MusicItem> all = new ArrayList<>();
        if (json != null) {
            Type type = new TypeToken<List<MusicItem>>(){}.getType();
            List<MusicItem> saved = gson.fromJson(json, type);
            if (saved != null) all = saved;
        }
        Map<Long, Boolean> pinMap = new HashMap<>();
        for (MusicItem s : albumSongs) pinMap.put(s.getId(), s.isPinned());
        for (MusicItem s : all) {
            if (pinMap.containsKey(s.getId())) s.setPinned(pinMap.get(s.getId()));
        }
        songPrefs.edit().putString(KEY_SONGS, gson.toJson(all)).apply();
    }

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

    // ── Init helpers ──────────────────────────────────────────────────────────

    private boolean getAlbumDataFromIntent() {
        albumItem = getIntent().getParcelableExtra("album_item");
        if (albumItem == null) {
            Toast.makeText(this, "Error: Album not found", Toast.LENGTH_SHORT).show();
            return false;
        }
        return true;
    }

    private boolean initializeViews() {
        try {
            toolbar               = findViewById(R.id.toolbar);
            albumArtImageView     = findViewById(R.id.albumBackgroundImageView);
            albumTitleTextView    = findViewById(R.id.albumTitleTextView);
            albumArtistTextView   = findViewById(R.id.albumArtistTextView);
            songCountTextView     = findViewById(R.id.songCountTextView);
            songsRecyclerView     = findViewById(R.id.songsRecyclerView);
            loadingLayout         = findViewById(R.id.loadingLayout);
            emptyState            = findViewById(R.id.emptyState);
            shuffleAlbumButton    = findViewById(R.id.shuffleAlbumButton);
            totalDurationTextView = findViewById(R.id.totalDurationTextView);

            if (toolbar == null || albumArtImageView == null || albumTitleTextView == null ||
                    albumArtistTextView == null || songCountTextView == null ||
                    songsRecyclerView == null || loadingLayout == null || emptyState == null ||
                    shuffleAlbumButton == null) {
                Log.e(TAG, "One or more album views are null");
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

    private void setupAlbumHeader() {
        try {
            albumTitleTextView.setText(albumItem.getAlbumName());
            albumArtistTextView.setText(albumItem.getArtistName());
            if (albumItem.getAlbumArtUri() != null) {
                Glide.with(this)
                        .load(albumItem.getAlbumArtUri())
                        .apply(new RequestOptions()
                                .placeholder(R.drawable.ic_outline_album_24)
                                .error(R.drawable.ic_outline_album_24)
                                .centerCrop())
                        .into(albumArtImageView);
            } else {
                albumArtImageView.setImageResource(R.drawable.ic_outline_album_24);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error setting up album header", e);
        }
    }

    private void setupShuffleButton() {
        shuffleAlbumButton.setOnClickListener(v -> {
            if (albumSongs.isEmpty()) {
                Toast.makeText(this, "No songs to shuffle", Toast.LENGTH_SHORT).show();
                return;
            }
            int randomIndex = new Random().nextInt(albumSongs.size());
            MusicItem randomSong = albumSongs.get(randomIndex);

            Intent playlistIntent = new Intent(this, MusicService.class);
            playlistIntent.setAction(MusicService.ACTION_SET_PLAYLIST);
            playlistIntent.putParcelableArrayListExtra("playlist", new ArrayList<>(albumSongs));
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

    // ── RecyclerView ──────────────────────────────────────────────────────────

    private void setupRecyclerView() {
        try {
            musicAdapter = new MusicAdapter(albumSongs, this);
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

            // No long-press listener – vert icon handles everything.

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
                showAddToCollectionBottomSheet(item);
            }
            @Override public void onViewDetails(MusicItem item) {
                SongDetailBottomSheet.newInstance(item)
                        .show(getSupportFragmentManager(), "song_detail");
            }
            @Override public void onDelete(MusicItem item) {
                showDeleteConfirmationDialog(item);
            }
            @Override public void onPin(MusicItem item) {
                toggleSongPin(item);
            }
        });
        sheet.show(getSupportFragmentManager(), "song_options");
    }

    // ── Delete ────────────────────────────────────────────────────────────────

    private void showDeleteConfirmationDialog(MusicItem musicItem) {
        if (musicItem == null) return;
        new MaterialAlertDialogBuilder(this)
                .setTitle("Delete Song")
                .setMessage("Are you sure you want to delete \"" + musicItem.getTitle() + "\"? This action cannot be undone.")
                .setPositiveButton("Delete", (dialog, which) -> deleteSong(musicItem))
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void deleteSong(MusicItem musicItem) {
        if (musicItem == null) return;
        try {
            Uri uri = ContentUris.withAppendedId(
                    MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, musicItem.getId());

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                try {
                    int rows = getContentResolver().delete(uri, null, null);
                    if (rows > 0) onSongDeleteSuccess(musicItem);
                    else Toast.makeText(this, "Failed to delete song", Toast.LENGTH_SHORT).show();
                } catch (SecurityException se) {
                    if (se instanceof RecoverableSecurityException) {
                        pendingDeleteItem = musicItem;
                        deletePermissionLauncher.launch(new IntentSenderRequest.Builder(
                                ((RecoverableSecurityException) se).getUserAction()
                                        .getActionIntent().getIntentSender()).build());
                    } else {
                        Toast.makeText(this, "Permission denied.", Toast.LENGTH_SHORT).show();
                    }
                }
            } else {
                int rows = getContentResolver().delete(uri, null, null);
                if (rows > 0) { new File(musicItem.getPath()).delete(); onSongDeleteSuccess(musicItem); }
                else Toast.makeText(this, "Failed to delete song", Toast.LENGTH_SHORT).show();
            }
        } catch (Exception e) {
            Toast.makeText(this, "Error: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            Log.e(TAG, "Error deleting song", e);
        }
    }

    private void deleteSongAfterPermission(MusicItem musicItem) {
        if (musicItem == null) return;
        try {
            Uri uri = ContentUris.withAppendedId(
                    MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, musicItem.getId());
            int rows = getContentResolver().delete(uri, null, null);
            if (rows > 0) onSongDeleteSuccess(musicItem);
            else Toast.makeText(this, "Failed to delete song", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(this, "Error: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            Log.e(TAG, "Error deleting song after permission", e);
        }
    }

    private void onSongDeleteSuccess(MusicItem musicItem) {
        Toast.makeText(this, "Song deleted successfully", Toast.LENGTH_SHORT).show();
        albumSongs.remove(musicItem);
        if (musicAdapter != null) musicAdapter.notifyDataSetChanged();
        updateSongCountAndDuration();
        updateUI();
        if (currentPlayingItem != null && currentPlayingItem.getId() == musicItem.getId()) {
            Intent stop = new Intent(this, MusicService.class);
            stop.setAction(MusicService.ACTION_STOP);
            startService(stop);
        }
    }

    // ── Add to collection ─────────────────────────────────────────────────────

    private void showAddToCollectionBottomSheet(MusicItem musicItem) {
        if (musicItem == null) return;
        List<Collection> collections = collectionManager.getAllCollections();
        BottomSheetDialog dialog = new BottomSheetDialog(this);
        View view = LayoutInflater.from(this).inflate(R.layout.bottom_sheet_add_to_collection, null);
        RecyclerView rv = view.findViewById(R.id.collectionsRecyclerView);
        TextView emptyText = view.findViewById(R.id.emptyCollectionsText);
        View createBtn = view.findViewById(R.id.createNewCollectionButton);
        rv.setLayoutManager(new LinearLayoutManager(this));
        if (collections.isEmpty()) {
            rv.setVisibility(View.GONE); emptyText.setVisibility(View.VISIBLE);
        } else {
            rv.setVisibility(View.VISIBLE); emptyText.setVisibility(View.GONE);
            AddToCollectionAdapter adapter = new AddToCollectionAdapter(
                    collections, musicItem.getId(), collectionManager,
                    collection -> {
                        boolean added = collectionManager.addSongToCollection(collection.getId(), musicItem.getId());
                        if (added) {
                            Toast.makeText(this, "Added to " + collection.getName(), Toast.LENGTH_SHORT).show();
                            broadcastCollectionChange(ACTION_SONG_ADDED_TO_COLLECTION);
                            dialog.dismiss();
                        } else {
                            Toast.makeText(this, "Song already in " + collection.getName(), Toast.LENGTH_SHORT).show();
                        }
                    });
            rv.setAdapter(adapter);
        }
        createBtn.setOnClickListener(v -> { dialog.dismiss(); showCreateCollectionDialog(musicItem); });
        dialog.setContentView(view);
        dialog.show();
    }

    private void showCreateCollectionDialog(MusicItem musicItem) {
        View dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_add_collection, null);
        TextInputEditText editTextName = dialogView.findViewById(R.id.editTextCollectionName);
        new MaterialAlertDialogBuilder(this)
                .setTitle("New Collection").setView(dialogView)
                .setPositiveButton("Create", (dialog, which) -> {
                    String name = editTextName.getText() != null
                            ? editTextName.getText().toString().trim() : "";
                    if (!name.isEmpty()) createCollectionAndAddSong(name, musicItem);
                    else Toast.makeText(this, "Collection name cannot be empty", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("Cancel", null).show();
    }

    private void createCollectionAndAddSong(String collectionName, MusicItem musicItem) {
        if (musicItem == null) return;
        for (Collection c : collectionManager.getAllCollections()) {
            if (c.getName().equalsIgnoreCase(collectionName)) {
                Toast.makeText(this, "Collection already exists", Toast.LENGTH_SHORT).show();
                return;
            }
        }
        Collection newCollection = collectionManager.createCollection(collectionName);
        collectionManager.addSongToCollection(newCollection.getId(), musicItem.getId());
        Toast.makeText(this, "Created \"" + collectionName + "\" and added song", Toast.LENGTH_SHORT).show();
        broadcastCollectionChange(ACTION_COLLECTION_CREATED);
    }

    private void broadcastCollectionChange(String action) {
        try {
            Intent intent = new Intent(action);
            intent.setPackage(getPackageName());
            sendBroadcast(intent);
        } catch (Exception e) { e.printStackTrace(); }
    }

    // ── Playback ──────────────────────────────────────────────────────────────

    private void startMusicServiceAndOpenNowPlaying(MusicItem musicItem) {
        startMusicServiceWithPlaylist(musicItem);
        new android.os.Handler().postDelayed(() -> openNowPlaying(musicItem), 200);
    }

    private void startMusicServiceWithPlaylist(MusicItem selectedSong) {
        if (albumSongs.isEmpty()) return;
        int idx = 0;
        for (int i = 0; i < albumSongs.size(); i++)
            if (albumSongs.get(i).getId() == selectedSong.getId()) { idx = i; break; }

        Intent pl = new Intent(this, MusicService.class);
        pl.setAction(MusicService.ACTION_SET_PLAYLIST);
        pl.putParcelableArrayListExtra("playlist", new ArrayList<>(albumSongs));
        pl.putExtra("start_index", idx);
        startService(pl);

        Intent play = new Intent(this, MusicService.class);
        play.setAction(MusicService.ACTION_PLAY);
        play.putExtra("music_item", selectedSong);
        startService(play);
    }

    private void openNowPlaying(MusicItem musicItem) {
        Intent intent = new Intent(this, NowPlayingActivity.class);
        intent.putExtra("music_item", (Parcelable) musicItem);
        startActivity(intent);
        overridePendingTransition(R.anim.slide_in_bottom, R.anim.slide_out_top);
    }

    private void openNowPlayingActivity() {
        if (isActivityDestroyed || currentPlayingItem == null) return;
        try {
            Intent intent = new Intent(this, NowPlayingActivity.class);
            intent.putExtra("music_item", currentPlayingItem);
            startActivity(intent);
            overridePendingTransition(R.anim.slide_in_bottom, R.anim.slide_out_top);
        } catch (Exception e) { Log.e(TAG, "Error opening NowPlaying", e); }
    }

    // ── UI helpers ────────────────────────────────────────────────────────────

    private void updateSongCountAndDuration() {
        songCountTextView.setText(albumSongs.size() + " songs");
        long total = 0;
        for (MusicItem s : albumSongs) total += s.getDuration();
        if (totalDurationTextView != null) totalDurationTextView.setText(formatTotalDuration(total));
    }

    private String formatTotalDuration(long totalMs) {
        long s = totalMs / 1000, h = s / 3600, m = (s % 3600) / 60, sec = s % 60;
        if (h > 0)  return String.format("%dh %dm", h, m);
        if (m > 0)  return String.format("%dm %ds", m, sec);
        return String.format("%ds", sec);
    }

    private void updateUI() {
        boolean empty = albumSongs.isEmpty();
        emptyState.setVisibility(empty ? View.VISIBLE : View.GONE);
        songsRecyclerView.setVisibility(empty ? View.GONE : View.VISIBLE);
    }

    private void showLoading(boolean show) {
        if (show) {
            loadingLayout.setVisibility(View.VISIBLE);
            songsRecyclerView.setVisibility(View.GONE);
            emptyState.setVisibility(View.GONE);
        } else {
            loadingLayout.setVisibility(View.GONE);
        }
    }

    private void adjustRecyclerViewPadding(boolean isVisible, int height) {
        if (songsRecyclerView != null) {
            songsRecyclerView.setPadding(
                    songsRecyclerView.getPaddingLeft(),
                    songsRecyclerView.getPaddingTop(),
                    songsRecyclerView.getPaddingRight(),
                    isVisible ? height : 0);
        }
    }

    private boolean hasStoragePermission() {
        String perm = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                ? Manifest.permission.READ_MEDIA_AUDIO : Manifest.permission.READ_EXTERNAL_STORAGE;
        return ContextCompat.checkSelfPermission(this, perm) == PackageManager.PERMISSION_GRANTED;
    }

    // ── Load songs ────────────────────────────────────────────────────────────

    private void loadAlbumSongs() {
        if (!hasStoragePermission()) {
            Toast.makeText(this, "Storage permission required", Toast.LENGTH_SHORT).show();
            return;
        }
        if (isLoading) return;
        isLoading = true;
        showLoading(true);

        executorService.execute(() -> {
            List<MusicItem> temp = new ArrayList<>();
            Uri musicUri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;
            String[] projection = {
                    MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE,
                    MediaStore.Audio.Media.ARTIST, MediaStore.Audio.Media.ALBUM,
                    MediaStore.Audio.Media.DURATION, MediaStore.Audio.Media.DATA,
                    MediaStore.Audio.Media.ALBUM_ID, MediaStore.Audio.Media.TRACK
            };
            String selection = MediaStore.Audio.Media.IS_MUSIC + " != 0 AND "
                    + MediaStore.Audio.Media.ALBUM_ID + " = ?";
            String[] selArgs = { String.valueOf(albumItem.getAlbumId()) };
            String sortOrder = MediaStore.Audio.Media.TRACK + " ASC, "
                    + MediaStore.Audio.Media.TITLE + " ASC";

            try (Cursor cursor = getContentResolver().query(musicUri, projection, selection, selArgs, sortOrder)) {
                if (cursor != null && cursor.moveToFirst()) {
                    do {
                        long   id       = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID));
                        String title    = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE));
                        String artist   = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST));
                        String album    = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM));
                        long   duration = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION));
                        String path     = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA));
                        long   albumId  = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID));
                        temp.add(new MusicItem(id, title, artist, album, duration, path,
                                Uri.parse("content://media/external/audio/albumart/" + albumId)));
                    } while (cursor.moveToNext());
                }
            } catch (Exception e) {
                e.printStackTrace();
                runOnUiThread(() -> {
                    showLoading(false); isLoading = false;
                    Toast.makeText(this, "Error loading album songs", Toast.LENGTH_SHORT).show();
                });
                return;
            }

            restoreSongPinnedState(temp);

            runOnUiThread(() -> {
                showLoading(false);
                isLoading = false;
                albumSongs.clear();
                albumSongs.addAll(temp);
                updateSongCountAndDuration();
                if (musicAdapter != null) musicAdapter.notifyDataSetChanged();
                updateUI();
            });
        });
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
        if (isActivityDestroyed || isFinishing() || isDestroyed() || item == null) return;
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

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
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
        albumSongs          = null;
        try {
            if (!isDestroyed()) {
                Glide.with(this).clear(miniAlbumArt);
                Glide.with(this).clear(albumArtImageView);
            }
        } catch (Exception ignored) {}
    }
}
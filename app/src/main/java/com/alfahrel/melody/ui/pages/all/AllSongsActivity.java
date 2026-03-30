package com.alfahrel.melody.ui.pages.all;

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
import android.text.Editable;
import android.text.TextWatcher;
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

import com.alfahrel.melody.R;
import com.alfahrel.melody.databinding.ActivityAllSongsBinding;
import com.alfahrel.melody.service.MusicService;
import com.alfahrel.melody.ui.music.MusicAdapter;
import com.alfahrel.melody.ui.music.MusicItem;
import com.alfahrel.melody.ui.pages.nowplaying.AddToCollectionAdapter;
import com.alfahrel.melody.ui.pages.nowplaying.NowPlayingActivity;
import com.alfahrel.melody.ui.collection.Collection;
import com.alfahrel.melody.ui.collection.CollectionManager;
import com.alfahrel.melody.utils.GsonHelper;
import com.alfahrel.melody.utils.PlayCountManager;
import com.alfahrel.melody.utils.SongDetailBottomSheet;
import com.alfahrel.melody.utils.SongOptionsBottomSheet;
import com.bumptech.glide.Glide;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputEditText;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class AllSongsActivity extends AppCompatActivity {

    private static final String TAG = "AllSongsActivity";

    // ── Song pin persistence ──────────────────────────────────────────────────
    private static final String SONG_PREFS_NAME = "SongsPrefs";
    private static final String KEY_SONGS       = "songs_full";
    private Gson gson;
    private SharedPreferences songPrefs;

    private ActivityAllSongsBinding binding;
    private MusicAdapter adapter;
    private final List<MusicItem> allSongs      = new ArrayList<>();
    private final List<MusicItem> filteredSongs = new ArrayList<>();
    private ExecutorService executorService;
    private PlayCountManager playCountManager;
    private CollectionManager collectionManager;

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

    // Delete
    private MusicItem pendingDeleteItem = null;
    private ActivityResultLauncher<IntentSenderRequest> deletePermissionLauncher;

    private enum SortMode { TITLE_AZ, TITLE_ZA, RECENTLY_ADDED, MOST_PLAYED }
    private SortMode currentSort = SortMode.TITLE_AZ;

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
                    case "MINI_PLAYER_VISIBILITY_CHANGED":
                        adjustRecyclerViewPadding(
                                intent.getBooleanExtra("is_visible", false),
                                intent.getIntExtra("height", 0));
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
        binding = ActivityAllSongsBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        executorService  = Executors.newSingleThreadExecutor();
        playCountManager = new PlayCountManager(this);
        collectionManager = new CollectionManager(this);
        gson     = GsonHelper.get();
        songPrefs = getSharedPreferences(SONG_PREFS_NAME, Context.MODE_PRIVATE);

        setupDeletePermissionLauncher();
        setupToolbar();
        setupRecyclerView();
        setupSearch();
        initializeMiniPlayer();
        loadSongs();
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
        if (executorService != null && !executorService.isShutdown()) executorService.shutdown();
        try {
            if (!isDestroyed()) Glide.with(this).clear(miniAlbumArt);
        } catch (Exception ignored) {}
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) { finish(); return true; }
        return super.onOptionsItemSelected(item);
    }

    // ── Setup ─────────────────────────────────────────────────────────────────

    private void setupToolbar() {
        setSupportActionBar(binding.toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle("All Songs");
        }
    }

    private void setupRecyclerView() {
        adapter = new MusicAdapter(filteredSongs, this);
        binding.recyclerView.setLayoutManager(new LinearLayoutManager(this));
        binding.recyclerView.setAdapter(adapter);
        binding.recyclerView.setItemViewCacheSize(20);

        adapter.setOnMusicItemClickListener(new MusicAdapter.OnMusicItemClickListener() {
            @Override public void onMusicItemClick(MusicItem song) { playSong(song); }
            @Override public void onOptionClick(MusicItem song)    { showOptions(song); }
        });

        adapter.setOnMusicItemLongClickListener(song -> { showOptions(song); return true; });
    }

    private void setupSearch() {
        binding.searchEditText.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
            @Override public void onTextChanged(CharSequence s, int st, int b, int c) { applySort(); }
            @Override public void afterTextChanged(Editable s) {}
        });
    }


    private void setupDeletePermissionLauncher() {
        deletePermissionLauncher = registerForActivityResult(
                new ActivityResultContracts.StartIntentSenderForResult(),
                result -> {
                    if (result.getResultCode() == RESULT_OK && pendingDeleteItem != null) {
                        deleteSongAfterPermission(pendingDeleteItem);
                    } else {
                        Toast.makeText(this, "Permission denied to delete file", Toast.LENGTH_SHORT).show();
                    }
                    pendingDeleteItem = null;
                });
    }

    // ── Load ──────────────────────────────────────────────────────────────────

    private void loadSongs() {
        binding.progressBar.setVisibility(View.VISIBLE);
        executorService.execute(() -> {
            List<MusicItem> list = new ArrayList<>();
            if (hasPermission()) {
                String[] proj = {
                        MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE,
                        MediaStore.Audio.Media.ARTIST, MediaStore.Audio.Media.ALBUM,
                        MediaStore.Audio.Media.DURATION, MediaStore.Audio.Media.DATA,
                        MediaStore.Audio.Media.ALBUM_ID
                };
                try (Cursor c = getContentResolver().query(
                        MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, proj,
                        MediaStore.Audio.Media.IS_MUSIC + " != 0", null,
                        MediaStore.Audio.Media.TITLE + " ASC")) {
                    if (c != null && c.moveToFirst()) {
                        int colId  = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID);
                        int colT   = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE);
                        int colAr  = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST);
                        int colAl  = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM);
                        int colDur = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION);
                        int colP   = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA);
                        int colAid = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID);
                        do {
                            list.add(new MusicItem(
                                    c.getLong(colId), c.getString(colT), c.getString(colAr),
                                    c.getString(colAl), c.getLong(colDur), c.getString(colP),
                                    Uri.parse("content://media/external/audio/albumart/" + c.getLong(colAid))));
                        } while (c.moveToNext());
                    }
                } catch (Exception e) { e.printStackTrace(); }
            }
            restoreSongPinnedState(list);
            runOnUiThread(() -> {
                binding.progressBar.setVisibility(View.GONE);
                allSongs.clear();
                allSongs.addAll(list);
                applySort();
                updateUI();
            });
        });
    }

    // ── Sort / filter ─────────────────────────────────────────────────────────

    private void applySort() {
        List<MusicItem> sorted = new ArrayList<>(allSongs);
        switch (currentSort) {
            case TITLE_ZA:       sorted.sort((a, b) -> b.getTitle().compareToIgnoreCase(a.getTitle())); break;
            case RECENTLY_ADDED: sorted.sort((a, b) -> Long.compare(b.getId(), a.getId())); break;
            case MOST_PLAYED:    sorted.sort((a, b) -> Integer.compare(
                    playCountManager.getCount(b.getId()), playCountManager.getCount(a.getId()))); break;
            default:             sorted.sort((a, b) -> a.getTitle().compareToIgnoreCase(b.getTitle())); break;
        }
        String query = binding.searchEditText.getText() != null
                ? binding.searchEditText.getText().toString().trim() : "";
        filteredSongs.clear();
        if (query.isEmpty()) {
            filteredSongs.addAll(sorted);
        } else {
            String q = query.toLowerCase(Locale.getDefault());
            for (MusicItem s : sorted)
                if (s.getTitle().toLowerCase(Locale.getDefault()).contains(q)
                        || s.getArtist().toLowerCase(Locale.getDefault()).contains(q))
                    filteredSongs.add(s);
        }
        adapter.notifyDataSetChanged();
    }

    private void updateUI() {
        boolean empty = filteredSongs.isEmpty();
        binding.emptyState.setVisibility(empty ? View.VISIBLE : View.GONE);
        binding.recyclerView.setVisibility(empty ? View.GONE : View.VISIBLE);
    }

    // ── Playback ──────────────────────────────────────────────────────────────

    private void playSong(MusicItem song) {
        playCountManager.increment(song.getId());
        int idx = filteredSongs.indexOf(song);
        if (idx < 0) idx = 0;

        Intent pi = new Intent(this, MusicService.class);
        pi.setAction(MusicService.ACTION_SET_PLAYLIST);
        pi.putParcelableArrayListExtra("playlist", new ArrayList<>(filteredSongs));
        pi.putExtra("start_index", idx);
        startService(pi);

        Intent play = new Intent(this, MusicService.class);
        play.setAction(MusicService.ACTION_PLAY);
        play.putExtra("music_item", song);
        startService(play);

        new android.os.Handler().postDelayed(() -> {
            Intent intent = new Intent(this, NowPlayingActivity.class);
            intent.putExtra("music_item", (Parcelable) song);
            startActivity(intent);
            overridePendingTransition(R.anim.slide_in_bottom, R.anim.slide_out_top);
        }, 200);
    }

    private void openNowPlayingActivity() {
        if (isActivityDestroyed || currentPlayingItem == null) return;
        Intent intent = new Intent(this, NowPlayingActivity.class);
        intent.putExtra("music_item", currentPlayingItem);
        startActivity(intent);
        overridePendingTransition(R.anim.slide_in_bottom, R.anim.slide_out_top);
    }

    // ── Song options ──────────────────────────────────────────────────────────

    private void showOptions(MusicItem song) {
        if (song == null) return;
        SongOptionsBottomSheet sheet = SongOptionsBottomSheet.newInstance(song);
        sheet.setListener(new SongOptionsBottomSheet.SongOptionsListener() {
            @Override public void onAddToCollection(MusicItem item) { showAddToCollectionBottomSheet(item); }
            @Override public void onViewDetails(MusicItem item) {
                SongDetailBottomSheet.newInstance(item).show(getSupportFragmentManager(), "detail");
            }
            @Override public void onDelete(MusicItem item) { showDeleteConfirmationDialog(item); }
            @Override public void onPin(MusicItem item)    { toggleSongPin(item); }
        });
        sheet.show(getSupportFragmentManager(), "options");
    }

    // ── Pin ───────────────────────────────────────────────────────────────────

    private void toggleSongPin(MusicItem song) {
        executorService.execute(() -> {
            boolean nowPinned = false;
            for (MusicItem s : allSongs) {
                if (s.getId() == song.getId()) { nowPinned = !s.isPinned(); s.setPinned(nowPinned); break; }
            }
            // keep filteredSongs in sync
            for (MusicItem s : filteredSongs) {
                if (s.getId() == song.getId()) { s.setPinned(nowPinned); break; }
            }
            saveSongList();
            final boolean pinned = nowPinned;
            runOnUiThread(() -> {
                if (adapter != null) adapter.notifyDataSetChanged();
                Toast.makeText(this, pinned ? "Pinned to Home" : "Unpinned from Home", Toast.LENGTH_SHORT).show();
                broadcastChange("com.alfahrel.melody.SONG_PIN_CHANGED");
            });
        });
    }

    private void saveSongList() {
        songPrefs.edit().putString(KEY_SONGS, gson.toJson(allSongs)).apply();
    }

    private void restoreSongPinnedState(List<MusicItem> list) {
        String json = songPrefs.getString(KEY_SONGS, null);
        if (json == null) return;
        Type type = new TypeToken<List<MusicItem>>(){}.getType();
        List<MusicItem> saved = gson.fromJson(json, type);
        if (saved == null) return;
        Map<Long, Boolean> pinMap = new HashMap<>();
        for (MusicItem s : saved) pinMap.put(s.getId(), s.isPinned());
        for (MusicItem s : list) { Boolean p = pinMap.get(s.getId()); if (p != null) s.setPinned(p); }
    }

    // ── Delete ────────────────────────────────────────────────────────────────

    private void showDeleteConfirmationDialog(MusicItem song) {
        if (song == null) return;
        new MaterialAlertDialogBuilder(this)
                .setTitle("Delete song")
                .setMessage("Are you sure you want to delete \"" + song.getTitle() + "\"?")
                .setPositiveButton("Delete", (d, w) -> deleteSong(song))
                .setNegativeButton("Cancel", null).show();
    }

    private void deleteSong(MusicItem song) {
        if (song == null) return;
        try {
            Uri uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, song.getId());
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                try {
                    int rows = getContentResolver().delete(uri, null, null);
                    if (rows > 0) onSongDeleteSuccess(song);
                    else Toast.makeText(this, "Failed to delete song", Toast.LENGTH_SHORT).show();
                } catch (SecurityException se) {
                    if (se instanceof RecoverableSecurityException) {
                        pendingDeleteItem = song;
                        deletePermissionLauncher.launch(new IntentSenderRequest.Builder(
                                ((RecoverableSecurityException) se).getUserAction()
                                        .getActionIntent().getIntentSender()).build());
                    } else {
                        Toast.makeText(this, "Permission denied.", Toast.LENGTH_SHORT).show();
                    }
                }
            } else {
                int rows = getContentResolver().delete(uri, null, null);
                if (rows > 0) { new java.io.File(song.getPath()).delete(); onSongDeleteSuccess(song); }
                else Toast.makeText(this, "Failed to delete song", Toast.LENGTH_SHORT).show();
            }
        } catch (Exception e) {
            Toast.makeText(this, "Error: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            Log.e(TAG, "Delete error", e);
        }
    }

    private void deleteSongAfterPermission(MusicItem song) {
        try {
            Uri uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, song.getId());
            int rows = getContentResolver().delete(uri, null, null);
            if (rows > 0) onSongDeleteSuccess(song);
            else Toast.makeText(this, "Failed to delete song", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(this, "Error: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private void onSongDeleteSuccess(MusicItem song) {
        Toast.makeText(this, "Song deleted", Toast.LENGTH_SHORT).show();
        allSongs.remove(song);
        filteredSongs.remove(song);
        if (adapter != null) adapter.notifyDataSetChanged();
        updateUI();
        Intent intent = new Intent("SONG_DELETED");
        intent.putExtra("song_id", song.getId());
        intent.setPackage(getPackageName());
        sendBroadcast(intent);
        if (currentPlayingItem != null && currentPlayingItem.getId() == song.getId())
            sendServiceAction(MusicService.ACTION_STOP);
    }

    // ── Add to collection ─────────────────────────────────────────────────────

    private void showAddToCollectionBottomSheet(MusicItem song) {
        if (song == null) return;
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
            AddToCollectionAdapter colAdapter = new AddToCollectionAdapter(
                    collections, song.getId(), collectionManager,
                    col -> {
                        boolean added = collectionManager.addSongToCollection(col.getId(), song.getId());
                        if (added) {
                            Toast.makeText(this, "Added to " + col.getName(), Toast.LENGTH_SHORT).show();
                            broadcastChange(ACTION_SONG_ADDED_TO_COLLECTION);
                            dialog.dismiss();
                        } else {
                            Toast.makeText(this, "Song already in " + col.getName(), Toast.LENGTH_SHORT).show();
                        }
                    });
            rv.setAdapter(colAdapter);
        }
        createBtn.setOnClickListener(v -> { dialog.dismiss(); showCreateCollectionDialog(song); });
        dialog.setContentView(view);
        dialog.show();
    }

    private void showCreateCollectionDialog(MusicItem song) {
        View dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_add_collection, null);
        TextInputEditText editName = dialogView.findViewById(R.id.editTextCollectionName);
        new MaterialAlertDialogBuilder(this)
                .setTitle("New collection").setView(dialogView)
                .setPositiveButton("Create", (d, w) -> {
                    String name = editName.getText() != null ? editName.getText().toString().trim() : "";
                    if (!name.isEmpty()) createCollectionAndAddSong(name, song);
                    else Toast.makeText(this, "Collection name cannot be empty", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("Cancel", null).show();
    }

    private void createCollectionAndAddSong(String name, MusicItem song) {
        for (Collection c : collectionManager.getAllCollections()) {
            if (c.getName().equalsIgnoreCase(name)) {
                Toast.makeText(this, "Collection already exists", Toast.LENGTH_SHORT).show();
                return;
            }
        }
        Collection col = collectionManager.createCollection(name);
        collectionManager.addSongToCollection(col.getId(), song.getId());
        Toast.makeText(this, "Created \"" + name + "\" and added song", Toast.LENGTH_SHORT).show();
        broadcastChange(ACTION_COLLECTION_CREATED);
    }

    private void broadcastChange(String action) {
        try {
            Intent i = new Intent(action);
            i.setPackage(getPackageName());
            sendBroadcast(i);
        } catch (Exception e) { e.printStackTrace(); }
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

    private void sendServiceAction(String action) {
        if (isActivityDestroyed) return;
        Intent i = new Intent(this, MusicService.class);
        i.setAction(action);
        startService(i);
    }

    private void adjustRecyclerViewPadding(boolean visible, int height) {
        if (binding.recyclerView != null) {
            RecyclerView rv = binding.recyclerView;
            int base = (int) (120 * getResources().getDisplayMetrics().density);
            rv.setPadding(rv.getPaddingLeft(), rv.getPaddingTop(), rv.getPaddingRight(),
                    visible ? base + height : base);
        }
    }

    // ── Receiver ──────────────────────────────────────────────────────────────

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private void registerMusicUpdateReceiver() {
        if (isReceiverRegistered) return;
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
    }

    private boolean hasPermission() {
        String p = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                ? Manifest.permission.READ_MEDIA_AUDIO : Manifest.permission.READ_EXTERNAL_STORAGE;
        return ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED;
    }
}
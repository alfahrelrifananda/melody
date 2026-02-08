package com.alfahrel.melody.ui.collection;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
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

import com.bumptech.glide.Glide;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;

import com.alfahrel.melody.R;
import com.alfahrel.melody.ui.music.MusicAdapter;
import com.alfahrel.melody.ui.music.MusicItem;
import com.alfahrel.melody.ui.pages.nowplaying.NowPlayingActivity;
import com.alfahrel.melody.service.MusicService;
import com.google.android.material.snackbar.Snackbar;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class CollectionDetailActivity extends AppCompatActivity {

    private static final String TAG = "CollectionDetailActivity";

    private MaterialToolbar toolbar;
    private TextView collectionNameTextView;
    private TextView songCountTextView;
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
    private TextView totalDurationTextView;

    private BroadcastReceiver musicUpdateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (isActivityDestroyed || isFinishing() || isDestroyed()) {
                return;
            }

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
                            boolean playingState = intent.getBooleanExtra("is_playing", false);
                            updateMiniPlayerState(playingState);
                            break;
                        case MusicService.ACTION_HIDE_MINI_PLAYER:
                            hideMiniPlayer();
                            break;
                        case "MINI_PLAYER_VISIBILITY_CHANGED":
                            boolean isVisible = intent.getBooleanExtra("is_visible", false);
                            int height = intent.getIntExtra("height", 0);
                            adjustRecyclerViewPadding(isVisible, height);
                            break;
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "Error handling broadcast: " + e.getMessage(), e);
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_collection_detail);

        try {
            executorService = Executors.newSingleThreadExecutor();
            collectionManager = new CollectionManager(this);

            if (!getCollectionDataFromIntent()) {
                finish();
                return;
            }

            if (!initializeViews()) {
                Log.e(TAG, "Failed to initialize views");
                finish();
                return;
            }

            if (!initializeMiniPlayer()) {
                Log.e(TAG, "Failed to initialize mini player");
                finish();
                return;
            }

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
            toolbar = findViewById(R.id.toolbar);
            collectionNameTextView = findViewById(R.id.collectionNameTextView);
            songCountTextView = findViewById(R.id.songCountTextView);
            songsRecyclerView = findViewById(R.id.songsRecyclerView);
            loadingLayout = findViewById(R.id.loadingLayout);
            emptyState = findViewById(R.id.emptyState);
            shuffleCollectionButton = findViewById(R.id.shuffleCollectionButton);
            deleteCollectionButton = findViewById(R.id.deleteCollectionButton);
            totalDurationTextView = findViewById(R.id.totalDurationTextView);

            if (toolbar == null || collectionNameTextView == null ||
                    songCountTextView == null || songsRecyclerView == null ||
                    loadingLayout == null || emptyState == null ||
                    shuffleCollectionButton == null || deleteCollectionButton == null) {
                Log.e(TAG, "One or more collection views are null");
                return false;
            }

            return true;
        } catch (Exception e) {
            Log.e(TAG, "Error initializing views: " + e.getMessage(), e);
            return false;
        }
    }

    private String formatTotalDuration(long totalMilliseconds) {
        long totalSeconds = totalMilliseconds / 1000;
        long hours = totalSeconds / 3600;
        long minutes = (totalSeconds % 3600) / 60;
        long seconds = totalSeconds % 60;

        if (hours > 0) {
            return String.format("%dh %dm", hours, minutes);
        } else if (minutes > 0) {
            return String.format("%dm %ds", minutes, seconds);
        } else {
            return String.format("%ds", seconds);
        }
    }

    private void setupShuffleButton() {
        shuffleCollectionButton.setOnClickListener(v -> {
            if (collectionSongs.isEmpty()) {
                Toast.makeText(this, "No songs to shuffle", Toast.LENGTH_SHORT).show();
                return;
            }

            Random random = new Random();
            int randomIndex = random.nextInt(collectionSongs.size());
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
        deleteCollectionButton.setOnClickListener(v -> {
            new AlertDialog.Builder(this)
                    .setTitle("Delete Collection")
                    .setMessage("Are you sure you want to delete \"" + collection.getName() + "\"? This cannot be undone.")
                    .setPositiveButton("Delete", (dialog, which) -> {
                        boolean deleted = collectionManager.deleteCollection(collection.getId());
                        if (deleted) {
                            Toast.makeText(this, "Collection deleted", Toast.LENGTH_SHORT).show();
                            finish();
                        } else {
                            Toast.makeText(this, "Failed to delete collection", Toast.LENGTH_SHORT).show();
                        }
                    })
                    .setNegativeButton("Cancel", null)
                    .show();
        });
    }

    private boolean initializeMiniPlayer() {
        try {
            miniPlayerContainer = findViewById(R.id.miniPlayerContainer);
            miniAlbumArt = findViewById(R.id.miniAlbumArt);
            miniSongTitle = findViewById(R.id.miniSongTitle);
            miniArtistName = findViewById(R.id.miniArtistName);
            miniPlayPauseButton = findViewById(R.id.miniPlayPauseButton);
            miniNextButton = findViewById(R.id.miniNextButton);
            miniCloseButton = findViewById(R.id.miniCloseButton);

            if (miniPlayerContainer == null || miniAlbumArt == null ||
                    miniSongTitle == null || miniArtistName == null ||
                    miniPlayPauseButton == null || miniNextButton == null || miniCloseButton == null) {
                Log.e(TAG, "One or more mini player components are null");
                return false;
            }

            miniPlayerContainer.setOnClickListener(v -> {
                if (!isActivityDestroyed) {
                    openNowPlayingActivity();
                }
            });

            miniPlayPauseButton.setOnClickListener(v -> {
                if (!isActivityDestroyed) {
                    try {
                        Intent serviceIntent = new Intent(this, MusicService.class);
                        serviceIntent.setAction(MusicService.ACTION_TOGGLE_PLAY_PAUSE);
                        startService(serviceIntent);
                    } catch (Exception e) {
                        Log.e(TAG, "Error toggling play/pause: " + e.getMessage(), e);
                    }
                }
            });

            miniNextButton.setOnClickListener(v -> {
                if (!isActivityDestroyed) {
                    try {
                        Intent serviceIntent = new Intent(this, MusicService.class);
                        serviceIntent.setAction(MusicService.ACTION_NEXT);
                        startService(serviceIntent);
                    } catch (Exception e) {
                        Log.e(TAG, "Error playing next: " + e.getMessage(), e);
                    }
                }
            });

            miniCloseButton.setOnClickListener(v -> {
                if (!isActivityDestroyed) {
                    try {
                        Intent serviceIntent = new Intent(this, MusicService.class);
                        serviceIntent.setAction(MusicService.ACTION_STOP);
                        startService(serviceIntent);
                        hideMiniPlayer();
                    } catch (Exception e) {
                        Log.e(TAG, "Error stopping music: " + e.getMessage(), e);
                    }
                }
            });

            return true;
        } catch (Exception e) {
            Log.e(TAG, "Error initializing mini player: " + e.getMessage(), e);
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
            Log.e(TAG, "Error setting up toolbar: " + e.getMessage(), e);
        }
    }

    private void setupCollectionHeader() {
        try {
            collectionNameTextView.setText(collection.getName());

        } catch (Exception e) {
            Log.e(TAG, "Error setting up collection header: " + e.getMessage(), e);
        }
    }

    private void setupRecyclerView() {
        try {
            musicAdapter = new MusicAdapter(collectionSongs, this);
            songsRecyclerView.setLayoutManager(new LinearLayoutManager(this));
            songsRecyclerView.setAdapter(musicAdapter);

            musicAdapter.setOnMusicItemClickListener(new MusicAdapter.OnMusicItemClickListener() {
                @Override
                public void onMusicItemClick(MusicItem musicItem) {
                    startMusicServiceAndOpenNowPlaying(musicItem);
                }

                @Override
                public void onPlayButtonClick(MusicItem musicItem) {
                    startMusicService(musicItem);
                }
            });

            ItemTouchHelper itemTouchHelper = new ItemTouchHelper(new CollectionItemTouchHelperCallback());
            itemTouchHelper.attachToRecyclerView(songsRecyclerView);

        } catch (Exception e) {
            Log.e(TAG, "Error setting up RecyclerView: " + e.getMessage(), e);
        }
    }

    private class CollectionItemTouchHelperCallback extends ItemTouchHelper.Callback {

        private int dragFrom = -1;
        private int dragTo = -1;

        @Override
        public boolean isLongPressDragEnabled() {
            return true;
        }

        @Override
        public boolean isItemViewSwipeEnabled() {
            return true;
        }

        @Override
        public int getMovementFlags(RecyclerView recyclerView, RecyclerView.ViewHolder viewHolder) {
            int dragFlags = ItemTouchHelper.UP | ItemTouchHelper.DOWN;
            int swipeFlags = ItemTouchHelper.START | ItemTouchHelper.END;
            return makeMovementFlags(dragFlags, swipeFlags);
        }

        @Override
        public boolean onMove(RecyclerView recyclerView, RecyclerView.ViewHolder viewHolder,
                              RecyclerView.ViewHolder target) {
            int fromPosition = viewHolder.getAdapterPosition();
            int toPosition = target.getAdapterPosition();

            if (dragFrom == -1) {
                dragFrom = fromPosition;
            }
            dragTo = toPosition;

            if (fromPosition < toPosition) {
                for (int i = fromPosition; i < toPosition; i++) {
                    java.util.Collections.swap(collectionSongs, i, i + 1);
                }
            } else {
                for (int i = fromPosition; i > toPosition; i--) {
                    java.util.Collections.swap(collectionSongs, i, i - 1);
                }
            }

            if (musicAdapter != null) {
                musicAdapter.notifyItemMoved(fromPosition, toPosition);
            }

            return true;
        }

        @Override
        public void onSwiped(RecyclerView.ViewHolder viewHolder, int direction) {
            int position = viewHolder.getAdapterPosition();
            if (position >= 0 && position < collectionSongs.size()) {
                MusicItem removedItem = collectionSongs.get(position);

                collectionSongs.remove(position);
                if (musicAdapter != null) {
                    musicAdapter.notifyItemRemoved(position);
                }

                boolean removed = collectionManager.removeSongFromCollection(
                        collection.getId(),
                        removedItem.getId()
                );

                if (removed) {
                    songCountTextView.setText(collectionSongs.size() + " songs");

                    long totalDuration = 0;
                    for (MusicItem song : collectionSongs) {
                        totalDuration += song.getDuration();
                    }
                    totalDurationTextView.setText(formatTotalDuration(totalDuration));

                    if (collectionSongs.isEmpty()) {
                        updateUI();
                    }

                    Snackbar snackbar = Snackbar.make(
                            songsRecyclerView,
                            "Removed from collection",
                            Snackbar.LENGTH_LONG
                    );

                    snackbar.setAction("UNDO", v -> {
                        boolean added = collectionManager.addSongToCollection(
                                collection.getId(),
                                removedItem.getId()
                        );

                        if (added) {
                            collectionSongs.add(position, removedItem);
                            if (musicAdapter != null) {
                                musicAdapter.notifyItemInserted(position);
                            }
                            songCountTextView.setText(collectionSongs.size() + " songs");

                            long totalDurations = 0;
                            for (MusicItem song : collectionSongs) {
                                totalDurations += song.getDuration();
                            }
                            totalDurationTextView.setText(formatTotalDuration(totalDurations));

                            updateUI();

                            broadcastCollectionChange();
                        }
                    });

                    snackbar.show();

                    broadcastCollectionChange();
                } else {
                    Toast.makeText(
                            CollectionDetailActivity.this,
                            "Failed to remove song",
                            Toast.LENGTH_SHORT
                    ).show();

                    collectionSongs.add(position, removedItem);
                    if (musicAdapter != null) {
                        musicAdapter.notifyItemInserted(position);
                    }
                }
            }
        }

        @Override
        public void onSelectedChanged(RecyclerView.ViewHolder viewHolder, int actionState) {
            super.onSelectedChanged(viewHolder, actionState);

            if (actionState == ItemTouchHelper.ACTION_STATE_DRAG && viewHolder != null) {
                viewHolder.itemView.setAlpha(0.7f);
                viewHolder.itemView.setScaleX(1.05f);
                viewHolder.itemView.setScaleY(1.05f);
                viewHolder.itemView.setElevation(8f);
            }
        }

        @Override
        public void clearView(RecyclerView recyclerView, RecyclerView.ViewHolder viewHolder) {
            super.clearView(recyclerView, viewHolder);

            viewHolder.itemView.setAlpha(1.0f);
            viewHolder.itemView.setScaleX(1.0f);
            viewHolder.itemView.setScaleY(1.0f);
            viewHolder.itemView.setElevation(0f);

            if (dragFrom != -1 && dragTo != -1 && dragFrom != dragTo) {
                updateCollectionOrder();
            }

            dragFrom = -1;
            dragTo = -1;
        }

        @Override
        public void onChildDraw(Canvas c, RecyclerView recyclerView, RecyclerView.ViewHolder viewHolder,
                                float dX, float dY, int actionState, boolean isCurrentlyActive) {
            if (actionState == ItemTouchHelper.ACTION_STATE_SWIPE) {
                final float alpha = 1.0f - Math.abs(dX) / (float) viewHolder.itemView.getWidth();
                viewHolder.itemView.setAlpha(alpha);
                viewHolder.itemView.setTranslationX(dX);
            } else {
                super.onChildDraw(c, recyclerView, viewHolder, dX, dY, actionState, isCurrentlyActive);
            }
        }
    }

    private void updateCollectionOrder() {
        if (collectionSongs == null || collectionSongs.isEmpty()) {
            return;
        }

        try {
            List<Long> newOrder = new ArrayList<>();
            for (MusicItem item : collectionSongs) {
                newOrder.add(item.getId());
            }

            collection.setMusicIds(newOrder);
            collectionManager.updateCollection(collection);

            Log.d(TAG, "Collection order updated");
        } catch (Exception e) {
            Log.e(TAG, "Error updating collection order: " + e.getMessage(), e);
        }
    }

    private void broadcastCollectionChange() {
        try {
            Intent intent = new Intent("com.alfahrel.melody.COLLECTION_CHANGED");
            intent.setPackage(getPackageName());
            sendBroadcast(intent);
            Log.d(TAG, "Broadcast sent: Collection changed");
        } catch (Exception e) {
            Log.e(TAG, "Error broadcasting collection change: " + e.getMessage(), e);
        }
    }

    private void loadCollectionSongs() {
        if (!hasStoragePermission()) {
            Toast.makeText(this, "Storage permission required to load songs", Toast.LENGTH_SHORT).show();
            return;
        }

        if (isLoading) return;

        isLoading = true;
        showLoading(true);

        executorService.execute(() -> {
            List<MusicItem> tempSongsList = new ArrayList<>();
            List<Long> musicIds = collection.getMusicIds();

            if (musicIds == null || musicIds.isEmpty()) {
                runOnUiThread(() -> {
                    showLoading(false);
                    isLoading = false;
                    updateUI();
                });
                return;
            }

            ContentResolver contentResolver = getContentResolver();
            Uri musicUri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;

            String[] projection = {
                    MediaStore.Audio.Media._ID,
                    MediaStore.Audio.Media.TITLE,
                    MediaStore.Audio.Media.ARTIST,
                    MediaStore.Audio.Media.ALBUM,
                    MediaStore.Audio.Media.DURATION,
                    MediaStore.Audio.Media.DATA,
                    MediaStore.Audio.Media.ALBUM_ID
            };

            for (Long musicId : musicIds) {
                String selection = MediaStore.Audio.Media._ID + " = ?";
                String[] selectionArgs = {String.valueOf(musicId)};

                try (Cursor cursor = contentResolver.query(musicUri, projection, selection, selectionArgs, null)) {
                    if (cursor != null && cursor.moveToFirst()) {
                        int idColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID);
                        int titleColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE);
                        int artistColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST);
                        int albumColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM);
                        int durationColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION);
                        int pathColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA);
                        int albumIdColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID);

                        long id = cursor.getLong(idColumn);
                        String title = cursor.getString(titleColumn);
                        String artist = cursor.getString(artistColumn);
                        String album = cursor.getString(albumColumn);
                        long duration = cursor.getLong(durationColumn);
                        String path = cursor.getString(pathColumn);
                        long albumId = cursor.getLong(albumIdColumn);

                        Uri albumArtUri = Uri.parse("content://media/external/audio/albumart/" + albumId);

                        MusicItem musicItem = new MusicItem(id, title, artist, album, duration, path, albumArtUri);
                        tempSongsList.add(musicItem);
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Error loading song with ID: " + musicId, e);
                }
            }

            runOnUiThread(() -> {
                showLoading(false);
                isLoading = false;

                collectionSongs.clear();
                collectionSongs.addAll(tempSongsList);

                songCountTextView.setText(collectionSongs.size() + " songs");

                long totalDuration = 0;
                for (MusicItem song : collectionSongs) {
                    totalDuration += song.getDuration();
                }
                totalDurationTextView.setText(formatTotalDuration(totalDuration));

                if (musicAdapter != null) {
                    musicAdapter.notifyDataSetChanged();
                }


                updateUI();
            });
        });
    }

    private boolean hasStoragePermission() {
        String permission;
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            permission = Manifest.permission.READ_MEDIA_AUDIO;
        } else {
            permission = Manifest.permission.READ_EXTERNAL_STORAGE;
        }
        return ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED;
    }

    private void startMusicServiceAndOpenNowPlaying(MusicItem musicItem) {
        startMusicServiceWithPlaylist(musicItem);

        new android.os.Handler().postDelayed(() -> {
            openNowPlaying(musicItem);
        }, 200);
    }

    private void startMusicService(MusicItem musicItem) {
        startMusicServiceWithPlaylist(musicItem);
    }

    private void startMusicServiceWithPlaylist(MusicItem selectedSong) {
        if (collectionSongs.isEmpty()) {
            return;
        }

        int selectedIndex = -1;
        for (int i = 0; i < collectionSongs.size(); i++) {
            if (collectionSongs.get(i).getId() == selectedSong.getId()) {
                selectedIndex = i;
                break;
            }
        }

        if (selectedIndex == -1) {
            selectedIndex = 0;
        }

        Intent playlistIntent = new Intent(this, MusicService.class);
        playlistIntent.setAction(MusicService.ACTION_SET_PLAYLIST);
        playlistIntent.putParcelableArrayListExtra("playlist", new ArrayList<>(collectionSongs));
        playlistIntent.putExtra("start_index", selectedIndex);
        startService(playlistIntent);

        Intent playIntent = new Intent(this, MusicService.class);
        playIntent.setAction(MusicService.ACTION_PLAY);
        playIntent.putExtra("music_item", selectedSong);
        startService(playIntent);
    }

    private void openNowPlaying(MusicItem musicItem) {
        Intent intent = new Intent(this, NowPlayingActivity.class);
        intent.putExtra("music_item", (Parcelable) musicItem);
        startActivity(intent);

        overridePendingTransition(
                R.anim.slide_in_bottom,
                R.anim.slide_out_top
        );
    }

    private void openNowPlayingActivity() {
        if (isActivityDestroyed || currentPlayingItem == null) {
            return;
        }

        try {
            Intent intent = new Intent(this, NowPlayingActivity.class);
            intent.putExtra("music_item", currentPlayingItem);
            startActivity(intent);

            overridePendingTransition(
                    R.anim.slide_in_bottom,
                    R.anim.slide_out_top
            );
        } catch (Exception e) {
            Log.e(TAG, "Error opening now playing activity: " + e.getMessage(), e);
        }
    }

    private void updateUI() {
        if (collectionSongs.isEmpty()) {
            emptyState.setVisibility(View.VISIBLE);
            songsRecyclerView.setVisibility(View.GONE);
        } else {
            emptyState.setVisibility(View.GONE);
            songsRecyclerView.setVisibility(View.VISIBLE);
        }
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
                    isVisible ? height : 0
            );

            songsRecyclerView.post(() -> {
                if (songsRecyclerView.getAdapter() != null) {
                    songsRecyclerView.getAdapter().notifyDataSetChanged();
                }
            });
        }
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private void registerMusicUpdateReceiver() {
        try {
            if (!isReceiverRegistered && musicUpdateReceiver != null) {
                IntentFilter filter = new IntentFilter();
                filter.addAction(MusicService.ACTION_MUSIC_UPDATED);
                filter.addAction(MusicService.ACTION_PLAYBACK_STATE_CHANGED);
                filter.addAction(MusicService.ACTION_HIDE_MINI_PLAYER);
                filter.addAction("MINI_PLAYER_VISIBILITY_CHANGED");

                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                    registerReceiver(musicUpdateReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
                } else {
                    registerReceiver(musicUpdateReceiver, filter);
                }

                isReceiverRegistered = true;
            }
        } catch (Exception e) {
            Log.e(TAG, "Error registering broadcast receiver: " + e.getMessage(), e);
            isReceiverRegistered = false;
        }
    }

    public void showMiniPlayer(MusicItem musicItem) {
        if (isActivityDestroyed || isFinishing() || isDestroyed() || musicItem == null) {
            return;
        }

        try {
            currentPlayingItem = musicItem;

            if (miniSongTitle == null || miniArtistName == null ||
                    miniAlbumArt == null || miniPlayerContainer == null) {
                Log.e(TAG, "Mini player components are null");
                return;
            }

            miniSongTitle.setText(musicItem.getTitle());
            miniArtistName.setText(musicItem.getArtist());

            try {
                Glide.with(this)
                        .load(musicItem.getAlbumArtUri())
                        .placeholder(R.drawable.ic_outline_music_note_24)
                        .error(R.drawable.ic_outline_music_note_24)
                        .into(miniAlbumArt);

            } catch (Exception e) {
                Log.e(TAG, "Error loading album art: " + e.getMessage(), e);
            }

            if (!isMiniPlayerVisible) {
                isMiniPlayerVisible = true;
                miniPlayerContainer.setVisibility(View.VISIBLE);
                miniPlayerContainer.setTranslationY(miniPlayerContainer.getHeight());
                miniPlayerContainer.animate()
                        .translationY(0)
                        .setDuration(300)
                        .start();
            }

            updateMiniPlayerPlayButton();
        } catch (Exception e) {
            Log.e(TAG, "Error showing mini player: " + e.getMessage(), e);
        }
    }

    public void hideMiniPlayer() {
        if (isActivityDestroyed || isFinishing() || isDestroyed()) {
            return;
        }

        try {
            if (isMiniPlayerVisible && miniPlayerContainer != null) {
                isMiniPlayerVisible = false;
                miniPlayerContainer.animate()
                        .translationY(miniPlayerContainer.getHeight())
                        .setDuration(300)
                        .withEndAction(() -> {
                            if (!isActivityDestroyed && miniPlayerContainer != null) {
                                miniPlayerContainer.setVisibility(View.GONE);
                            }
                        })
                        .start();
            }
        } catch (Exception e) {
            Log.e(TAG, "Error hiding mini player: " + e.getMessage(), e);
        }
    }

    public void updateMiniPlayerState(boolean playing) {
        if (isActivityDestroyed) {
            return;
        }

        try {
            isPlaying = playing;
            updateMiniPlayerPlayButton();

        } catch (Exception e) {
            Log.e(TAG, "Error updating mini player state: " + e.getMessage(), e);
        }
    }

    private void updateMiniPlayerPlayButton() {
        if (isActivityDestroyed || miniPlayPauseButton == null) {
            return;
        }

        try {
            int iconRes = isPlaying ? R.drawable.ic_baseline_pause_24 : R.drawable.ic_baseline_play_arrow_24;
            miniPlayPauseButton.setIconResource(iconRes);
        } catch (Exception e) {
            Log.e(TAG, "Error updating play button: " + e.getMessage(), e);
        }
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            onBackPressed();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    protected void onResume() {
        super.onResume();

        try {
            Intent serviceIntent = new Intent(this, MusicService.class);
            serviceIntent.setAction(MusicService.ACTION_REQUEST_STATE);
            startService(serviceIntent);

        } catch (Exception e) {
            Log.e(TAG, "Error in onResume: " + e.getMessage(), e);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        try {
            if (miniPlayerContainer != null) {
                miniPlayerContainer.clearAnimation();
            }
        } catch (Exception e) {
            Log.e(TAG, "Error in onPause: " + e.getMessage(), e);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();

        isActivityDestroyed = true;

        if (isReceiverRegistered && musicUpdateReceiver != null) {
            try {
                unregisterReceiver(musicUpdateReceiver);
            } catch (IllegalArgumentException e) {
                Log.w(TAG, "Receiver was not registered or already unregistered");
            } catch (Exception e) {
                Log.e(TAG, "Error unregistering receiver: " + e.getMessage(), e);
            } finally {
                isReceiverRegistered = false;
            }
        }

        if (executorService != null && !executorService.isShutdown()) {
            executorService.shutdown();
        }

        try {
            currentPlayingItem = null;
            musicUpdateReceiver = null;
            collectionSongs = null;

            if (!isDestroyed()) {
                Glide.with(this).clear(miniAlbumArt);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error clearing references: " + e.getMessage(), e);
        }
    }
}
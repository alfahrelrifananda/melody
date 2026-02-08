package com.alfahrel.melody.ui.artist;

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

import com.bumptech.glide.Glide;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputEditText;

import com.alfahrel.melody.R;
import com.alfahrel.melody.ui.music.MusicAdapter;
import com.alfahrel.melody.ui.music.MusicItem;
import com.alfahrel.melody.ui.pages.nowplaying.NowPlayingActivity;
import com.alfahrel.melody.ui.pages.nowplaying.AddToCollectionAdapter;
import com.alfahrel.melody.ui.collection.Collection;
import com.alfahrel.melody.ui.collection.CollectionManager;
import com.alfahrel.melody.service.MusicService;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ArtistDetailActivity extends AppCompatActivity {

    private static final String TAG = "ArtistDetailActivity";
    private static final int REQUEST_CODE_DELETE_PERMISSION = 1001;

    private MaterialToolbar toolbar;
    private TextView artistNameTextView;
    private TextView songCountTextView;
    private RecyclerView songsRecyclerView;
    private View loadingLayout;
    private View emptyState;
    private MaterialButton shuffleArtistButton;
    private TextView totalDurationTextView;

    private MaterialCardView miniPlayerContainer;
    private ImageView miniAlbumArt;
    private TextView miniSongTitle;
    private TextView miniArtistName;
    private MaterialButton miniPlayPauseButton;
    private MaterialButton miniNextButton;
    private MaterialButton miniCloseButton;

    private ArtistItem artistItem;
    private List<MusicItem> artistSongs = new ArrayList<>();
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
                        Toast.makeText(this,
                                "Permission denied to delete file",
                                Toast.LENGTH_SHORT).show();
                        pendingDeleteItem = null;
                    }
                }
        );
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_artist_detail);

        try {
            executorService = Executors.newSingleThreadExecutor();
            collectionManager = new CollectionManager(this);

            if (!getArtistDataFromIntent()) {
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
            setupArtistHeader();
            setupRecyclerView();
            setupShuffleButton();

            loadArtistSongs();

            setupDeletePermissionLauncher();
            registerMusicUpdateReceiver();

        } catch (Exception e) {
            Log.e(TAG, "Error in onCreate: " + e.getMessage(), e);
            finish();
        }
    }

    private boolean getArtistDataFromIntent() {
        artistItem = getIntent().getParcelableExtra("artist_item");
        if (artistItem == null) {
            Toast.makeText(this, "Error: Artist not found", Toast.LENGTH_SHORT).show();
            return false;
        }
        return true;
    }

    private boolean initializeViews() {
        try {
            toolbar = findViewById(R.id.toolbar);
            artistNameTextView = findViewById(R.id.artistNameTextView);
            songCountTextView = findViewById(R.id.songCountTextView);
            songsRecyclerView = findViewById(R.id.songsRecyclerView);
            loadingLayout = findViewById(R.id.loadingLayout);
            emptyState = findViewById(R.id.emptyState);
            shuffleArtistButton = findViewById(R.id.shuffleArtistButton);
            totalDurationTextView = findViewById(R.id.totalDurationTextView);

            if (toolbar == null || artistNameTextView == null ||
                    songCountTextView == null || songsRecyclerView == null ||
                    loadingLayout == null || emptyState == null || shuffleArtistButton == null) {
                Log.e(TAG, "One or more artist views are null");
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
        shuffleArtistButton.setOnClickListener(v -> {
            if (artistSongs.isEmpty()) {
                Toast.makeText(this, "No songs to shuffle", Toast.LENGTH_SHORT).show();
                return;
            }

            Random random = new Random();
            int randomIndex = random.nextInt(artistSongs.size());
            MusicItem randomSong = artistSongs.get(randomIndex);

            Intent playlistIntent = new Intent(this, MusicService.class);
            playlistIntent.setAction(MusicService.ACTION_SET_PLAYLIST);
            playlistIntent.putParcelableArrayListExtra("playlist", new ArrayList<>(artistSongs));
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

    private void setupArtistHeader() {
        try {
            artistNameTextView.setText(artistItem.getArtistName());
        } catch (Exception e) {
            Log.e(TAG, "Error setting up artist header: " + e.getMessage(), e);
        }
    }

    private void setupRecyclerView() {
        try {
            musicAdapter = new MusicAdapter(artistSongs, this);
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

            // Set long click listener to show options dialog
            musicAdapter.setOnMusicItemLongClickListener(musicItem -> {
                showSongOptionsDialog(musicItem);
                return true;
            });
        } catch (Exception e) {
            Log.e(TAG, "Error setting up RecyclerView: " + e.getMessage(), e);
        }
    }

    private void showSongOptionsDialog(MusicItem musicItem) {
        if (musicItem == null) {
            return;
        }

        String[] options = {"View Details", "Add to Collection", "Delete"};

        new MaterialAlertDialogBuilder(this)
                .setTitle(musicItem.getTitle())
                .setItems(options, (dialog, which) -> {
                    switch (which) {
                        case 0: // View Details
                            showSongDetailDialog(musicItem);
                            break;
                        case 1: // Add to Collection
                            showAddToCollectionBottomSheet(musicItem);
                            break;
                        case 2: // Delete
                            showDeleteConfirmationDialog(musicItem);
                            break;
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void showSongDetailDialog(MusicItem musicItem) {
        if (musicItem == null) {
            return;
        }

        View dialogView = LayoutInflater.from(this)
                .inflate(R.layout.dialog_song_details, null);

        TextView titleText = dialogView.findViewById(R.id.detailTitleText);
        TextView artistText = dialogView.findViewById(R.id.detailArtistText);
        TextView albumText = dialogView.findViewById(R.id.detailAlbumText);
        TextView durationText = dialogView.findViewById(R.id.detailDurationText);
        TextView pathText = dialogView.findViewById(R.id.detailPathText);
        TextView fileSizeText = dialogView.findViewById(R.id.detailFileSizeText);

        titleText.setText(musicItem.getTitle());
        artistText.setText(musicItem.getArtist());
        albumText.setText(musicItem.getAlbum());
        durationText.setText(formatDuration(musicItem.getDuration()));
        pathText.setText(musicItem.getPath());

        // Get file size
        File file = new File(musicItem.getPath());
        if (file.exists()) {
            long fileSizeInBytes = file.length();
            String fileSize = formatFileSize(fileSizeInBytes);
            fileSizeText.setText(fileSize);
        } else {
            fileSizeText.setText("Unknown");
        }

        new MaterialAlertDialogBuilder(this)
                .setView(dialogView)
                .setPositiveButton("Close", null)
                .show();
    }

    private String formatDuration(long duration) {
        long seconds = (duration / 1000) % 60;
        long minutes = (duration / (1000 * 60)) % 60;
        long hours = (duration / (1000 * 60 * 60));

        if (hours > 0) {
            return String.format("%02d:%02d:%02d", hours, minutes, seconds);
        } else {
            return String.format("%02d:%02d", minutes, seconds);
        }
    }

    private String formatFileSize(long size) {
        if (size <= 0) return "0 B";

        final String[] units = new String[]{"B", "KB", "MB", "GB"};
        int digitGroups = (int) (Math.log10(size) / Math.log10(1024));

        return String.format("%.2f %s",
                size / Math.pow(1024, digitGroups),
                units[digitGroups]);
    }

    private void showDeleteConfirmationDialog(MusicItem musicItem) {
        if (musicItem == null) {
            return;
        }

        new MaterialAlertDialogBuilder(this)
                .setTitle("Delete Song")
                .setMessage("Are you sure you want to delete \"" + musicItem.getTitle() + "\"? This action cannot be undone.")
                .setPositiveButton("Delete", (dialog, which) -> {
                    deleteSong(musicItem);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void deleteSong(MusicItem musicItem) {
        if (musicItem == null) {
            return;
        }

        try {
            Uri uri = ContentUris.withAppendedId(
                    MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                    musicItem.getId()
            );

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                try {
                    int rowsDeleted = getContentResolver().delete(uri, null, null);
                    if (rowsDeleted > 0) {
                        onSongDeleteSuccess(musicItem);
                    } else {
                        Toast.makeText(this, "Failed to delete song", Toast.LENGTH_SHORT).show();
                    }
                } catch (SecurityException securityException) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        RecoverableSecurityException recoverableException;
                        if (securityException instanceof RecoverableSecurityException) {
                            recoverableException = (RecoverableSecurityException) securityException;

                            pendingDeleteItem = musicItem;
                            IntentSenderRequest intentSenderRequest = new IntentSenderRequest.Builder(
                                    recoverableException.getUserAction().getActionIntent().getIntentSender()
                            ).build();
                            deletePermissionLauncher.launch(intentSenderRequest);
                        } else {
                            throw securityException;
                        }
                    } else {
                        throw securityException;
                    }
                }
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                try {
                    int rowsDeleted = getContentResolver().delete(uri, null, null);
                    if (rowsDeleted > 0) {
                        onSongDeleteSuccess(musicItem);
                    } else {
                        Toast.makeText(this, "Failed to delete song", Toast.LENGTH_SHORT).show();
                    }
                } catch (SecurityException securityException) {
                    RecoverableSecurityException recoverableException;
                    if (securityException instanceof RecoverableSecurityException) {
                        recoverableException = (RecoverableSecurityException) securityException;

                        pendingDeleteItem = musicItem;
                        IntentSenderRequest intentSenderRequest = new IntentSenderRequest.Builder(
                                recoverableException.getUserAction().getActionIntent().getIntentSender()
                        ).build();
                        deletePermissionLauncher.launch(intentSenderRequest);
                    } else {
                        Toast.makeText(this,
                                "Permission denied. Cannot delete this file.",
                                Toast.LENGTH_SHORT).show();
                    }
                }
            } else {
                int rowsDeleted = getContentResolver().delete(uri, null, null);

                if (rowsDeleted > 0) {
                    File file = new File(musicItem.getPath());
                    file.delete();
                    onSongDeleteSuccess(musicItem);
                } else {
                    Toast.makeText(this, "Failed to delete song", Toast.LENGTH_SHORT).show();
                }
            }

        } catch (Exception e) {
            Toast.makeText(this, "Error deleting song: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            Log.e(TAG, "Error deleting song: " + e.getMessage(), e);
        }
    }

    private void deleteSongAfterPermission(MusicItem musicItem) {
        if (musicItem == null) {
            return;
        }

        try {
            Uri uri = ContentUris.withAppendedId(
                    MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                    musicItem.getId()
            );

            int rowsDeleted = getContentResolver().delete(uri, null, null);

            if (rowsDeleted > 0) {
                onSongDeleteSuccess(musicItem);
            } else {
                Toast.makeText(this, "Failed to delete song", Toast.LENGTH_SHORT).show();
            }
        } catch (Exception e) {
            Toast.makeText(this, "Error deleting song: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            Log.e(TAG, "Error deleting song after permission: " + e.getMessage(), e);
        }
    }

    private void onSongDeleteSuccess(MusicItem musicItem) {
        Toast.makeText(this, "Song deleted successfully", Toast.LENGTH_SHORT).show();

        artistSongs.remove(musicItem);
        if (musicAdapter != null) {
            musicAdapter.notifyDataSetChanged();
        }

        songCountTextView.setText(artistSongs.size() + " songs");

        long totalDuration = 0;
        for (MusicItem song : artistSongs) {
            totalDuration += song.getDuration();
        }
        totalDurationTextView.setText(formatTotalDuration(totalDuration));

        updateUI();

        // If the deleted song is currently playing, stop it
        if (currentPlayingItem != null &&
                currentPlayingItem.getId() == musicItem.getId()) {
            Intent stopIntent = new Intent(this, MusicService.class);
            stopIntent.setAction(MusicService.ACTION_STOP);
            startService(stopIntent);
        }
    }

    private void showAddToCollectionBottomSheet(MusicItem musicItem) {
        if (musicItem == null) {
            return;
        }

        List<Collection> collections = collectionManager.getAllCollections();

        BottomSheetDialog bottomSheetDialog = new BottomSheetDialog(this);
        View view = LayoutInflater.from(this).inflate(
                R.layout.bottom_sheet_add_to_collection,
                null
        );

        RecyclerView collectionsRecyclerView = view.findViewById(R.id.collectionsRecyclerView);
        TextView emptyCollectionsText = view.findViewById(R.id.emptyCollectionsText);
        MaterialButton createNewCollectionButton = view.findViewById(R.id.createNewCollectionButton);

        collectionsRecyclerView.setLayoutManager(new LinearLayoutManager(this));

        if (collections.isEmpty()) {
            collectionsRecyclerView.setVisibility(View.GONE);
            emptyCollectionsText.setVisibility(View.VISIBLE);
        } else {
            collectionsRecyclerView.setVisibility(View.VISIBLE);
            emptyCollectionsText.setVisibility(View.GONE);

            AddToCollectionAdapter adapter = new AddToCollectionAdapter(
                    collections,
                    musicItem.getId(),
                    collectionManager,
                    collection -> {
                        boolean added = collectionManager.addSongToCollection(
                                collection.getId(),
                                musicItem.getId()
                        );
                        if (added) {
                            Toast.makeText(this,
                                    "Added to " + collection.getName(),
                                    Toast.LENGTH_SHORT).show();
                            broadcastCollectionChange(ACTION_SONG_ADDED_TO_COLLECTION);
                            bottomSheetDialog.dismiss();
                        } else {
                            Toast.makeText(this,
                                    "Song already in " + collection.getName(),
                                    Toast.LENGTH_SHORT).show();
                        }
                    }
            );
            collectionsRecyclerView.setAdapter(adapter);
        }

        createNewCollectionButton.setOnClickListener(v -> {
            bottomSheetDialog.dismiss();
            showCreateCollectionDialog(musicItem);
        });

        bottomSheetDialog.setContentView(view);
        bottomSheetDialog.show();
    }

    private void showCreateCollectionDialog(MusicItem musicItem) {
        View dialogView = LayoutInflater.from(this)
                .inflate(R.layout.dialog_add_collection, null);

        TextInputEditText editTextName = dialogView.findViewById(R.id.editTextCollectionName);

        new MaterialAlertDialogBuilder(this)
                .setTitle("New Collection")
                .setView(dialogView)
                .setPositiveButton("Create", (dialog, which) -> {
                    String collectionName = editTextName.getText().toString().trim();
                    if (!collectionName.isEmpty()) {
                        createCollectionAndAddSong(collectionName, musicItem);
                    } else {
                        Toast.makeText(this,
                                "Collection name cannot be empty",
                                Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void createCollectionAndAddSong(String collectionName, MusicItem musicItem) {
        if (musicItem == null) {
            return;
        }

        List<Collection> existingCollections = collectionManager.getAllCollections();
        for (Collection collection : existingCollections) {
            if (collection.getName().equalsIgnoreCase(collectionName)) {
                Toast.makeText(this,
                        "Collection already exists",
                        Toast.LENGTH_SHORT).show();
                return;
            }
        }

        Collection newCollection = collectionManager.createCollection(collectionName);

        boolean added = collectionManager.addSongToCollection(
                newCollection.getId(),
                musicItem.getId()
        );

        if (added) {
            Toast.makeText(this,
                    "Created \"" + collectionName + "\" and added song",
                    Toast.LENGTH_SHORT).show();
            broadcastCollectionChange(ACTION_COLLECTION_CREATED);
        } else {
            Toast.makeText(this,
                    "Collection created",
                    Toast.LENGTH_SHORT).show();
            broadcastCollectionChange(ACTION_COLLECTION_CREATED);
        }
    }

    private void broadcastCollectionChange(String action) {
        try {
            Intent intent = new Intent(action);
            intent.setPackage(getPackageName());
            sendBroadcast(intent);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void loadArtistSongs() {
        if (!hasStoragePermission()) {
            Toast.makeText(this, "Storage permission required to load songs", Toast.LENGTH_SHORT).show();
            return;
        }

        if (isLoading) return;

        isLoading = true;
        showLoading(true);

        executorService.execute(() -> {
            List<MusicItem> tempSongsList = new ArrayList<>();

            ContentResolver contentResolver = getContentResolver();
            Uri musicUri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;

            String[] projection = {
                    MediaStore.Audio.Media._ID,
                    MediaStore.Audio.Media.TITLE,
                    MediaStore.Audio.Media.ARTIST,
                    MediaStore.Audio.Media.ALBUM,
                    MediaStore.Audio.Media.DURATION,
                    MediaStore.Audio.Media.DATA,
                    MediaStore.Audio.Media.ALBUM_ID,
                    MediaStore.Audio.Media.TRACK
            };

            String selection = MediaStore.Audio.Media.IS_MUSIC + " != 0 AND " +
                    MediaStore.Audio.Media.ARTIST + " = ?";
            String[] selectionArgs = {artistItem.getArtistName()};
            String sortOrder = MediaStore.Audio.Media.ALBUM + " ASC, " +
                    MediaStore.Audio.Media.TRACK + " ASC, " +
                    MediaStore.Audio.Media.TITLE + " ASC";

            try (Cursor cursor = contentResolver.query(musicUri, projection, selection, selectionArgs, sortOrder)) {
                if (cursor != null && cursor.moveToFirst()) {
                    int idColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID);
                    int titleColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE);
                    int artistColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST);
                    int albumColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM);
                    int durationColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION);
                    int pathColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA);
                    int albumIdColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID);

                    do {
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

                    } while (cursor.moveToNext());
                }
            } catch (Exception e) {
                e.printStackTrace();
                runOnUiThread(() -> {
                    showLoading(false);
                    isLoading = false;
                    Toast.makeText(this, "Error loading artist songs", Toast.LENGTH_SHORT).show();
                });
                return;
            }

            runOnUiThread(() -> {
                showLoading(false);
                isLoading = false;

                artistSongs.clear();
                artistSongs.addAll(tempSongsList);

                songCountTextView.setText(artistSongs.size() + " songs");

                long totalDuration = 0;
                for (MusicItem song : artistSongs) {
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
        if (artistSongs.isEmpty()) {
            return;
        }

        int selectedIndex = -1;
        for (int i = 0; i < artistSongs.size(); i++) {
            if (artistSongs.get(i).getId() == selectedSong.getId()) {
                selectedIndex = i;
                break;
            }
        }

        if (selectedIndex == -1) {
            selectedIndex = 0;
        }

        Intent playlistIntent = new Intent(this, MusicService.class);
        playlistIntent.setAction(MusicService.ACTION_SET_PLAYLIST);
        playlistIntent.putParcelableArrayListExtra("playlist", new ArrayList<>(artistSongs));
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
        if (artistSongs.isEmpty()) {
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
            artistSongs = null;

            if (!isDestroyed()) {
                Glide.with(this).clear(miniAlbumArt);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error clearing references: " + e.getMessage(), e);
        }
    }
}
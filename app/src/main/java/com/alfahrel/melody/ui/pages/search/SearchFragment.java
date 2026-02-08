package com.alfahrel.melody.ui.pages.search;

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
import android.os.Handler;
import android.os.Looper;
import android.os.Parcelable;
import android.provider.MediaStore;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.inputmethod.InputMethodManager;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.IntentSenderRequest;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputEditText;

import com.alfahrel.melody.R;
import com.alfahrel.melody.databinding.SearchFragmentBinding;
import com.alfahrel.melody.service.MusicService;
import com.alfahrel.melody.ui.music.MusicAdapter;
import com.alfahrel.melody.ui.music.MusicItem;
import com.alfahrel.melody.ui.pages.nowplaying.NowPlayingActivity;
import com.alfahrel.melody.ui.pages.nowplaying.AddToCollectionAdapter;
import com.alfahrel.melody.ui.collection.Collection;
import com.alfahrel.melody.ui.collection.CollectionManager;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static com.alfahrel.melody.ui.collection.CollectionFragment.ACTION_COLLECTION_CREATED;
import static com.alfahrel.melody.ui.collection.CollectionFragment.ACTION_SONG_ADDED_TO_COLLECTION;

public class SearchFragment extends Fragment {

    private static final String TAG = "SearchFragment";

    private SearchFragmentBinding binding;
    private MusicAdapter searchAdapter;
    private List<MusicItem> allMusicList = new ArrayList<>();
    private List<MusicItem> searchResults = new ArrayList<>();
    private ExecutorService executorService;
    private Handler searchHandler;
    private Runnable searchRunnable;
    private static final int PERMISSION_REQUEST_CODE = 124;
    private static final int SEARCH_DELAY = 300;
    private boolean isSearching = false;
    private CollectionManager collectionManager;

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
    private boolean isFragmentDestroyed = false;

    private MusicItem pendingDeleteItem = null;
    private ActivityResultLauncher<IntentSenderRequest> deletePermissionLauncher;

    private BroadcastReceiver miniPlayerReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (isFragmentDestroyed) {
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
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        binding = SearchFragmentBinding.inflate(inflater, container, false);
        View root = binding.getRoot();

        executorService = Executors.newSingleThreadExecutor();
        searchHandler = new Handler(Looper.getMainLooper());
        collectionManager = new CollectionManager(requireContext());

        setupDeletePermissionLauncher();
        initializeMiniPlayer();
        setupRecyclerView();
        setupSearchView();
        loadAllMusic();

        return root;
    }

    private void setupDeletePermissionLauncher() {
        deletePermissionLauncher = registerForActivityResult(
                new ActivityResultContracts.StartIntentSenderForResult(),
                result -> {
                    if (result.getResultCode() == getActivity().RESULT_OK) {
                        if (pendingDeleteItem != null) {
                            deleteSongAfterPermission(pendingDeleteItem);
                            pendingDeleteItem = null;
                        }
                    } else {
                        Toast.makeText(requireContext(),
                                "Permission denied to delete file",
                                Toast.LENGTH_SHORT).show();
                        pendingDeleteItem = null;
                    }
                }
        );
    }

    private void initializeMiniPlayer() {
        try {
            miniPlayerContainer = binding.miniPlayerContainer;
            miniAlbumArt = binding.miniAlbumArt;
            miniSongTitle = binding.miniSongTitle;
            miniArtistName = binding.miniArtistName;
            miniPlayPauseButton = binding.miniPlayPauseButton;
            miniNextButton = binding.miniNextButton;
            miniCloseButton = binding.miniCloseButton;

            miniPlayerContainer.setOnClickListener(v -> {
                if (!isFragmentDestroyed) {
                    openNowPlayingActivity();
                }
            });

            miniPlayPauseButton.setOnClickListener(v -> {
                if (!isFragmentDestroyed && getActivity() != null) {
                    try {
                        Intent serviceIntent = new Intent(getActivity(), MusicService.class);
                        serviceIntent.setAction(MusicService.ACTION_TOGGLE_PLAY_PAUSE);
                        getActivity().startService(serviceIntent);
                    } catch (Exception e) {
                        Log.e(TAG, "Error toggling play/pause: " + e.getMessage(), e);
                    }
                }
            });

            miniNextButton.setOnClickListener(v -> {
                if (!isFragmentDestroyed && getActivity() != null) {
                    try {
                        Intent serviceIntent = new Intent(getActivity(), MusicService.class);
                        serviceIntent.setAction(MusicService.ACTION_NEXT);
                        getActivity().startService(serviceIntent);
                    } catch (Exception e) {
                        Log.e(TAG, "Error playing next: " + e.getMessage(), e);
                    }
                }
            });

            miniCloseButton.setOnClickListener(v -> {
                if (!isFragmentDestroyed && getActivity() != null) {
                    try {
                        Intent serviceIntent = new Intent(getActivity(), MusicService.class);
                        serviceIntent.setAction(MusicService.ACTION_STOP);
                        getActivity().startService(serviceIntent);
                        hideMiniPlayer();
                    } catch (Exception e) {
                        Log.e(TAG, "Error stopping music: " + e.getMessage(), e);
                    }
                }
            });

        } catch (Exception e) {
            Log.e(TAG, "Error initializing mini player: " + e.getMessage(), e);
        }
    }

    private void setupRecyclerView() {
        searchAdapter = new MusicAdapter(searchResults, getContext());
        binding.searchRecyclerView.setLayoutManager(new LinearLayoutManager(getContext()));
        binding.searchRecyclerView.setAdapter(searchAdapter);

        searchAdapter.setOnMusicItemClickListener(new MusicAdapter.OnMusicItemClickListener() {
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
        searchAdapter.setOnMusicItemLongClickListener(musicItem -> {
            showSongOptionsDialog(musicItem);
            return true;
        });
    }

    private void showSongOptionsDialog(MusicItem musicItem) {
        if (getContext() == null || musicItem == null) {
            return;
        }

        String[] options = {"View Details", "Add to Collection", "Delete"};

        new MaterialAlertDialogBuilder(requireContext())
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
        if (getContext() == null || musicItem == null) {
            return;
        }

        View dialogView = LayoutInflater.from(requireContext())
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

        new MaterialAlertDialogBuilder(requireContext())
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
        if (getContext() == null || musicItem == null) {
            return;
        }

        new MaterialAlertDialogBuilder(requireContext())
                .setTitle("Delete Song")
                .setMessage("Are you sure you want to delete \"" + musicItem.getTitle() + "\"? This action cannot be undone.")
                .setPositiveButton("Delete", (dialog, which) -> {
                    deleteSong(musicItem);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void deleteSong(MusicItem musicItem) {
        if (getContext() == null || musicItem == null) {
            return;
        }

        try {
            Uri uri = ContentUris.withAppendedId(
                    MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                    musicItem.getId()
            );

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                try {
                    int rowsDeleted = requireContext().getContentResolver().delete(uri, null, null);
                    if (rowsDeleted > 0) {
                        onSongDeleteSuccess(musicItem);
                    } else {
                        Toast.makeText(requireContext(), "Failed to delete song", Toast.LENGTH_SHORT).show();
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
                    int rowsDeleted = requireContext().getContentResolver().delete(uri, null, null);
                    if (rowsDeleted > 0) {
                        onSongDeleteSuccess(musicItem);
                    } else {
                        Toast.makeText(requireContext(), "Failed to delete song", Toast.LENGTH_SHORT).show();
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
                        Toast.makeText(requireContext(),
                                "Permission denied. Cannot delete this file.",
                                Toast.LENGTH_SHORT).show();
                    }
                }
            } else {
                int rowsDeleted = requireContext().getContentResolver().delete(uri, null, null);

                if (rowsDeleted > 0) {
                    File file = new File(musicItem.getPath());
                    file.delete();
                    onSongDeleteSuccess(musicItem);
                } else {
                    Toast.makeText(requireContext(), "Failed to delete song", Toast.LENGTH_SHORT).show();
                }
            }

        } catch (Exception e) {
            Toast.makeText(requireContext(), "Error deleting song: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            Log.e(TAG, "Error deleting song: " + e.getMessage(), e);
        }
    }

    private void deleteSongAfterPermission(MusicItem musicItem) {
        if (getContext() == null || musicItem == null) {
            return;
        }

        try {
            Uri uri = ContentUris.withAppendedId(
                    MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                    musicItem.getId()
            );

            int rowsDeleted = requireContext().getContentResolver().delete(uri, null, null);

            if (rowsDeleted > 0) {
                onSongDeleteSuccess(musicItem);
            } else {
                Toast.makeText(requireContext(), "Failed to delete song", Toast.LENGTH_SHORT).show();
            }
        } catch (Exception e) {
            Toast.makeText(requireContext(), "Error deleting song: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            Log.e(TAG, "Error deleting song after permission: " + e.getMessage(), e);
        }
    }

    private void onSongDeleteSuccess(MusicItem musicItem) {
        Toast.makeText(requireContext(), "Song deleted successfully", Toast.LENGTH_SHORT).show();

        // Remove from search results
        searchResults.remove(musicItem);

        // Also remove from all music list
        allMusicList.remove(musicItem);

        if (searchAdapter != null) {
            searchAdapter.notifyDataSetChanged();
        }

        // Update UI based on current search state
        String currentQuery = binding.searchEditText.getText().toString().trim();
        updateUI(currentQuery);

        // Broadcast to update other fragments/activities
        Intent intent = new Intent("SONG_DELETED");
        intent.putExtra("song_id", musicItem.getId());
        intent.setPackage(requireContext().getPackageName());
        requireContext().sendBroadcast(intent);
    }

    private void showAddToCollectionBottomSheet(MusicItem musicItem) {
        if (getContext() == null || musicItem == null) {
            return;
        }

        List<Collection> collections = collectionManager.getAllCollections();

        BottomSheetDialog bottomSheetDialog = new BottomSheetDialog(requireContext());
        View view = LayoutInflater.from(requireContext()).inflate(
                R.layout.bottom_sheet_add_to_collection,
                null
        );

        RecyclerView collectionsRecyclerView = view.findViewById(R.id.collectionsRecyclerView);
        android.widget.TextView emptyCollectionsText = view.findViewById(R.id.emptyCollectionsText);
        MaterialButton createNewCollectionButton = view.findViewById(R.id.createNewCollectionButton);

        collectionsRecyclerView.setLayoutManager(new LinearLayoutManager(requireContext()));

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
                            Toast.makeText(requireContext(),
                                    "Added to " + collection.getName(),
                                    Toast.LENGTH_SHORT).show();
                            broadcastCollectionChange(ACTION_SONG_ADDED_TO_COLLECTION);
                            bottomSheetDialog.dismiss();
                        } else {
                            Toast.makeText(requireContext(),
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
        if (getContext() == null) {
            return;
        }

        View dialogView = LayoutInflater.from(requireContext())
                .inflate(R.layout.dialog_add_collection, null);

        TextInputEditText editTextName = dialogView.findViewById(R.id.editTextCollectionName);

        new MaterialAlertDialogBuilder(requireContext())
                .setTitle("New Collection")
                .setView(dialogView)
                .setPositiveButton("Create", (dialog, which) -> {
                    String collectionName = editTextName.getText().toString().trim();
                    if (!collectionName.isEmpty()) {
                        createCollectionAndAddSong(collectionName, musicItem);
                    } else {
                        Toast.makeText(requireContext(),
                                "Collection name cannot be empty",
                                Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void createCollectionAndAddSong(String collectionName, MusicItem musicItem) {
        if (getContext() == null || musicItem == null) {
            return;
        }

        List<Collection> existingCollections = collectionManager.getAllCollections();
        for (Collection collection : existingCollections) {
            if (collection.getName().equalsIgnoreCase(collectionName)) {
                Toast.makeText(requireContext(),
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
            Toast.makeText(requireContext(),
                    "Created \"" + collectionName + "\" and added song",
                    Toast.LENGTH_SHORT).show();
            broadcastCollectionChange(ACTION_COLLECTION_CREATED);
        } else {
            Toast.makeText(requireContext(),
                    "Collection created",
                    Toast.LENGTH_SHORT).show();
            broadcastCollectionChange(ACTION_COLLECTION_CREATED);
        }
    }

    private void broadcastCollectionChange(String action) {
        if (getContext() == null) {
            return;
        }

        try {
            Intent intent = new Intent(action);
            intent.setPackage(requireContext().getPackageName());
            requireContext().sendBroadcast(intent);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void setupSearchView() {
        binding.searchEditText.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {}

            @Override
            public void afterTextChanged(Editable s) {
                String query = s.toString().trim();

                binding.clearButton.setVisibility(query.isEmpty() ? View.GONE : View.VISIBLE);

                if (searchRunnable != null) {
                    searchHandler.removeCallbacks(searchRunnable);
                }

                searchRunnable = () -> performSearch(query);
                searchHandler.postDelayed(searchRunnable, SEARCH_DELAY);
            }
        });

        binding.clearButton.setOnClickListener(v -> {
            binding.searchEditText.setText("");
            binding.searchEditText.requestFocus();
            showKeyboard();
        });

        binding.searchEditText.requestFocus();

        binding.searchEditText.getViewTreeObserver().addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener() {
            @Override
            public void onGlobalLayout() {
                binding.searchEditText.getViewTreeObserver().removeOnGlobalLayoutListener(this);
                showKeyboard();
            }
        });
    }

    private void showKeyboard() {
        if (getActivity() != null && !isFragmentDestroyed && binding != null) {
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                if (binding != null && binding.searchEditText != null) {
                    binding.searchEditText.requestFocus();
                    InputMethodManager imm = (InputMethodManager) requireContext().getSystemService(Context.INPUT_METHOD_SERVICE);
                    if (imm != null) {
                        imm.showSoftInput(binding.searchEditText, InputMethodManager.SHOW_FORCED);
                        imm.toggleSoftInput(InputMethodManager.SHOW_FORCED, InputMethodManager.HIDE_IMPLICIT_ONLY);
                    }
                }
            }, 100);
        }
    }

    private void performSearch(String query) {
        if (query.isEmpty()) {
            showInitialState();
            return;
        }

        if (isSearching) return;

        isSearching = true;
        showLoading(true);

        executorService.execute(() -> {
            List<MusicItem> results = new ArrayList<>();
            String lowerQuery = query.toLowerCase();

            for (MusicItem item : allMusicList) {
                if (item.getTitle().toLowerCase().contains(lowerQuery) ||
                        item.getArtist().toLowerCase().contains(lowerQuery) ||
                        item.getAlbum().toLowerCase().contains(lowerQuery)) {
                    results.add(item);
                }
            }

            if (getActivity() != null) {
                getActivity().runOnUiThread(() -> {
                    showLoading(false);
                    isSearching = false;
                    updateSearchResults(results, query);
                });
            }
        });
    }

    private void updateSearchResults(List<MusicItem> results, String query) {
        if (binding == null) return;

        searchResults.clear();
        searchResults.addAll(results);
        searchAdapter.notifyDataSetChanged();

        updateUI(query);
    }

    private void updateUI(String query) {
        if (binding == null) return;

        if (query.isEmpty()) {
            showInitialState();
        } else if (searchResults.isEmpty()) {
            showNoResults(query);
        } else {
            showResults();
        }
    }

    private void showInitialState() {
        binding.searchRecyclerView.setVisibility(View.GONE);
        binding.emptyState.setVisibility(View.GONE);
        binding.initialState.setVisibility(View.VISIBLE);
        binding.loadingIndicator.setVisibility(View.GONE);
    }

    private void showResults() {
        binding.searchRecyclerView.setVisibility(View.VISIBLE);
        binding.emptyState.setVisibility(View.GONE);
        binding.initialState.setVisibility(View.GONE);
        binding.loadingIndicator.setVisibility(View.GONE);
    }

    private void showNoResults(String query) {
        binding.searchRecyclerView.setVisibility(View.GONE);
        binding.emptyState.setVisibility(View.VISIBLE);
        binding.initialState.setVisibility(View.GONE);
        binding.loadingIndicator.setVisibility(View.GONE);

        binding.emptyStateText.setText("No results found for \"" + query + "\"");
        binding.emptyStateSubtext.setText("Try searching with different keywords");
    }

    private void showLoading(boolean show) {
        if (binding == null) return;
        binding.loadingIndicator.setVisibility(show ? View.VISIBLE : View.GONE);
    }

    private void loadAllMusic() {
        checkPermissionAndLoadMusic();
    }

    private void checkPermissionAndLoadMusic() {
        String permission;
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            permission = Manifest.permission.READ_MEDIA_AUDIO;
        } else {
            permission = Manifest.permission.READ_EXTERNAL_STORAGE;
        }

        if (ContextCompat.checkSelfPermission(requireContext(), permission)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(requireActivity(),
                    new String[]{permission}, PERMISSION_REQUEST_CODE);
        } else {
            loadMusicFromDevice();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                loadMusicFromDevice();
            } else {
                Toast.makeText(getContext(), "Permission denied. Cannot search music files.",
                        Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void loadMusicFromDevice() {
        executorService.execute(() -> {
            List<MusicItem> tempMusicList = new ArrayList<>();

            ContentResolver contentResolver = requireContext().getContentResolver();
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

            String selection = MediaStore.Audio.Media.IS_MUSIC + " != 0";
            String sortOrder = MediaStore.Audio.Media.TITLE + " ASC";

            try (Cursor cursor = contentResolver.query(musicUri, projection, selection, null, sortOrder)) {
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
                        tempMusicList.add(musicItem);

                    } while (cursor.moveToNext());
                }
            } catch (Exception e) {
                e.printStackTrace();
                if (getActivity() != null) {
                    getActivity().runOnUiThread(() -> {
                        Toast.makeText(getContext(), "Error loading music files", Toast.LENGTH_SHORT).show();
                    });
                }
                return;
            }

            if (getActivity() != null) {
                getActivity().runOnUiThread(() -> {
                    allMusicList.clear();
                    allMusicList.addAll(tempMusicList);
                });
            }
        });
    }

    private void startMusicServiceAndOpenNowPlaying(MusicItem musicItem) {
        startMusicServiceWithPlaylist(musicItem);

        new Handler().postDelayed(() -> {
            openNowPlaying(musicItem);
        }, 200);
    }

    private void startMusicService(MusicItem musicItem) {
        startMusicServiceWithPlaylist(musicItem);
    }

    private void startMusicServiceWithPlaylist(MusicItem selectedSong) {
        if (getContext() == null || searchResults.isEmpty()) {
            return;
        }

        int selectedIndex = -1;
        for (int i = 0; i < searchResults.size(); i++) {
            if (searchResults.get(i).getId() == selectedSong.getId()) {
                selectedIndex = i;
                break;
            }
        }

        if (selectedIndex == -1) {
            selectedIndex = 0;
        }

        Intent playlistIntent = new Intent(getContext(), MusicService.class);
        playlistIntent.setAction(MusicService.ACTION_SET_PLAYLIST);
        playlistIntent.putParcelableArrayListExtra("playlist", new ArrayList<>(searchResults));
        playlistIntent.putExtra("start_index", selectedIndex);
        getContext().startService(playlistIntent);

        Intent playIntent = new Intent(getContext(), MusicService.class);
        playIntent.setAction(MusicService.ACTION_PLAY);
        playIntent.putExtra("music_item", selectedSong);
        getContext().startService(playIntent);
    }

    private void openNowPlaying(MusicItem musicItem) {
        Intent intent = new Intent(getContext(), NowPlayingActivity.class);
        intent.putExtra("music_item", (Parcelable) musicItem);
        startActivity(intent);

        if (getActivity() != null) {
            getActivity().overridePendingTransition(
                    R.anim.slide_in_bottom,
                    R.anim.slide_out_top
            );
        }
    }

    private void openNowPlayingActivity() {
        if (isFragmentDestroyed || currentPlayingItem == null || getActivity() == null) {
            return;
        }

        try {
            Intent intent = new Intent(getActivity(), NowPlayingActivity.class);
            intent.putExtra("music_item", currentPlayingItem);
            startActivity(intent);

            getActivity().overridePendingTransition(
                    R.anim.slide_in_bottom,
                    R.anim.slide_out_top
            );
        } catch (Exception e) {
            Log.e(TAG, "Error opening now playing activity: " + e.getMessage(), e);
        }
    }

    public void showMiniPlayer(MusicItem musicItem) {
        if (isFragmentDestroyed || getActivity() == null || musicItem == null) {
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
        if (isFragmentDestroyed) {
            return;
        }

        try {
            if (isMiniPlayerVisible && miniPlayerContainer != null) {
                isMiniPlayerVisible = false;
                miniPlayerContainer.animate()
                        .translationY(miniPlayerContainer.getHeight())
                        .setDuration(300)
                        .withEndAction(() -> {
                            if (!isFragmentDestroyed && miniPlayerContainer != null) {
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
        if (isFragmentDestroyed) {
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
        if (isFragmentDestroyed || miniPlayPauseButton == null) {
            return;
        }

        try {
            int iconRes = isPlaying ? R.drawable.ic_baseline_pause_24 : R.drawable.ic_baseline_play_arrow_24;
            miniPlayPauseButton.setIconResource(iconRes);
        } catch (Exception e) {
            Log.e(TAG, "Error updating play button: " + e.getMessage(), e);
        }
    }

    private void adjustRecyclerViewPadding(boolean isVisible, int height) {
        if (binding != null && binding.searchRecyclerView != null) {
            RecyclerView recyclerView = binding.searchRecyclerView;
            recyclerView.setPadding(
                    recyclerView.getPaddingLeft(),
                    recyclerView.getPaddingTop(),
                    recyclerView.getPaddingRight(),
                    isVisible ? height : 0
            );

            recyclerView.post(() -> {
                if (recyclerView.getAdapter() != null) {
                    recyclerView.getAdapter().notifyDataSetChanged();
                }
            });
        }
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private void registerMusicUpdateReceiver() {
        try {
            if (!isReceiverRegistered && miniPlayerReceiver != null && getActivity() != null) {
                IntentFilter filter = new IntentFilter();
                filter.addAction(MusicService.ACTION_MUSIC_UPDATED);
                filter.addAction(MusicService.ACTION_PLAYBACK_STATE_CHANGED);
                filter.addAction(MusicService.ACTION_HIDE_MINI_PLAYER);
                filter.addAction("MINI_PLAYER_VISIBILITY_CHANGED");

                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                    getActivity().registerReceiver(miniPlayerReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
                } else {
                    getActivity().registerReceiver(miniPlayerReceiver, filter);
                }

                isReceiverRegistered = true;
            }
        } catch (Exception e) {
            Log.e(TAG, "Error registering broadcast receiver: " + e.getMessage(), e);
            isReceiverRegistered = false;
        }
    }

    @Override
    public void onResume() {
        super.onResume();

        registerMusicUpdateReceiver();

        if (getActivity() != null) {
            Intent requestStateIntent = new Intent(getActivity(), MusicService.class);
            requestStateIntent.setAction(MusicService.ACTION_REQUEST_STATE);
            getActivity().startService(requestStateIntent);

        }
    }

    @Override
    public void onPause() {
        super.onPause();
        if (getActivity() != null && miniPlayerReceiver != null && isReceiverRegistered) {
            try {
                getActivity().unregisterReceiver(miniPlayerReceiver);
                isReceiverRegistered = false;
            } catch (IllegalArgumentException e) {
            }
        }

        try {
            if (miniPlayerContainer != null) {
                miniPlayerContainer.clearAnimation();
            }
        } catch (Exception e) {
            Log.e(TAG, "Error in onPause: " + e.getMessage(), e);
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();

        isFragmentDestroyed = true;

        if (isReceiverRegistered && miniPlayerReceiver != null && getActivity() != null) {
            try {
                getActivity().unregisterReceiver(miniPlayerReceiver);
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
        if (searchHandler != null && searchRunnable != null) {
            searchHandler.removeCallbacks(searchRunnable);
        }

        try {
            currentPlayingItem = null;

            if (getActivity() != null && !getActivity().isDestroyed()) {
                if (miniAlbumArt != null) {
                    Glide.with(this).clear(miniAlbumArt);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error clearing references: " + e.getMessage(), e);
        }

        binding = null;
    }
}
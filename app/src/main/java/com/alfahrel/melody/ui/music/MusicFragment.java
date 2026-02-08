package com.alfahrel.melody.ui.music;


import static com.alfahrel.melody.ui.collection.CollectionFragment.ACTION_COLLECTION_CREATED;
import static com.alfahrel.melody.ui.collection.CollectionFragment.ACTION_SONG_ADDED_TO_COLLECTION;

import android.Manifest;
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
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.IntentSenderRequest;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.alfahrel.melody.R;
import com.alfahrel.melody.databinding.FragmentMusicBinding;
import com.alfahrel.melody.service.MusicService;
import com.alfahrel.melody.ui.pages.nowplaying.AddToCollectionAdapter;
import com.alfahrel.melody.ui.pages.nowplaying.NowPlayingActivity;

import com.alfahrel.melody.ui.collection.Collection;
import com.alfahrel.melody.ui.collection.CollectionManager;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputEditText;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MusicFragment extends Fragment {

    private static final String TAG = "MusicFragment";

    private FragmentMusicBinding binding;
    private MusicAdapter musicAdapter;
    private List<MusicItem> musicList = new ArrayList<>();
    private ExecutorService executorService;
    private static final int PERMISSION_REQUEST_CODE = 123;

    private static List<MusicItem> cachedMusicList = null;
    private static long lastCacheTime = 0;
    private static final long CACHE_DURATION = 5 * 60 * 1000;
    private boolean isLoading = false;
    private CollectionManager collectionManager;
    private boolean hasLoadedOnce = false;
    private boolean permissionJustGranted = false;

    private MusicItem pendingDeleteItem = null;
    private ActivityResultLauncher<IntentSenderRequest> deletePermissionLauncher;

    private BroadcastReceiver miniPlayerReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if ("MINI_PLAYER_VISIBILITY_CHANGED".equals(intent.getAction())) {
                boolean isVisible = intent.getBooleanExtra("is_visible", false);
                int height = intent.getIntExtra("height", 0);

                if (binding != null && binding.musicRecyclerView != null) {
                    RecyclerView recyclerView = binding.musicRecyclerView;
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
        }
    };

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             ViewGroup container, Bundle savedInstanceState) {
        MusicViewModel musicViewModel =
                new ViewModelProvider(this).get(MusicViewModel.class);

        binding = FragmentMusicBinding.inflate(inflater, container, false);
        View root = binding.getRoot();

        executorService = Executors.newSingleThreadExecutor();
        collectionManager = new CollectionManager(requireContext());

        setupDeletePermissionLauncher();
        setupRecyclerView();

        loadMusicData();

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

    private void setupRecyclerView() {
        RecyclerView recyclerView = binding.musicRecyclerView;

//        recyclerView.setHasFixedSize(true);
        recyclerView.setItemViewCacheSize(20);
        recyclerView.setDrawingCacheEnabled(true);
        recyclerView.setDrawingCacheQuality(View.DRAWING_CACHE_QUALITY_HIGH);

        musicAdapter = new MusicAdapter(musicList, getContext());
        recyclerView.setLayoutManager(new LinearLayoutManager(getContext()));
        recyclerView.setAdapter(musicAdapter);

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

        musicList.remove(musicItem);

        // Also remove from cache
        if (cachedMusicList != null) {
            cachedMusicList.remove(musicItem);
        }

        if (musicAdapter != null) {
            musicAdapter.notifyDataSetChanged();
        }

        updateUI();

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

    private void loadMusicData() {
        if (isLoading) {
            return;
        }

        if (isCacheValid() && hasLoadedOnce) {
            loadFromCache();
            return;
        }

        checkPermissionAndLoadMusic();
    }

    private boolean isCacheValid() {
        return cachedMusicList != null &&
                !cachedMusicList.isEmpty() &&
                (System.currentTimeMillis() - lastCacheTime) < CACHE_DURATION;
    }

    private void loadFromCache() {
        if (binding != null && binding.musicRecyclerView != null) {
            binding.musicRecyclerView.post(() -> {
                musicList.clear();
                musicList.addAll(cachedMusicList);

                if (musicAdapter != null) {
                    musicAdapter.notifyDataSetChanged();
                }

                updateUI();
            });
        }
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
        if (getContext() == null || musicList.isEmpty()) {
            return;
        }

        int selectedIndex = -1;
        for (int i = 0; i < musicList.size(); i++) {
            if (musicList.get(i).getId() == selectedSong.getId()) {
                selectedIndex = i;
                break;
            }
        }

        if (selectedIndex == -1) {
            selectedIndex = 0;
        }

        Intent playlistIntent = new Intent(getContext(), MusicService.class);
        playlistIntent.setAction(MusicService.ACTION_SET_PLAYLIST);
        playlistIntent.putParcelableArrayListExtra("playlist", new ArrayList<>(musicList));
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
                permissionJustGranted = true;
                Toast.makeText(getContext(), "Loading music files...", Toast.LENGTH_SHORT).show();

                if (binding != null && binding.getRoot() != null) {
                    binding.getRoot().postDelayed(() -> {
                        if (getContext() != null && !isLoading) {
                            loadMusicFromDevice();
                        }
                    }, 100);
                } else {
                    loadMusicFromDevice();
                }
            } else {
                Toast.makeText(getContext(), "Permission denied. Cannot access music files.",
                        Toast.LENGTH_SHORT).show();
                updateUI();
            }
        }
    }

    private void loadMusicFromDevice() {
        if (isLoading) return;

        isLoading = true;
        showLoading(true);

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
                requireActivity().runOnUiThread(() -> {
                    showLoading(false);
                    isLoading = false;
                    Toast.makeText(getContext(), "Error loading music files", Toast.LENGTH_SHORT).show();
                    showRefreshComplete(false);
                });
                return;
            }

            requireActivity().runOnUiThread(() -> {
                showLoading(false);
                isLoading = false;
                hasLoadedOnce = true;
                cachedMusicList = new ArrayList<>(tempMusicList);
                lastCacheTime = System.currentTimeMillis();

                int previousSize = musicList.size();
                musicList.clear();
                musicList.addAll(tempMusicList);

                if (musicAdapter != null) {
                    musicAdapter.notifyDataSetChanged();
                }

                updateUI();
                showRefreshComplete(true);

                int newCount = tempMusicList.size();
                if (newCount > 0) {
                    if (newCount == previousSize) {
                        Toast.makeText(getContext(),
                                "Music library is up to date (" + newCount + " songs)",
                                Toast.LENGTH_SHORT).show();
                    } else {
//                        Toast.makeText(getContext(),
//                                "Library updated: " + newCount + " songs",
//                                Toast.LENGTH_SHORT).show();
                    }
                } else {
                    Toast.makeText(getContext(),
                            "No music files found",
                            Toast.LENGTH_SHORT).show();
                }
            });
        });
    }

    private void updateUI() {
        if (binding == null) return;

        if (musicList.isEmpty()) {
            binding.emptyState.setVisibility(View.VISIBLE);
            binding.musicRecyclerView.setVisibility(View.GONE);
        } else {
            binding.emptyState.setVisibility(View.GONE);
            binding.musicRecyclerView.setVisibility(View.VISIBLE);
        }
    }

    private void showLoading(boolean show) {
        if (binding == null) return;
    }

    private void showRefreshComplete(boolean success) {
        if (binding == null) return;
    }

    public void refreshData() {
        cachedMusicList = null;
        lastCacheTime = 0;
        hasLoadedOnce = false;

        if (getContext() != null) {
            Toast.makeText(getContext(), "Refreshing music library...", Toast.LENGTH_SHORT).show();
        }

        loadMusicData();
    }

    public static void clearCache() {
        cachedMusicList = null;
        lastCacheTime = 0;
    }

    @Override
    public void onResume() {
        super.onResume();

        String permission;
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            permission = Manifest.permission.READ_MEDIA_AUDIO;
        } else {
            permission = Manifest.permission.READ_EXTERNAL_STORAGE;
        }

        if (getContext() != null &&
                ContextCompat.checkSelfPermission(getContext(), permission) == PackageManager.PERMISSION_GRANTED &&
                musicList.isEmpty() &&
                !isLoading) {
            permissionJustGranted = false;
            loadMusicFromDevice();
        }

        if (getActivity() != null) {
            IntentFilter filter = new IntentFilter("MINI_PLAYER_VISIBILITY_CHANGED");

            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                getActivity().registerReceiver(miniPlayerReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
            } else {
                getActivity().registerReceiver(miniPlayerReceiver, filter);
            }

            Intent requestStateIntent = new Intent(getActivity(), MusicService.class);
            requestStateIntent.setAction(MusicService.ACTION_REQUEST_STATE);
            getActivity().startService(requestStateIntent);
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        if (getActivity() != null && miniPlayerReceiver != null) {
            try {
                getActivity().unregisterReceiver(miniPlayerReceiver);
            } catch (IllegalArgumentException e) {
            }
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        if (executorService != null && !executorService.isShutdown()) {
            executorService.shutdown();
        }
        binding = null;
    }
}
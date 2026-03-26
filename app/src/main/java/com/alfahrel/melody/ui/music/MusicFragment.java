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
import android.content.res.ColorStateList;
import android.content.res.TypedArray;
import android.database.Cursor;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Parcelable;
import android.provider.MediaStore;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
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

import com.alfahrel.melody.utils.SongDetailBottomSheet;
import com.alfahrel.melody.utils.SongOptionsBottomSheet;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

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

    private final BroadcastReceiver miniPlayerReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if ("MINI_PLAYER_VISIBILITY_CHANGED".equals(intent.getAction())) {
                boolean isVisible = intent.getBooleanExtra("is_visible", false);
                int height = intent.getIntExtra("height", 0);

                if (binding != null && binding.musicRecyclerView != null) {
                    RecyclerView rv = binding.musicRecyclerView;
                    int basePadding = (int) (100 * getResources().getDisplayMetrics().density);
                    int bottomPadding = isVisible ? basePadding + height : basePadding;
                    rv.setPadding(rv.getPaddingLeft(), rv.getPaddingTop(),
                            rv.getPaddingRight(), bottomPadding);
                }
            }
        }
    };

    // =========================================================================
    // Lifecycle
    // =========================================================================

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             ViewGroup container, Bundle savedInstanceState) {
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

    @Override
    public void onResume() {
        super.onResume();

        String permission = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                ? Manifest.permission.READ_MEDIA_AUDIO
                : Manifest.permission.READ_EXTERNAL_STORAGE;

        if (getContext() != null
                && ContextCompat.checkSelfPermission(getContext(), permission) == PackageManager.PERMISSION_GRANTED
                && musicList.isEmpty()
                && !isLoading) {
            permissionJustGranted = false;
            loadMusicFromDevice();
        }

        if (getActivity() != null) {
            IntentFilter filter = new IntentFilter("MINI_PLAYER_VISIBILITY_CHANGED");
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                getActivity().registerReceiver(miniPlayerReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
            } else {
                getActivity().registerReceiver(miniPlayerReceiver, filter);
            }

            Intent req = new Intent(getActivity(), MusicService.class);
            req.setAction(MusicService.ACTION_REQUEST_STATE);
            getActivity().startService(req);
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        if (getActivity() != null) {
            try { getActivity().unregisterReceiver(miniPlayerReceiver); }
            catch (IllegalArgumentException ignored) {}
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

    // =========================================================================
    // Setup
    // =========================================================================

    private void setupDeletePermissionLauncher() {
        deletePermissionLauncher = registerForActivityResult(
                new ActivityResultContracts.StartIntentSenderForResult(),
                result -> {
                    if (result.getResultCode() == requireActivity().RESULT_OK) {
                        if (pendingDeleteItem != null) {
                            deleteSongAfterPermission(pendingDeleteItem);
                            pendingDeleteItem = null;
                        }
                    } else {
                        Toast.makeText(requireContext(),
                                "Permission denied to delete file", Toast.LENGTH_SHORT).show();
                        pendingDeleteItem = null;
                    }
                });
    }

    private void setupRecyclerView() {
        RecyclerView recyclerView = binding.musicRecyclerView;
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
            public void onOptionClick(MusicItem musicItem) {
                showSongOptionsSheet(musicItem);
            }
        });

        musicAdapter.setOnMusicItemLongClickListener(musicItem -> {
            showSongOptionsSheet(musicItem);
            return true;
        });
    }

    // =========================================================================
    // Song options bottom sheet (replaces the old string-array AlertDialog)
    // =========================================================================

    private void showSongOptionsSheet(MusicItem musicItem) {
        if (musicItem == null) return;

        SongOptionsBottomSheet sheet = SongOptionsBottomSheet.newInstance(musicItem);
        sheet.setListener(new SongOptionsBottomSheet.SongOptionsListener() {

            @Override
            public void onAddToCollection(MusicItem item) {
                showAddToCollectionBottomSheet(item);
            }

            @Override
            public void onViewDetails(MusicItem item) {
                SongDetailBottomSheet.newInstance(item)
                        .show(getChildFragmentManager(), "song_detail");
            }

            @Override
            public void onDelete(MusicItem item) {
                showDeleteConfirmationDialog(item);
            }
        });

        sheet.show(getChildFragmentManager(), "song_options");
    }

    // =========================================================================
    // Delete
    // =========================================================================

    private void showDeleteConfirmationDialog(MusicItem musicItem) {
        if (getContext() == null || musicItem == null) return;

        new MaterialAlertDialogBuilder(requireContext())
                .setTitle("Delete song")
                .setMessage("Are you sure you want to delete \"" + musicItem.getTitle() + "\"? This action cannot be undone.")
                .setPositiveButton("Delete", (dialog, which) -> deleteSong(musicItem))
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void deleteSong(MusicItem musicItem) {
        if (getContext() == null || musicItem == null) return;

        try {
            Uri uri = ContentUris.withAppendedId(
                    MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, musicItem.getId());

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                try {
                    int rows = requireContext().getContentResolver().delete(uri, null, null);
                    if (rows > 0) onSongDeleteSuccess(musicItem);
                    else Toast.makeText(requireContext(), "Failed to delete song", Toast.LENGTH_SHORT).show();
                } catch (SecurityException se) {
                    if (se instanceof RecoverableSecurityException) {
                        pendingDeleteItem = musicItem;
                        deletePermissionLauncher.launch(new IntentSenderRequest.Builder(
                                ((RecoverableSecurityException) se)
                                        .getUserAction().getActionIntent().getIntentSender()).build());
                    } else {
                        Toast.makeText(requireContext(),
                                "Permission denied. Cannot delete this file.", Toast.LENGTH_SHORT).show();
                    }
                }
            } else {
                int rows = requireContext().getContentResolver().delete(uri, null, null);
                if (rows > 0) {
                    new java.io.File(musicItem.getPath()).delete();
                    onSongDeleteSuccess(musicItem);
                } else {
                    Toast.makeText(requireContext(), "Failed to delete song", Toast.LENGTH_SHORT).show();
                }
            }
        } catch (Exception e) {
            Toast.makeText(requireContext(), "Error deleting song: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            Log.e(TAG, "Error deleting song", e);
        }
    }

    private void deleteSongAfterPermission(MusicItem musicItem) {
        if (getContext() == null || musicItem == null) return;
        try {
            Uri uri = ContentUris.withAppendedId(
                    MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, musicItem.getId());
            int rows = requireContext().getContentResolver().delete(uri, null, null);
            if (rows > 0) onSongDeleteSuccess(musicItem);
            else Toast.makeText(requireContext(), "Failed to delete song", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(requireContext(), "Error: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            Log.e(TAG, "Error deleting song after permission", e);
        }
    }

    private void onSongDeleteSuccess(MusicItem musicItem) {
        Toast.makeText(requireContext(), "Song deleted", Toast.LENGTH_SHORT).show();
        musicList.remove(musicItem);
        if (cachedMusicList != null) cachedMusicList.remove(musicItem);
        if (musicAdapter != null) musicAdapter.notifyDataSetChanged();
        updateUI();

        Intent intent = new Intent("SONG_DELETED");
        intent.putExtra("song_id", musicItem.getId());
        intent.setPackage(requireContext().getPackageName());
        requireContext().sendBroadcast(intent);
    }

    // =========================================================================
    // Add to collection
    // =========================================================================

    private void showAddToCollectionBottomSheet(MusicItem musicItem) {
        if (getContext() == null || musicItem == null) return;

        List<Collection> collections = collectionManager.getAllCollections();

        BottomSheetDialog bottomSheetDialog = new BottomSheetDialog(requireContext());
        View view = LayoutInflater.from(requireContext())
                .inflate(R.layout.bottom_sheet_add_to_collection, null);

        RecyclerView collectionsRv = view.findViewById(R.id.collectionsRecyclerView);
        android.widget.TextView emptyText = view.findViewById(R.id.emptyCollectionsText);
        MaterialButton createBtn = view.findViewById(R.id.createNewCollectionButton);

        collectionsRv.setLayoutManager(new LinearLayoutManager(requireContext()));

        if (collections.isEmpty()) {
            collectionsRv.setVisibility(View.GONE);
            emptyText.setVisibility(View.VISIBLE);
        } else {
            collectionsRv.setVisibility(View.VISIBLE);
            emptyText.setVisibility(View.GONE);
            AddToCollectionAdapter adapter = new AddToCollectionAdapter(
                    collections, musicItem.getId(), collectionManager,
                    collection -> {
                        boolean added = collectionManager.addSongToCollection(
                                collection.getId(), musicItem.getId());
                        if (added) {
                            Toast.makeText(requireContext(),
                                    "Added to " + collection.getName(), Toast.LENGTH_SHORT).show();
                            broadcastCollectionChange(ACTION_SONG_ADDED_TO_COLLECTION);
                            bottomSheetDialog.dismiss();
                        } else {
                            Toast.makeText(requireContext(),
                                    "Song already in " + collection.getName(), Toast.LENGTH_SHORT).show();
                        }
                    });
            collectionsRv.setAdapter(adapter);
        }

        createBtn.setOnClickListener(v -> {
            bottomSheetDialog.dismiss();
            showCreateCollectionDialog(musicItem);
        });

        bottomSheetDialog.setContentView(view);
        bottomSheetDialog.show();
    }

    private void showCreateCollectionDialog(MusicItem musicItem) {
        if (getContext() == null) return;

        View dialogView = LayoutInflater.from(requireContext())
                .inflate(R.layout.dialog_add_collection, null);
        TextInputEditText editTextName = dialogView.findViewById(R.id.editTextCollectionName);
        TextInputLayout textInputLayout = dialogView.findViewById(R.id.textInputCollectionName);

        int[] attrsToResolve = {
                com.google.android.material.R.attr.colorOnSurface,
                com.google.android.material.R.attr.colorOnSurfaceVariant
        };
        TypedArray ta = requireContext().obtainStyledAttributes(attrsToResolve);
        int colorPrimary   = ta.getColor(0, Color.BLACK);
        int colorOnSurface = ta.getColor(1, Color.GRAY);
        ta.recycle();

        int[][] states = new int[][] {
                new int[] { android.R.attr.state_focused },
                new int[] {}  // default
        };
        int[] colors = new int[] { colorPrimary, colorOnSurface };

        textInputLayout.setBoxStrokeColorStateList(new ColorStateList(states, colors));


        new MaterialAlertDialogBuilder(requireContext())
                .setTitle("New collection")
                .setView(dialogView)
                .setPositiveButton("Create", (dialog, which) -> {
                    String name = editTextName.getText() != null
                            ? editTextName.getText().toString().trim() : "";
                    if (!name.isEmpty()) createCollectionAndAddSong(name, musicItem);
                    else Toast.makeText(requireContext(),
                            "Collection name cannot be empty", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void createCollectionAndAddSong(String collectionName, MusicItem musicItem) {
        if (getContext() == null || musicItem == null) return;

        for (Collection c : collectionManager.getAllCollections()) {
            if (c.getName().equalsIgnoreCase(collectionName)) {
                Toast.makeText(requireContext(), "Collection already exists", Toast.LENGTH_SHORT).show();
                return;
            }
        }

        Collection newCollection = collectionManager.createCollection(collectionName);
        collectionManager.addSongToCollection(newCollection.getId(), musicItem.getId());
        Toast.makeText(requireContext(),
                "Created \"" + collectionName + "\" and added song", Toast.LENGTH_SHORT).show();
        broadcastCollectionChange(ACTION_COLLECTION_CREATED);
    }

    private void broadcastCollectionChange(String action) {
        if (getContext() == null) return;
        try {
            Intent intent = new Intent(action);
            intent.setPackage(requireContext().getPackageName());
            requireContext().sendBroadcast(intent);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // =========================================================================
    // Playback
    // =========================================================================

    private void startMusicServiceAndOpenNowPlaying(MusicItem musicItem) {
        startMusicServiceWithPlaylist(musicItem);
        new android.os.Handler().postDelayed(() -> openNowPlaying(musicItem), 200);
    }

    private void startMusicService(MusicItem musicItem) {
        startMusicServiceWithPlaylist(musicItem);
    }

    private void startMusicServiceWithPlaylist(MusicItem selectedSong) {
        if (getContext() == null || musicList.isEmpty()) return;

        int selectedIndex = 0;
        for (int i = 0; i < musicList.size(); i++) {
            if (musicList.get(i).getId() == selectedSong.getId()) {
                selectedIndex = i;
                break;
            }
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
            getActivity().overridePendingTransition(R.anim.slide_in_bottom, R.anim.slide_out_top);
        }
    }

    // =========================================================================
    // Data loading
    // =========================================================================

    private void loadMusicData() {
        if (isLoading) return;
        if (isCacheValid() && hasLoadedOnce) { loadFromCache(); return; }
        checkPermissionAndLoadMusic();
    }

    private boolean isCacheValid() {
        return cachedMusicList != null
                && !cachedMusicList.isEmpty()
                && (System.currentTimeMillis() - lastCacheTime) < CACHE_DURATION;
    }

    private void loadFromCache() {
        if (binding == null) return;
        binding.musicRecyclerView.post(() -> {
            musicList.clear();
            musicList.addAll(cachedMusicList);
            if (musicAdapter != null) musicAdapter.notifyDataSetChanged();
            updateUI();
        });
    }

    private void checkPermissionAndLoadMusic() {
        String permission = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                ? Manifest.permission.READ_MEDIA_AUDIO
                : Manifest.permission.READ_EXTERNAL_STORAGE;

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
                if (binding != null) {
                    binding.getRoot().postDelayed(() -> {
                        if (getContext() != null && !isLoading) loadMusicFromDevice();
                    }, 100);
                } else {
                    loadMusicFromDevice();
                }
            } else {
                Toast.makeText(getContext(),
                        "Permission denied. Cannot access music files.", Toast.LENGTH_SHORT).show();
                updateUI();
            }
        }
    }

    private void loadMusicFromDevice() {
        if (isLoading) return;
        isLoading = true;

        executorService.execute(() -> {
            List<MusicItem> tempList = new ArrayList<>();
            ContentResolver cr = requireContext().getContentResolver();
            Uri uri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;

            String[] projection = {
                    MediaStore.Audio.Media._ID,
                    MediaStore.Audio.Media.TITLE,
                    MediaStore.Audio.Media.ARTIST,
                    MediaStore.Audio.Media.ALBUM,
                    MediaStore.Audio.Media.DURATION,
                    MediaStore.Audio.Media.DATA,
                    MediaStore.Audio.Media.ALBUM_ID
            };

            try (Cursor cursor = cr.query(uri, projection,
                    MediaStore.Audio.Media.IS_MUSIC + " != 0",
                    null,
                    MediaStore.Audio.Media.TITLE + " ASC")) {

                if (cursor != null && cursor.moveToFirst()) {
                    int colId       = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID);
                    int colTitle    = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE);
                    int colArtist   = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST);
                    int colAlbum    = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM);
                    int colDuration = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION);
                    int colPath     = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA);
                    int colAlbumId  = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID);

                    do {
                        long id       = cursor.getLong(colId);
                        String title  = cursor.getString(colTitle);
                        String artist = cursor.getString(colArtist);
                        String album  = cursor.getString(colAlbum);
                        long duration = cursor.getLong(colDuration);
                        String path   = cursor.getString(colPath);
                        long albumId  = cursor.getLong(colAlbumId);
                        Uri artUri    = Uri.parse("content://media/external/audio/albumart/" + albumId);
                        tempList.add(new MusicItem(id, title, artist, album, duration, path, artUri));
                    } while (cursor.moveToNext());
                }
            } catch (Exception e) {
                Log.e(TAG, "Error loading music", e);
                requireActivity().runOnUiThread(() -> {
                    isLoading = false;
                    Toast.makeText(getContext(), "Error loading music files", Toast.LENGTH_SHORT).show();
                });
                return;
            }

            requireActivity().runOnUiThread(() -> {
                isLoading = false;
                hasLoadedOnce = true;
                cachedMusicList = new ArrayList<>(tempList);
                lastCacheTime = System.currentTimeMillis();

                int previousSize = musicList.size();
                musicList.clear();
                musicList.addAll(tempList);
                if (musicAdapter != null) musicAdapter.notifyDataSetChanged();
                updateUI();

                int newCount = tempList.size();
                if (newCount == 0) {
                    Toast.makeText(getContext(), "No music files found", Toast.LENGTH_SHORT).show();
                } else if (newCount == previousSize) {
                    Toast.makeText(getContext(),
                            "Library up to date (" + newCount + " songs)", Toast.LENGTH_SHORT).show();
                }
            });
        });
    }

    // =========================================================================
    // UI helpers
    // =========================================================================

    private void updateUI() {
        if (binding == null) return;
        boolean empty = musicList.isEmpty();
        binding.emptyState.setVisibility(empty ? View.VISIBLE : View.GONE);
        binding.musicRecyclerView.setVisibility(empty ? View.GONE : View.VISIBLE);
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
}
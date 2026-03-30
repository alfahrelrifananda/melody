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
    import android.content.SharedPreferences;
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
    import androidx.recyclerview.widget.ConcatAdapter;
    import androidx.recyclerview.widget.LinearLayoutManager;
    import androidx.recyclerview.widget.RecyclerView;
    
    import com.alfahrel.melody.R;
    import com.alfahrel.melody.databinding.FragmentMusicBinding;
    import com.alfahrel.melody.service.MusicService;
    import com.alfahrel.melody.ui.album.AlbumDetailActivity;
    import com.alfahrel.melody.ui.album.AlbumFragment;
    import com.alfahrel.melody.ui.album.AlbumItem;
    import com.alfahrel.melody.ui.artist.ArtistDetailActivity;
    import com.alfahrel.melody.ui.artist.ArtistFragment;
    import com.alfahrel.melody.ui.artist.ArtistItem;
    import com.alfahrel.melody.ui.pages.nowplaying.AddToCollectionAdapter;
    import com.alfahrel.melody.ui.pages.nowplaying.NowPlayingActivity;
    import com.alfahrel.melody.ui.collection.Collection;
    import com.alfahrel.melody.ui.collection.CollectionDetailActivity;
    import com.alfahrel.melody.ui.collection.CollectionManager;
    import com.alfahrel.melody.utils.GsonHelper;
    import com.alfahrel.melody.utils.PinnedItem;
    import com.alfahrel.melody.utils.PinnedStripHeaderAdapter;
    import com.alfahrel.melody.utils.PlayCountManager;
    import com.alfahrel.melody.utils.SongDetailBottomSheet;
    import com.alfahrel.melody.utils.SongOptionsBottomSheet;
    import com.google.android.material.bottomsheet.BottomSheetDialog;
    import com.google.android.material.dialog.MaterialAlertDialogBuilder;
    import com.google.android.material.textfield.TextInputEditText;
    import com.google.android.material.textfield.TextInputLayout;
    import com.google.gson.Gson;
    import com.google.gson.reflect.TypeToken;
    
    import java.lang.reflect.Type;
    import java.util.ArrayList;
    import java.util.HashMap;
    import java.util.List;
    import java.util.Map;
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
        private enum SortMode { DEFAULT, RECENTLY_ADDED, MOST_PLAYED, A_Z, Z_A }
        private SortMode currentSort = SortMode.DEFAULT;
        private List<MusicItem> originalMusicList = new ArrayList<>();
        private PlayCountManager playCountManager;
    
        // ── Single pinned strip ───────────────────────────────────────────────────
        private PinnedStripHeaderAdapter pinnedStripHeaderAdapter;
    
        // ── Song pin persistence ──────────────────────────────────────────────────
        private static final String SONG_PREFS_NAME = "SongsPrefs";
        private static final String KEY_SONGS       = "songs_full";
        private Gson gson;
        private SharedPreferences songPrefs;
    
        // ── Broadcast receivers ───────────────────────────────────────────────────
        private final BroadcastReceiver stripUpdateReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                 refreshPinnedStrip(); binding.musicRecyclerView.scrollToPosition(0); 
            }
        };
    
        private final BroadcastReceiver miniPlayerReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if ("MINI_PLAYER_VISIBILITY_CHANGED".equals(intent.getAction())) {
                    boolean isVisible = intent.getBooleanExtra("is_visible", false);
                    int height = intent.getIntExtra("height", 0);
                    if (binding != null && binding.musicRecyclerView != null) {
                        RecyclerView rv = binding.musicRecyclerView;
                        int basePadding = (int) (100 * getResources().getDisplayMetrics().density);
                        rv.setPadding(rv.getPaddingLeft(), rv.getPaddingTop(),
                                rv.getPaddingRight(), isVisible ? basePadding + height : basePadding);
                    }
                }
            }
        };
    
        // ── Lifecycle ─────────────────────────────────────────────────────────────
    
        @Override
        public View onCreateView(@NonNull LayoutInflater inflater,
                                 ViewGroup container, Bundle savedInstanceState) {
            new ViewModelProvider(this).get(MusicViewModel.class);
    
            binding = FragmentMusicBinding.inflate(inflater, container, false);
            View root = binding.getRoot();
    
            executorService = Executors.newSingleThreadExecutor();
            collectionManager = new CollectionManager(requireContext());
            playCountManager = new PlayCountManager(requireContext());
            gson = GsonHelper.get();
            songPrefs = requireContext().getSharedPreferences(SONG_PREFS_NAME, Context.MODE_PRIVATE);
    
            setupDeletePermissionLauncher();
            setupRecyclerView();
             refreshPinnedStrip(); binding.musicRecyclerView.scrollToPosition(0); 
            loadMusicData();
    
            return root;
        }
    
        @Override
        public void onResume() {
            super.onResume();
    
            String permission = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                    ? Manifest.permission.READ_MEDIA_AUDIO : Manifest.permission.READ_EXTERNAL_STORAGE;
    
            if (getContext() != null
                    && ContextCompat.checkSelfPermission(getContext(), permission) == PackageManager.PERMISSION_GRANTED
                    && musicList.isEmpty() && !isLoading) {
                permissionJustGranted = false;
                loadMusicFromDevice();
            }
    
            if (getActivity() != null) {
                IntentFilter miniFilter = new IntentFilter("MINI_PLAYER_VISIBILITY_CHANGED");
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                    getActivity().registerReceiver(miniPlayerReceiver, miniFilter, Context.RECEIVER_NOT_EXPORTED);
                else
                    getActivity().registerReceiver(miniPlayerReceiver, miniFilter);
    
                IntentFilter stripFilter = new IntentFilter();
                stripFilter.addAction("com.alfahrel.melody.ALBUM_CHANGED");
                stripFilter.addAction("com.alfahrel.melody.ARTIST_CHANGED");
                stripFilter.addAction("com.alfahrel.melody.COLLECTION_CHANGED");
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                    getActivity().registerReceiver(stripUpdateReceiver, stripFilter, Context.RECEIVER_NOT_EXPORTED);
                else
                    getActivity().registerReceiver(stripUpdateReceiver, stripFilter);
    
                Intent req = new Intent(getActivity(), MusicService.class);
                req.setAction(MusicService.ACTION_REQUEST_STATE);
                getActivity().startService(req);
            }
    
             refreshPinnedStrip(); binding.musicRecyclerView.scrollToPosition(0); 
        }
    
        @Override
        public void onPause() {
            super.onPause();
            if (getActivity() != null) {
                try { getActivity().unregisterReceiver(miniPlayerReceiver); } catch (Exception ignored) {}
                try { getActivity().unregisterReceiver(stripUpdateReceiver); } catch (Exception ignored) {}
            }
        }
    
        @Override
        public void onDestroyView() {
            super.onDestroyView();
            if (executorService != null && !executorService.isShutdown()) executorService.shutdown();
            binding = null;
        }
    
        // ── Song pin ──────────────────────────────────────────────────────────────
    
        private void toggleSongPin(MusicItem song) {
            executorService.execute(() -> {
                boolean nowPinned = false;
                for (MusicItem s : musicList) {
                    if (s.getId() == song.getId()) {
                        nowPinned = !s.isPinned();
                        s.setPinned(nowPinned);
                        break;
                    }
                }
                saveSongList();
                final boolean pinned = nowPinned;
                requireActivity().runOnUiThread(() -> {
                    if (musicAdapter != null) musicAdapter.notifyDataSetChanged();
                    Toast.makeText(requireContext(),
                            pinned ? "Pinned to Home" : "Unpinned from Home",
                            Toast.LENGTH_SHORT).show();
                     refreshPinnedStrip(); binding.musicRecyclerView.scrollToPosition(0); 
                });
            });
        }
    
        private void saveSongList() {
            songPrefs.edit().putString(KEY_SONGS, gson.toJson(musicList)).apply();
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
    
        // ── Pinned strip ──────────────────────────────────────────────────────────
    
        private void refreshPinnedStrip() {
            if (getContext() == null || pinnedStripHeaderAdapter == null) return;
            executorService.execute(() -> {
                List<PinnedItem> merged = new ArrayList<>();
    
                // Collections
                try {
                    SharedPreferences cp = requireContext()
                            .getSharedPreferences("CollectionsPrefs", Context.MODE_PRIVATE);
                    String json = cp.getString("collections", null);
                    if (json != null) {
                        Type t = new TypeToken<List<Collection>>(){}.getType();
                        List<Collection> cols = GsonHelper.get().fromJson(json, t);
                        if (cols != null) {
                            for (Collection c : cols) {
                                if (c.isPinned()) {
                                    int count = c.getMusicIds() != null ? c.getMusicIds().size() : 0;
                                    merged.add(new PinnedItem(
                                            PinnedItem.Type.COLLECTION,
                                            String.valueOf(c.getId()),
                                            c.getName(),
                                            count + (count == 1 ? " song" : " songs"),
                                            c.getCoverImageUri() != null ? Uri.parse(c.getCoverImageUri()) : null,
                                            c));
                                }
                            }
                        }
                    }
                } catch (Exception e) { e.printStackTrace(); }
    
                // Albums
                try {
                    SharedPreferences ap = requireContext()
                            .getSharedPreferences(AlbumFragment.PREFS_NAME, Context.MODE_PRIVATE);
                    String json = ap.getString(AlbumFragment.KEY_ALBUMS, null);
                    if (json != null) {
                        Type t = new TypeToken<List<AlbumItem>>(){}.getType();
                        List<AlbumItem> albums = GsonHelper.get().fromJson(json, t);
                        if (albums != null) {
                            for (AlbumItem a : albums) {
                                if (a.isPinned()) {
                                    merged.add(new PinnedItem(
                                            PinnedItem.Type.ALBUM,
                                            String.valueOf(a.getAlbumId()),
                                            a.getAlbumName(),
                                            a.getArtistName(),
                                            a.getAlbumArtUri(),
                                            a));
                                }
                            }
                        }
                    }
                } catch (Exception e) { e.printStackTrace(); }
    
                // Artists
                try {
                    SharedPreferences ap = requireContext()
                            .getSharedPreferences(ArtistFragment.PREFS_NAME, Context.MODE_PRIVATE);
                    String json = ap.getString(ArtistFragment.KEY_ARTISTS, null);
                    if (json != null) {
                        Type t = new TypeToken<List<ArtistItem>>(){}.getType();
                        List<ArtistItem> artists = GsonHelper.get().fromJson(json, t);
                        if (artists != null) {
                            for (ArtistItem a : artists) {
                                if (a.isPinned()) {
                                    merged.add(new PinnedItem(
                                            PinnedItem.Type.ARTIST,
                                            a.getArtistName(),
                                            a.getArtistName(),
                                            a.getFormattedSongCount(),
                                            a.getArtistImageUri(),
                                            a));
                                }
                            }
                        }
                    }
                } catch (Exception e) { e.printStackTrace(); }
    
                // Songs
                for (MusicItem s : musicList) {
                    if (s.isPinned()) {
                        merged.add(new PinnedItem(
                                PinnedItem.Type.SONG,
                                String.valueOf(s.getId()),
                                s.getTitle(),
                                s.getArtist(),
                                s.getAlbumArtUri(),
                                s));
                    }
                }
    
                final List<PinnedItem> finalList = merged;
                if (getActivity() != null)
                    requireActivity().runOnUiThread(() -> {
                        if (pinnedStripHeaderAdapter != null)
                            pinnedStripHeaderAdapter.updateItems(finalList);
                    });
            });
        }
    
        // ── RecyclerView setup ────────────────────────────────────────────────────
    
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
                            Toast.makeText(requireContext(), "Permission denied to delete file", Toast.LENGTH_SHORT).show();
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
    
            pinnedStripHeaderAdapter = new PinnedStripHeaderAdapter(
                    // ── Click listener ────────────────────────────────────────────────
                    pinnedItem -> {
                        switch (pinnedItem.getType()) {
                            case COLLECTION:
                                Collection col = (Collection) pinnedItem.getOriginal();
                                Intent ci = new Intent(requireContext(), CollectionDetailActivity.class);
                                ci.putExtra("collection", col);
                                startActivity(ci);
                                break;
                            case ALBUM:
                                AlbumItem album = (AlbumItem) pinnedItem.getOriginal();
                                Intent ai = new Intent(requireContext(), AlbumDetailActivity.class);
                                ai.putExtra("album_item", album);
                                startActivity(ai);
                                break;
                            case ARTIST:
                                ArtistItem artist = (ArtistItem) pinnedItem.getOriginal();
                                Intent ari = new Intent(requireContext(), ArtistDetailActivity.class);
                                ari.putExtra("artist_item", artist);
                                startActivity(ari);
                                break;
                            case SONG:
                                MusicItem song = (MusicItem) pinnedItem.getOriginal();
                                startMusicServiceAndOpenNowPlaying(song);
                                break;
                        }
                    },
    
                    // ── Long click listener (unpin) ───────────────────────────────────
                    pinnedItem -> {
                        switch (pinnedItem.getType()) {
                            case SONG: {
                                MusicItem song = (MusicItem) pinnedItem.getOriginal();
                                for (MusicItem s : musicList) {
                                    if (s.getId() == song.getId()) {
                                        s.setPinned(false);
                                        break;
                                    }
                                }
                                saveSongList();
                                Toast.makeText(requireContext(), "\"" + song.getTitle() + "\" unpinned from Home", Toast.LENGTH_SHORT).show();
                                 refreshPinnedStrip(); binding.musicRecyclerView.scrollToPosition(0); 
                                if (musicAdapter != null) musicAdapter.notifyDataSetChanged();
                                break;
                            }
                            case ALBUM: {
                                AlbumItem album = (AlbumItem) pinnedItem.getOriginal();
                                SharedPreferences ap = requireContext()
                                        .getSharedPreferences(AlbumFragment.PREFS_NAME, Context.MODE_PRIVATE);
                                String json = ap.getString(AlbumFragment.KEY_ALBUMS, null);
                                if (json != null) {
                                    Type type = new TypeToken<List<AlbumItem>>(){}.getType();
                                    List<AlbumItem> albums = GsonHelper.get().fromJson(json, type);
                                    if (albums != null) {
                                        for (AlbumItem a : albums) {
                                            if (a.getAlbumId() == album.getAlbumId()) {
                                                a.setPinned(false);
                                                break;
                                            }
                                        }
                                        ap.edit().putString(AlbumFragment.KEY_ALBUMS, GsonHelper.get().toJson(albums)).apply();
                                    }
                                }
                                Toast.makeText(requireContext(), "\"" + album.getAlbumName() + "\" unpinned from Home", Toast.LENGTH_SHORT).show();
                                broadcastCollectionChange("com.alfahrel.melody.ALBUM_CHANGED");
                                 refreshPinnedStrip(); binding.musicRecyclerView.scrollToPosition(0); 
                                break;
                            }
                            case ARTIST: {
                                ArtistItem artist = (ArtistItem) pinnedItem.getOriginal();
                                SharedPreferences ap = requireContext()
                                        .getSharedPreferences(ArtistFragment.PREFS_NAME, Context.MODE_PRIVATE);
                                String json = ap.getString(ArtistFragment.KEY_ARTISTS, null);
                                if (json != null) {
                                    Type type = new TypeToken<List<ArtistItem>>(){}.getType();
                                    List<ArtistItem> artists = GsonHelper.get().fromJson(json, type);
                                    if (artists != null) {
                                        for (ArtistItem a : artists) {
                                            if (a.getArtistName().equals(artist.getArtistName())) {
                                                a.setPinned(false);
                                                break;
                                            }
                                        }
                                        ap.edit().putString(ArtistFragment.KEY_ARTISTS, GsonHelper.get().toJson(artists)).apply();
                                    }
                                }
                                Toast.makeText(requireContext(), "\"" + artist.getArtistName() + "\" unpinned from Home", Toast.LENGTH_SHORT).show();
                                broadcastCollectionChange("com.alfahrel.melody.ARTIST_CHANGED");
                                 refreshPinnedStrip(); binding.musicRecyclerView.scrollToPosition(0); 
                                break;
                            }
                            case COLLECTION: {
                                Collection col = (Collection) pinnedItem.getOriginal();
                                List<Collection> collections = collectionManager.getAllCollections();
                                for (Collection c : collections) {
                                    if (c.getId() == col.getId()) {
                                        c.setPinned(false);
                                        break;
                                    }
                                }
                                collectionManager.saveCollections(collections);
                                Toast.makeText(requireContext(), "\"" + col.getName() + "\" unpinned from Home", Toast.LENGTH_SHORT).show();
                                broadcastCollectionChange("com.alfahrel.melody.COLLECTION_CHANGED");
                                 refreshPinnedStrip(); binding.musicRecyclerView.scrollToPosition(0); 
                                break;
                            }
                        }
                    }
            );
    
            ConcatAdapter concatAdapter = new ConcatAdapter(
                    pinnedStripHeaderAdapter,
                    musicAdapter);
    
            recyclerView.setLayoutManager(new LinearLayoutManager(getContext()));
            recyclerView.setAdapter(concatAdapter);
    
            musicAdapter.setOnMusicItemClickListener(new MusicAdapter.OnMusicItemClickListener() {
                @Override public void onMusicItemClick(MusicItem musicItem) { startMusicServiceAndOpenNowPlaying(musicItem); }
                @Override public void onOptionClick(MusicItem musicItem)    { showSongOptionsSheet(musicItem); }
            });
    
            musicAdapter.setOnMusicItemLongClickListener(musicItem -> {
                showSongOptionsSheet(musicItem);
                return true;
            });
        }
        private void showSongOptionsSheet(MusicItem musicItem) {
            if (musicItem == null) return;
            SongOptionsBottomSheet sheet = SongOptionsBottomSheet.newInstance(musicItem);
            sheet.setListener(new SongOptionsBottomSheet.SongOptionsListener() {
                @Override public void onAddToCollection(MusicItem item) { showAddToCollectionBottomSheet(item); }
                @Override public void onViewDetails(MusicItem item) {
                    SongDetailBottomSheet.newInstance(item).show(getChildFragmentManager(), "song_detail");
                }
                @Override public void onDelete(MusicItem item) { showDeleteConfirmationDialog(item); }
                @Override public void onPin(MusicItem item)    { toggleSongPin(item); }
            });
            sheet.show(getChildFragmentManager(), "song_options");
        }
    
        // ── Delete ────────────────────────────────────────────────────────────────
    
        private void showDeleteConfirmationDialog(MusicItem musicItem) {
            if (getContext() == null || musicItem == null) return;
            new MaterialAlertDialogBuilder(requireContext())
                    .setTitle("Delete song")
                    .setMessage("Are you sure you want to delete \"" + musicItem.getTitle() + "\"?")
                    .setPositiveButton("Delete", (dialog, which) -> deleteSong(musicItem))
                    .setNegativeButton("Cancel", null).show();
        }
    
        private void deleteSong(MusicItem musicItem) {
            if (getContext() == null || musicItem == null) return;
            try {
                Uri uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, musicItem.getId());
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    try {
                        int rows = requireContext().getContentResolver().delete(uri, null, null);
                        if (rows > 0) onSongDeleteSuccess(musicItem);
                        else Toast.makeText(requireContext(), "Failed to delete song", Toast.LENGTH_SHORT).show();
                    } catch (SecurityException se) {
                        if (se instanceof RecoverableSecurityException) {
                            pendingDeleteItem = musicItem;
                            deletePermissionLauncher.launch(new IntentSenderRequest.Builder(
                                    ((RecoverableSecurityException) se).getUserAction()
                                            .getActionIntent().getIntentSender()).build());
                        } else {
                            Toast.makeText(requireContext(), "Permission denied.", Toast.LENGTH_SHORT).show();
                        }
                    }
                } else {
                    int rows = requireContext().getContentResolver().delete(uri, null, null);
                    if (rows > 0) { new java.io.File(musicItem.getPath()).delete(); onSongDeleteSuccess(musicItem); }
                    else Toast.makeText(requireContext(), "Failed to delete song", Toast.LENGTH_SHORT).show();
                }
            } catch (Exception e) {
                Toast.makeText(requireContext(), "Error: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                Log.e(TAG, "Error deleting song", e);
            }
        }
    
        private void deleteSongAfterPermission(MusicItem musicItem) {
            if (getContext() == null || musicItem == null) return;
            try {
                Uri uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, musicItem.getId());
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
    
        // ── Add to collection ─────────────────────────────────────────────────────
    
        private void showAddToCollectionBottomSheet(MusicItem musicItem) {
            if (getContext() == null || musicItem == null) return;
            List<Collection> collections = collectionManager.getAllCollections();
            BottomSheetDialog dialog = new BottomSheetDialog(requireContext());
            View view = LayoutInflater.from(requireContext())
                    .inflate(R.layout.bottom_sheet_add_to_collection, null);
            RecyclerView rv = view.findViewById(R.id.collectionsRecyclerView);
            android.widget.TextView emptyText = view.findViewById(R.id.emptyCollectionsText);
            View createBtn = view.findViewById(R.id.createNewCollectionButton);
            rv.setLayoutManager(new LinearLayoutManager(requireContext()));
            if (collections.isEmpty()) {
                rv.setVisibility(View.GONE); emptyText.setVisibility(View.VISIBLE);
            } else {
                rv.setVisibility(View.VISIBLE); emptyText.setVisibility(View.GONE);
                AddToCollectionAdapter adapter = new AddToCollectionAdapter(
                        collections, musicItem.getId(), collectionManager,
                        collection -> {
                            boolean added = collectionManager.addSongToCollection(collection.getId(), musicItem.getId());
                            if (added) {
                                Toast.makeText(requireContext(), "Added to " + collection.getName(), Toast.LENGTH_SHORT).show();
                                broadcastCollectionChange(ACTION_SONG_ADDED_TO_COLLECTION);
                                dialog.dismiss();
                            } else {
                                Toast.makeText(requireContext(), "Song already in " + collection.getName(), Toast.LENGTH_SHORT).show();
                            }
                        });
                rv.setAdapter(adapter);
            }
            createBtn.setOnClickListener(v -> { dialog.dismiss(); showCreateCollectionDialog(musicItem); });
            dialog.setContentView(view);
            dialog.show();
        }
    
        private void showCreateCollectionDialog(MusicItem musicItem) {
            if (getContext() == null) return;
            View dialogView = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_add_collection, null);
            TextInputEditText editTextName = dialogView.findViewById(R.id.editTextCollectionName);
            TextInputLayout textInputLayout = dialogView.findViewById(R.id.textInputCollectionName);
            int[] attrs = { com.google.android.material.R.attr.colorOnSurface,
                    com.google.android.material.R.attr.colorOnSurfaceVariant };
            TypedArray ta = requireContext().obtainStyledAttributes(attrs);
            int c1 = ta.getColor(0, Color.BLACK), c2 = ta.getColor(1, Color.GRAY);
            ta.recycle();
            textInputLayout.setBoxStrokeColorStateList(new ColorStateList(
                    new int[][]{ new int[]{ android.R.attr.state_focused }, new int[]{} }, new int[]{ c1, c2 }));
            new MaterialAlertDialogBuilder(requireContext())
                    .setTitle("New collection").setView(dialogView)
                    .setPositiveButton("Create", (dialog, which) -> {
                        String name = editTextName.getText() != null ? editTextName.getText().toString().trim() : "";
                        if (!name.isEmpty()) createCollectionAndAddSong(name, musicItem);
                        else Toast.makeText(requireContext(), "Collection name cannot be empty", Toast.LENGTH_SHORT).show();
                    })
                    .setNegativeButton("Cancel", null).show();
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
            Toast.makeText(requireContext(), "Created \"" + collectionName + "\" and added song", Toast.LENGTH_SHORT).show();
            broadcastCollectionChange(ACTION_COLLECTION_CREATED);
        }
    
        private void broadcastCollectionChange(String action) {
            if (getContext() == null) return;
            try {
                Intent intent = new Intent(action);
                intent.setPackage(requireContext().getPackageName());
                requireContext().sendBroadcast(intent);
            } catch (Exception e) { e.printStackTrace(); }
        }
    
        // ── Playback ──────────────────────────────────────────────────────────────
    
        private void startMusicServiceAndOpenNowPlaying(MusicItem musicItem) {
            playCountManager.increment(musicItem.getId());
            startMusicServiceWithPlaylist(musicItem);
            new android.os.Handler().postDelayed(() -> openNowPlaying(musicItem), 200);
        }
    
        private void startMusicServiceWithPlaylist(MusicItem selectedSong) {
            if (getContext() == null || musicList.isEmpty()) return;
            int idx = 0;
            for (int i = 0; i < musicList.size(); i++)
                if (musicList.get(i).getId() == selectedSong.getId()) { idx = i; break; }
            Intent pi = new Intent(getContext(), MusicService.class);
            pi.setAction(MusicService.ACTION_SET_PLAYLIST);
            pi.putParcelableArrayListExtra("playlist", new ArrayList<>(musicList));
            pi.putExtra("start_index", idx);
            getContext().startService(pi);
            Intent play = new Intent(getContext(), MusicService.class);
            play.setAction(MusicService.ACTION_PLAY);
            play.putExtra("music_item", selectedSong);
            getContext().startService(play);
        }
    
        private void openNowPlaying(MusicItem musicItem) {
            Intent intent = new Intent(getContext(), NowPlayingActivity.class);
            intent.putExtra("music_item", (Parcelable) musicItem);
            startActivity(intent);
            if (getActivity() != null)
                getActivity().overridePendingTransition(R.anim.slide_in_bottom, R.anim.slide_out_top);
        }
    
        // ── Sort ──────────────────────────────────────────────────────────────────
    
        private void applySort(SortMode mode) {
            currentSort = mode;
            List<MusicItem> sorted = new ArrayList<>(originalMusicList);
            switch (mode) {
                case RECENTLY_ADDED: sorted.sort((a, b) -> Long.compare(b.getId(), a.getId())); break;
                case MOST_PLAYED:    sorted.sort((a, b) -> Integer.compare(
                        playCountManager.getCount(b.getId()), playCountManager.getCount(a.getId()))); break;
                case A_Z:            sorted.sort((a, b) -> a.getTitle().compareToIgnoreCase(b.getTitle())); break;
                case Z_A:            sorted.sort((a, b) -> b.getTitle().compareToIgnoreCase(a.getTitle())); break;
                default: break;
            }
            musicList.clear();
            musicList.addAll(sorted);
            if (musicAdapter != null) musicAdapter.notifyDataSetChanged();
        }
    
        // ── Data loading ──────────────────────────────────────────────────────────
    
        private void loadMusicData() {
            if (isLoading) return;
            if (isCacheValid() && hasLoadedOnce) { loadFromCache(); return; }
            checkPermissionAndLoadMusic();
        }
    
        private boolean isCacheValid() {
            return cachedMusicList != null && !cachedMusicList.isEmpty() &&
                    (System.currentTimeMillis() - lastCacheTime) < CACHE_DURATION;
        }
    
        private void loadFromCache() {
            if (binding == null) return;
            binding.musicRecyclerView.post(() -> {
                musicList.clear();
                musicList.addAll(cachedMusicList);
                restoreSongPinnedState(musicList);
                if (musicAdapter != null) musicAdapter.notifyDataSetChanged();
                 refreshPinnedStrip(); binding.musicRecyclerView.scrollToPosition(0); 
                updateUI();
            });
        }
    
        private void checkPermissionAndLoadMusic() {
            String permission = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                    ? Manifest.permission.READ_MEDIA_AUDIO : Manifest.permission.READ_EXTERNAL_STORAGE;
            if (ContextCompat.checkSelfPermission(requireContext(), permission) != PackageManager.PERMISSION_GRANTED)
                ActivityCompat.requestPermissions(requireActivity(), new String[]{permission}, PERMISSION_REQUEST_CODE);
            else
                loadMusicFromDevice();
        }
    
        @Override
        public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                               @NonNull int[] grantResults) {
            super.onRequestPermissionsResult(requestCode, permissions, grantResults);
            if (requestCode == PERMISSION_REQUEST_CODE) {
                if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    permissionJustGranted = true;
                    Toast.makeText(getContext(), "Loading music files...", Toast.LENGTH_SHORT).show();
                    if (binding != null)
                        binding.getRoot().postDelayed(() -> { if (getContext() != null && !isLoading) loadMusicFromDevice(); }, 100);
                    else
                        loadMusicFromDevice();
                } else {
                    Toast.makeText(getContext(), "Permission denied.", Toast.LENGTH_SHORT).show();
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
                String[] projection = { MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE,
                        MediaStore.Audio.Media.ARTIST, MediaStore.Audio.Media.ALBUM,
                        MediaStore.Audio.Media.DURATION, MediaStore.Audio.Media.DATA,
                        MediaStore.Audio.Media.ALBUM_ID };
                try (Cursor cursor = cr.query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, projection,
                        MediaStore.Audio.Media.IS_MUSIC + " != 0", null, MediaStore.Audio.Media.TITLE + " ASC")) {
                    if (cursor != null && cursor.moveToFirst()) {
                        do {
                            long id       = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID));
                            String title  = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE));
                            String artist = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST));
                            String album  = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM));
                            long duration = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION));
                            String path   = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA));
                            long albumId  = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID));
                            tempList.add(new MusicItem(id, title, artist, album, duration, path,
                                    Uri.parse("content://media/external/audio/albumart/" + albumId)));
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
    
                restoreSongPinnedState(tempList);
    
                requireActivity().runOnUiThread(() -> {
                    isLoading = false;
                    hasLoadedOnce = true;
                    cachedMusicList = new ArrayList<>(tempList);
                    lastCacheTime = System.currentTimeMillis();
                    int previousSize = musicList.size();
                    musicList.clear();
                    musicList.addAll(tempList);
                    originalMusicList.clear();
                    originalMusicList.addAll(tempList);
                    if (musicAdapter != null) musicAdapter.notifyDataSetChanged();
                     refreshPinnedStrip(); binding.musicRecyclerView.scrollToPosition(0); 
                    updateUI();
                    int newCount = tempList.size();
                    if (newCount == 0)
                        Toast.makeText(getContext(), "No music files found", Toast.LENGTH_SHORT).show();
                    else if (newCount == previousSize)
                        Toast.makeText(getContext(), "Library up to date (" + newCount + " songs)", Toast.LENGTH_SHORT).show();
                });
            });
        }
    
    
        private void updateUI() {
            if (binding == null) return;
            boolean empty = musicList.isEmpty();
            binding.emptyState.setVisibility(empty ? View.VISIBLE : View.GONE);
            binding.musicRecyclerView.setVisibility(empty ? View.GONE : View.VISIBLE);
        }
    
        public void refreshData() {
            cachedMusicList = null; lastCacheTime = 0; hasLoadedOnce = false;
            if (getContext() != null) Toast.makeText(getContext(), "Refreshing music library...", Toast.LENGTH_SHORT).show();
            loadMusicData();
        }
    
        public static void clearCache() { cachedMusicList = null; lastCacheTime = 0; }
    }
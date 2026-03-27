package com.alfahrel.melody.ui.album;

import android.Manifest;
import android.content.ContentResolver;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.alfahrel.melody.R;
import com.alfahrel.melody.databinding.FragmentAlbumBinding;
import com.alfahrel.melody.service.MusicService;
import com.alfahrel.melody.ui.music.MusicItem;
import com.alfahrel.melody.utils.GsonHelper;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class AlbumFragment extends Fragment {

    private FragmentAlbumBinding binding;
    private AlbumAdapter albumAdapter;
    private List<AlbumItem> albumList = new ArrayList<>();
    private ExecutorService executorService;
    private static final int PERMISSION_REQUEST_CODE = 124;

    private static List<AlbumItem> cachedAlbumList = null;
    private static long lastCacheTime = 0;
    private static final long CACHE_DURATION = 5 * 60 * 1000;
    private boolean isLoading = false;
    private boolean hasLoadedOnce = false;
    private boolean permissionJustGranted = false;

    // Shared prefs — MusicFragment reads this same key
    public static final String PREFS_NAME  = "AlbumsPrefs";
    public static final String KEY_ALBUMS  = "albums_full";
    private Gson gson;
    private SharedPreferences preferences;

    public View onCreateView(@NonNull LayoutInflater inflater,
                             ViewGroup container, Bundle savedInstanceState) {
        new ViewModelProvider(this).get(AlbumViewModel.class);

        binding = FragmentAlbumBinding.inflate(inflater, container, false);
        View root = binding.getRoot();

        executorService = Executors.newSingleThreadExecutor();
        gson = GsonHelper.get();
        preferences = requireContext().getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE);

        setupRecyclerView();
        loadAlbumData();

        root.postDelayed(() -> {
            if (getContext() != null && albumList.isEmpty() && !isLoading) {
                String permission = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU
                        ? Manifest.permission.READ_MEDIA_AUDIO
                        : Manifest.permission.READ_EXTERNAL_STORAGE;
                if (ContextCompat.checkSelfPermission(getContext(), permission) == PackageManager.PERMISSION_GRANTED)
                    loadAlbumsFromDevice();
            }
        }, 300);

        return root;
    }

    // ── Pin ───────────────────────────────────────────────────────────────────

    private void togglePin(AlbumItem album) {
        executorService.execute(() -> {
            boolean nowPinned = false;
            for (AlbumItem a : albumList) {
                if (a.getAlbumId() == album.getAlbumId()) {
                    nowPinned = !a.isPinned();
                    a.setPinned(nowPinned);
                    break;
                }
            }
            saveAlbumList();
            final boolean pinned = nowPinned;
            requireActivity().runOnUiThread(() -> {
                if (albumAdapter != null) albumAdapter.notifyDataSetChanged();
                Toast.makeText(requireContext(),
                        pinned ? "Pinned to Home" : "Unpinned from Home",
                        Toast.LENGTH_SHORT).show();
                broadcastChange();
            });
        });
    }

    /** Saves the full album list (with pin flags) so MusicFragment can read it. */
    private void saveAlbumList() {
        preferences.edit().putString(KEY_ALBUMS, gson.toJson(albumList)).apply();
    }

    /** Restores pin flags from the saved full list into the given list. */
    private void restorePinnedState(List<AlbumItem> list) {
        String json = preferences.getString(KEY_ALBUMS, null);
        if (json == null) return;
        Type type = new TypeToken<List<AlbumItem>>(){}.getType();
        List<AlbumItem> saved = gson.fromJson(json, type);
        if (saved == null) return;
        Map<Long, Boolean> pinMap = new HashMap<>();
        for (AlbumItem a : saved) pinMap.put(a.getAlbumId(), a.isPinned());
        for (AlbumItem a : list) {
            Boolean pinned = pinMap.get(a.getAlbumId());
            if (pinned != null) a.setPinned(pinned);
        }
    }

    private void broadcastChange() {
        if (getContext() == null) return;
        try {
            Intent intent = new Intent("com.alfahrel.melody.ALBUM_CHANGED");
            intent.setPackage(getContext().getPackageName());
            getContext().sendBroadcast(intent);
        } catch (Exception e) { e.printStackTrace(); }
    }

    // ── Navigation / playback ─────────────────────────────────────────────────

    private void openAlbumDetail(AlbumItem albumItem) {
        if (getContext() == null) return;
        Intent intent = new Intent(getContext(), AlbumDetailActivity.class);
        intent.putExtra("album_item", albumItem);
        startActivity(intent);
    }

    private void playAllSongsFromAlbum(AlbumItem albumItem) {
        if (getContext() == null) return;
        if (!hasStoragePermission()) {
            Toast.makeText(getContext(), "Storage permission required to play music", Toast.LENGTH_SHORT).show();
            return;
        }
        executorService.execute(() -> {
            List<MusicItem> albumSongs = loadSongsFromAlbum(albumItem.getAlbumId());
            if (!albumSongs.isEmpty()) {
                requireActivity().runOnUiThread(() -> {
                    startAlbumPlayback(albumSongs);
                    Toast.makeText(getContext(), "Playing " + albumItem.getAlbumName(), Toast.LENGTH_SHORT).show();
                });
            } else {
                requireActivity().runOnUiThread(() ->
                        Toast.makeText(getContext(), "No songs found in this album", Toast.LENGTH_SHORT).show());
            }
        });
    }

    private List<MusicItem> loadSongsFromAlbum(long albumId) {
        List<MusicItem> songs = new ArrayList<>();
        if (getContext() == null) return songs;
        ContentResolver cr = requireContext().getContentResolver();
        String[] projection = {
                MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE,
                MediaStore.Audio.Media.ARTIST, MediaStore.Audio.Media.ALBUM,
                MediaStore.Audio.Media.DURATION, MediaStore.Audio.Media.DATA,
                MediaStore.Audio.Media.ALBUM_ID, MediaStore.Audio.Media.TRACK
        };
        try (Cursor cursor = cr.query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, projection,
                MediaStore.Audio.Media.IS_MUSIC + " != 0 AND " + MediaStore.Audio.Media.ALBUM_ID + " = ?",
                new String[]{String.valueOf(albumId)}, MediaStore.Audio.Media.TRACK + " ASC")) {
            if (cursor != null && cursor.moveToFirst()) {
                do {
                    long id          = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID));
                    String title     = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE));
                    String artist    = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST));
                    String album     = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM));
                    long duration    = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION));
                    String path      = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA));
                    long songAlbumId = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID));
                    songs.add(new MusicItem(id, title, artist, album, duration, path,
                            Uri.parse("content://media/external/audio/albumart/" + songAlbumId)));
                } while (cursor.moveToNext());
            }
        } catch (Exception e) { e.printStackTrace(); }
        return songs;
    }

    private void startAlbumPlayback(List<MusicItem> songs) {
        if (getContext() == null || songs.isEmpty()) return;
        Intent pi = new Intent(getContext(), MusicService.class);
        pi.setAction(MusicService.ACTION_SET_PLAYLIST);
        pi.putParcelableArrayListExtra("playlist", new ArrayList<>(songs));
        pi.putExtra("start_index", 0);
        getContext().startService(pi);
        Intent play = new Intent(getContext(), MusicService.class);
        play.setAction(MusicService.ACTION_PLAY);
        play.putExtra("music_item", songs.get(0));
        getContext().startService(play);
    }

    // ── Data loading ──────────────────────────────────────────────────────────

    private void loadAlbumData() {
        if (isLoading) return;
        if (isCacheValid() && hasLoadedOnce) { loadFromCache(); return; }
        checkPermissionAndLoadAlbums();
    }

    private boolean isCacheValid() {
        return cachedAlbumList != null && !cachedAlbumList.isEmpty() &&
                (System.currentTimeMillis() - lastCacheTime) < CACHE_DURATION;
    }

    private void loadFromCache() {
        if (binding == null) return;
        binding.albumRecyclerView.post(() -> {
            albumList.clear();
            albumList.addAll(cachedAlbumList);
            restorePinnedState(albumList);
            if (albumAdapter != null) albumAdapter.notifyDataSetChanged();
            updateUI();
        });
    }

    private void setupRecyclerView() {
        RecyclerView rv = binding.albumRecyclerView;
        rv.setHasFixedSize(true);
        rv.setItemViewCacheSize(20);
        rv.setDrawingCacheEnabled(true);
        rv.setDrawingCacheQuality(View.DRAWING_CACHE_QUALITY_HIGH);
        rv.setLayoutManager(new GridLayoutManager(getContext(), 2));

        albumAdapter = new AlbumAdapter(albumList, getContext());
        rv.setAdapter(albumAdapter);

        albumAdapter.setOnAlbumItemClickListener(new AlbumAdapter.OnAlbumItemClickListener() {
            @Override public void onAlbumItemClick(AlbumItem a)  { openAlbumDetail(a); }
            @Override public void onPlayButtonClick(AlbumItem a) { playAllSongsFromAlbum(a); }
            @Override public void onPinClick(AlbumItem a)        { togglePin(a); }
        });
    }

    private boolean hasStoragePermission() {
        String p = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU
                ? Manifest.permission.READ_MEDIA_AUDIO : Manifest.permission.READ_EXTERNAL_STORAGE;
        return ContextCompat.checkSelfPermission(requireContext(), p) == PackageManager.PERMISSION_GRANTED;
    }

    private void checkPermissionAndLoadAlbums() {
        String p = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU
                ? Manifest.permission.READ_MEDIA_AUDIO : Manifest.permission.READ_EXTERNAL_STORAGE;
        if (ContextCompat.checkSelfPermission(requireContext(), p) != PackageManager.PERMISSION_GRANTED)
            ActivityCompat.requestPermissions(requireActivity(), new String[]{p}, PERMISSION_REQUEST_CODE);
        else
            loadAlbumsFromDevice();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                permissionJustGranted = true;
                Toast.makeText(getContext(), "Loading albums...", Toast.LENGTH_SHORT).show();
                if (binding != null)
                    binding.getRoot().postDelayed(() -> { if (getContext() != null && !isLoading) loadAlbumsFromDevice(); }, 100);
                else
                    loadAlbumsFromDevice();
            } else {
                Toast.makeText(getContext(), "Permission denied.", Toast.LENGTH_SHORT).show();
                updateUI();
            }
        }
    }

    private void loadAlbumsFromDevice() {
        if (isLoading) return;
        isLoading = true;
        showLoading(true);

        executorService.execute(() -> {
            Map<String, AlbumItem> albumMap = new HashMap<>();
            ContentResolver cr = requireContext().getContentResolver();
            String[] projection = { MediaStore.Audio.Media.ALBUM, MediaStore.Audio.Media.ARTIST,
                    MediaStore.Audio.Media.ALBUM_ID, MediaStore.Audio.Media._ID };
            try (Cursor cursor = cr.query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, projection,
                    MediaStore.Audio.Media.IS_MUSIC + " != 0", null, MediaStore.Audio.Media.ALBUM + " ASC")) {
                if (cursor != null && cursor.moveToFirst()) {
                    do {
                        String albumName  = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM));
                        String artistName = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST));
                        long albumId      = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID));
                        if (albumName != null && !albumName.trim().isEmpty()) {
                            String key = albumId + "_" + albumName;
                            if (!albumMap.containsKey(key)) {
                                albumMap.put(key, new AlbumItem(albumId, albumName,
                                        artistName != null ? artistName : "Unknown Artist",
                                        Uri.parse("content://media/external/audio/albumart/" + albumId), 1));
                            } else {
                                AlbumItem e = albumMap.get(key);
                                if (e != null) e.setSongCount(e.getSongCount() + 1);
                            }
                        }
                    } while (cursor.moveToNext());
                }
            } catch (Exception e) {
                e.printStackTrace();
                requireActivity().runOnUiThread(() -> { showLoading(false); isLoading = false;
                    Toast.makeText(getContext(), "Error loading albums", Toast.LENGTH_SHORT).show(); });
                return;
            }

            List<AlbumItem> tempList = new ArrayList<>(albumMap.values());
            restorePinnedState(tempList);

            requireActivity().runOnUiThread(() -> {
                showLoading(false);
                isLoading = false;
                hasLoadedOnce = true;
                cachedAlbumList = new ArrayList<>(tempList);
                lastCacheTime = System.currentTimeMillis();
                albumList.clear();
                albumList.addAll(tempList);
                saveAlbumList(); // ← persist full list so MusicFragment can read it
                if (albumAdapter != null) albumAdapter.notifyDataSetChanged();
                updateUI();
            });
        });
    }

    private void updateUI() {
        if (binding == null) return;
        binding.emptyState.setVisibility(albumList.isEmpty() ? View.VISIBLE : View.GONE);
        binding.albumRecyclerView.setVisibility(albumList.isEmpty() ? View.GONE : View.VISIBLE);
    }

    private void showLoading(boolean show) {
        if (binding == null) return;
        if (show) {
            binding.loadingLayout.setVisibility(View.VISIBLE);
            binding.albumRecyclerView.setVisibility(View.GONE);
            binding.emptyState.setVisibility(View.GONE);
            if (binding.loadingCount != null) binding.loadingCount.setVisibility(View.GONE);
        } else {
            binding.loadingLayout.setVisibility(View.GONE);
        }
    }

    public void refreshData() {
        cachedAlbumList = null; lastCacheTime = 0; hasLoadedOnce = false;
        if (getContext() != null) Toast.makeText(getContext(), "Refreshing album library...", Toast.LENGTH_SHORT).show();
        loadAlbumData();
    }

    public static void clearCache() { cachedAlbumList = null; lastCacheTime = 0; }

    @Override
    public void onResume() {
        super.onResume();
        String p = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU
                ? Manifest.permission.READ_MEDIA_AUDIO : Manifest.permission.READ_EXTERNAL_STORAGE;
        if (getContext() != null &&
                ContextCompat.checkSelfPermission(getContext(), p) == PackageManager.PERMISSION_GRANTED &&
                albumList.isEmpty() && !isLoading) {
            permissionJustGranted = false;
            if (binding != null)
                binding.getRoot().postDelayed(() -> { if (getContext() != null && albumList.isEmpty() && !isLoading) loadAlbumsFromDevice(); }, 200);
            else
                loadAlbumsFromDevice();
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        if (executorService != null && !executorService.isShutdown()) executorService.shutdown();
        binding = null;
    }
}
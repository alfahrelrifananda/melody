package com.alfahrel.melody.ui.album;

import android.Manifest;
import android.content.ContentResolver;
import android.content.Intent;
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

    public View onCreateView(@NonNull LayoutInflater inflater,
                             ViewGroup container, Bundle savedInstanceState) {
        AlbumViewModel albumViewModel =
                new ViewModelProvider(this).get(AlbumViewModel.class);

        binding = FragmentAlbumBinding.inflate(inflater, container, false);
        View root = binding.getRoot();

        executorService = Executors.newSingleThreadExecutor();
        setupRecyclerView();

        loadAlbumData();

        root.postDelayed(() -> {
            if (getContext() != null && albumList.isEmpty() && !isLoading) {
                String permission;
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                    permission = Manifest.permission.READ_MEDIA_AUDIO;
                } else {
                    permission = Manifest.permission.READ_EXTERNAL_STORAGE;
                }

                if (ContextCompat.checkSelfPermission(getContext(), permission) == PackageManager.PERMISSION_GRANTED) {
                    loadAlbumsFromDevice();
                }
            }
        }, 300);

        return root;
    }

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
                requireActivity().runOnUiThread(() -> {
                    Toast.makeText(getContext(), "No songs found in this album", Toast.LENGTH_SHORT).show();
                });
            }
        });
    }

    private List<MusicItem> loadSongsFromAlbum(long albumId) {
        List<MusicItem> songs = new ArrayList<>();

        if (getContext() == null) return songs;

        ContentResolver contentResolver = requireContext().getContentResolver();
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
                MediaStore.Audio.Media.ALBUM_ID + " = ?";
        String[] selectionArgs = {String.valueOf(albumId)};
        String sortOrder = MediaStore.Audio.Media.TRACK + " ASC, " +
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
                    long songAlbumId = cursor.getLong(albumIdColumn);

                    Uri albumArtUri = Uri.parse("content://media/external/audio/albumart/" + songAlbumId);

                    MusicItem musicItem = new MusicItem(id, title, artist, album, duration, path, albumArtUri);
                    songs.add(musicItem);

                } while (cursor.moveToNext());
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        return songs;
    }

    private void startAlbumPlayback(List<MusicItem> albumSongs) {
        if (getContext() == null || albumSongs.isEmpty()) return;

        Intent playlistIntent = new Intent(getContext(), MusicService.class);
        playlistIntent.setAction(MusicService.ACTION_SET_PLAYLIST);
        playlistIntent.putParcelableArrayListExtra("playlist", new ArrayList<>(albumSongs));
        playlistIntent.putExtra("start_index", 0);
        getContext().startService(playlistIntent);

        Intent playIntent = new Intent(getContext(), MusicService.class);
        playIntent.setAction(MusicService.ACTION_PLAY);
        playIntent.putExtra("music_item", albumSongs.get(0));
        getContext().startService(playIntent);
    }

    private void loadAlbumData() {
        if (isLoading) {
            return;
        }

        if (isCacheValid() && hasLoadedOnce) {
            loadFromCache();
            return;
        }

        checkPermissionAndLoadAlbums();
    }

    private boolean isCacheValid() {
        return cachedAlbumList != null &&
                !cachedAlbumList.isEmpty() &&
                (System.currentTimeMillis() - lastCacheTime) < CACHE_DURATION;
    }

    private void loadFromCache() {
        if (binding != null && binding.albumRecyclerView != null) {
            binding.albumRecyclerView.post(() -> {
                albumList.clear();
                albumList.addAll(cachedAlbumList);

                if (albumAdapter != null) {
                    albumAdapter.notifyDataSetChanged();
                }

                updateUI();
            });
        }
    }

    private void setupRecyclerView() {
        RecyclerView recyclerView = binding.albumRecyclerView;

        recyclerView.setHasFixedSize(true);
        recyclerView.setItemViewCacheSize(20);
        recyclerView.setDrawingCacheEnabled(true);
        recyclerView.setDrawingCacheQuality(View.DRAWING_CACHE_QUALITY_HIGH);

        GridLayoutManager gridLayoutManager = new GridLayoutManager(getContext(), 2);
        recyclerView.setLayoutManager(gridLayoutManager);

        albumAdapter = new AlbumAdapter(albumList, getContext());
        recyclerView.setAdapter(albumAdapter);

        albumAdapter.setOnAlbumItemClickListener(new AlbumAdapter.OnAlbumItemClickListener() {
            @Override
            public void onAlbumItemClick(AlbumItem albumItem) {
                openAlbumDetail(albumItem);
            }

            @Override
            public void onPlayButtonClick(AlbumItem albumItem) {
                playAllSongsFromAlbum(albumItem);
            }
        });
    }

    private boolean hasStoragePermission() {
        String permission;
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            permission = Manifest.permission.READ_MEDIA_AUDIO;
        } else {
            permission = Manifest.permission.READ_EXTERNAL_STORAGE;
        }
        return ContextCompat.checkSelfPermission(requireContext(), permission) == PackageManager.PERMISSION_GRANTED;
    }

    private void checkPermissionAndLoadAlbums() {
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
            loadAlbumsFromDevice();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                permissionJustGranted = true;
                Toast.makeText(getContext(), "Loading albums...", Toast.LENGTH_SHORT).show();

                if (binding != null && binding.getRoot() != null) {
                    binding.getRoot().postDelayed(() -> {
                        if (getContext() != null && !isLoading) {
                            loadAlbumsFromDevice();
                        }
                    }, 100);
                } else {
                    loadAlbumsFromDevice();
                }
            } else {
                Toast.makeText(getContext(), "Permission denied. Cannot access music files.",
                        Toast.LENGTH_SHORT).show();
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
            ContentResolver contentResolver = requireContext().getContentResolver();
            Uri musicUri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;

            String[] projection = {
                    MediaStore.Audio.Media.ALBUM,
                    MediaStore.Audio.Media.ARTIST,
                    MediaStore.Audio.Media.ALBUM_ID,
                    MediaStore.Audio.Media._ID
            };

            String selection = MediaStore.Audio.Media.IS_MUSIC + " != 0";
            String sortOrder = MediaStore.Audio.Media.ALBUM + " ASC";

            try (Cursor cursor = contentResolver.query(musicUri, projection, selection, null, sortOrder)) {
                if (cursor != null && cursor.moveToFirst()) {
                    int albumColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM);
                    int artistColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST);
                    int albumIdColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID);
                    int idColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID);

                    do {
                        String albumName = cursor.getString(albumColumn);
                        String artistName = cursor.getString(artistColumn);
                        long albumId = cursor.getLong(albumIdColumn);

                        if (albumName != null && !albumName.trim().isEmpty()) {
                            String albumKey = albumId + "_" + albumName;

                            if (!albumMap.containsKey(albumKey)) {
                                Uri albumArtUri = Uri.parse("content://media/external/audio/albumart/" + albumId);

                                AlbumItem albumItem = new AlbumItem(
                                        albumId,
                                        albumName,
                                        artistName != null ? artistName : "Unknown Artist",
                                        albumArtUri,
                                        1
                                );
                                albumMap.put(albumKey, albumItem);
                            } else {
                                AlbumItem existingAlbum = albumMap.get(albumKey);
                                if (existingAlbum != null) {
                                    existingAlbum.setSongCount(existingAlbum.getSongCount() + 1);
                                }
                            }
                        }

                    } while (cursor.moveToNext());
                }
            } catch (Exception e) {
                e.printStackTrace();
                requireActivity().runOnUiThread(() -> {
                    showLoading(false);
                    isLoading = false;
                    Toast.makeText(getContext(), "Error loading albums", Toast.LENGTH_SHORT).show();
                });
                return;
            }

            List<AlbumItem> tempAlbumList = new ArrayList<>(albumMap.values());

            requireActivity().runOnUiThread(() -> {
                showLoading(false);
                isLoading = false;
                hasLoadedOnce = true;

                cachedAlbumList = new ArrayList<>(tempAlbumList);
                lastCacheTime = System.currentTimeMillis();

                int previousSize = albumList.size();
                albumList.clear();
                albumList.addAll(tempAlbumList);

                if (albumAdapter != null) {
                    albumAdapter.notifyDataSetChanged();
                }

                updateUI();

                int newCount = tempAlbumList.size();
                if (newCount > 0) {
                    if (newCount == previousSize) {
                        Toast.makeText(getContext(),
                                "Album library is up to date (" + newCount + " albums)",
                                Toast.LENGTH_SHORT).show();
                    } else {
//                        Toast.makeText(getContext(),
//                                "Library updated: " + newCount + " albums",
//                                Toast.LENGTH_SHORT).show();
                    }
                } else {
                    Toast.makeText(getContext(),
                            "No albums found",
                            Toast.LENGTH_SHORT).show();
                }
            });
        });
    }

    private void updateUI() {
        if (binding == null) return;

        if (albumList.isEmpty()) {
            binding.emptyState.setVisibility(View.VISIBLE);
            binding.albumRecyclerView.setVisibility(View.GONE);
        } else {
            binding.emptyState.setVisibility(View.GONE);
            binding.albumRecyclerView.setVisibility(View.VISIBLE);
        }
    }

    private void showLoading(boolean show) {
        if (binding == null) return;

        if (show) {
            binding.loadingLayout.setVisibility(View.VISIBLE);
            binding.albumRecyclerView.setVisibility(View.GONE);
            binding.emptyState.setVisibility(View.GONE);

            if (binding.loadingCount != null) {
                binding.loadingCount.setVisibility(View.GONE);
            }
        } else {
            binding.loadingLayout.setVisibility(View.GONE);
        }
    }

    public void refreshData() {
        cachedAlbumList = null;
        lastCacheTime = 0;
        hasLoadedOnce = false;

        if (getContext() != null) {
            Toast.makeText(getContext(), "Refreshing album library...", Toast.LENGTH_SHORT).show();
        }

        loadAlbumData();
    }

    public static void clearCache() {
        cachedAlbumList = null;
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
                albumList.isEmpty() &&
                !isLoading) {
            permissionJustGranted = false;

            if (binding != null && binding.getRoot() != null) {
                binding.getRoot().postDelayed(() -> {
                    if (getContext() != null && albumList.isEmpty() && !isLoading) {
                        loadAlbumsFromDevice();
                    }
                }, 200);
            } else {
                loadAlbumsFromDevice();
            }
        }
    }

    @Override
    public void setUserVisibleHint(boolean isVisibleToUser) {
        super.setUserVisibleHint(isVisibleToUser);

        if (isVisibleToUser && isResumed()) {
            checkAndLoadIfNeeded();
        }
    }

    @Override
    public void onHiddenChanged(boolean hidden) {
        super.onHiddenChanged(hidden);

        if (!hidden) {
            checkAndLoadIfNeeded();
        }
    }

    private void checkAndLoadIfNeeded() {
        String permission;
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            permission = Manifest.permission.READ_MEDIA_AUDIO;
        } else {
            permission = Manifest.permission.READ_EXTERNAL_STORAGE;
        }

        if (getContext() != null &&
                ContextCompat.checkSelfPermission(getContext(), permission) == PackageManager.PERMISSION_GRANTED &&
                albumList.isEmpty() &&
                !isLoading &&
                binding != null) {

            loadAlbumsFromDevice();
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
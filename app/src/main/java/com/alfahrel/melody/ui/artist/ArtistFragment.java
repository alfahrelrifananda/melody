package com.alfahrel.melody.ui.artist;

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
import com.alfahrel.melody.databinding.FragmentArtistBinding;
import com.alfahrel.melody.service.MusicService;
import com.alfahrel.melody.ui.music.MusicItem;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ArtistFragment extends Fragment {

    private FragmentArtistBinding binding;
    private ArtistAdapter artistAdapter;
    private List<ArtistItem> artistList = new ArrayList<>();
    private ExecutorService executorService;
    private static final int PERMISSION_REQUEST_CODE = 125;

    private static List<ArtistItem> cachedArtistList = null;
    private static long lastCacheTime = 0;
    private static final long CACHE_DURATION = 5 * 60 * 1000;
    private boolean isLoading = false;

    public View onCreateView(@NonNull LayoutInflater inflater,
                             ViewGroup container, Bundle savedInstanceState) {
        ArtistViewModel artistViewModel =
                new ViewModelProvider(this).get(ArtistViewModel.class);

        binding = FragmentArtistBinding.inflate(inflater, container, false);
        View root = binding.getRoot();

        executorService = Executors.newSingleThreadExecutor();
        setupRecyclerView();
        loadArtistData();

        return root;
    }

    private void openArtistDetail(ArtistItem artistItem) {
        if (getContext() == null) return;

        Intent intent = new Intent(getContext(), ArtistDetailActivity.class);
        intent.putExtra("artist_item", artistItem);
        startActivity(intent);
    }

    private void playAllSongsFromArtist(ArtistItem artistItem) {
        if (getContext() == null) return;

        if (!hasStoragePermission()) {
            Toast.makeText(getContext(), "Storage permission required to play music", Toast.LENGTH_SHORT).show();
            return;
        }

        executorService.execute(() -> {
            List<MusicItem> artistSongs = loadSongsFromArtist(artistItem.getArtistName());

            if (!artistSongs.isEmpty()) {
                requireActivity().runOnUiThread(() -> {
                    startArtistPlayback(artistSongs);
                    Toast.makeText(getContext(), "Playing " + artistItem.getArtistName(), Toast.LENGTH_SHORT).show();
                });
            } else {
                requireActivity().runOnUiThread(() -> {
                    Toast.makeText(getContext(), "No songs found for this artist", Toast.LENGTH_SHORT).show();
                });
            }
        });
    }

    private List<MusicItem> loadSongsFromArtist(String artistName) {
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
                MediaStore.Audio.Media.ARTIST + " = ?";
        String[] selectionArgs = {artistName};
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
                    songs.add(musicItem);

                } while (cursor.moveToNext());
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        return songs;
    }

    private void startArtistPlayback(List<MusicItem> artistSongs) {
        if (getContext() == null || artistSongs.isEmpty()) return;

        Intent playlistIntent = new Intent(getContext(), MusicService.class);
        playlistIntent.setAction(MusicService.ACTION_SET_PLAYLIST);
        playlistIntent.putParcelableArrayListExtra("playlist", new ArrayList<>(artistSongs));
        playlistIntent.putExtra("start_index", 0);
        getContext().startService(playlistIntent);

        Intent playIntent = new Intent(getContext(), MusicService.class);
        playIntent.setAction(MusicService.ACTION_PLAY);
        playIntent.putExtra("music_item", artistSongs.get(0));
        getContext().startService(playIntent);
    }

    private void loadArtistData() {
        if (isCacheValid()) {
            loadFromCache();
            return;
        }

        checkPermissionAndLoadArtists();
    }

    private boolean isCacheValid() {
        return cachedArtistList != null &&
                !cachedArtistList.isEmpty() &&
                (System.currentTimeMillis() - lastCacheTime) < CACHE_DURATION;
    }

    private void loadFromCache() {
        if (binding != null && binding.artistRecyclerView != null) {
            binding.artistRecyclerView.post(() -> {
                artistList.clear();
                artistList.addAll(cachedArtistList);

                if (artistAdapter != null) {
                    artistAdapter.notifyDataSetChanged();
                }

                updateUI();
            });
        }
    }

    private void setupRecyclerView() {
        RecyclerView recyclerView = binding.artistRecyclerView;

        recyclerView.setHasFixedSize(true);
        recyclerView.setItemViewCacheSize(20);
        recyclerView.setDrawingCacheEnabled(true);
        recyclerView.setDrawingCacheQuality(View.DRAWING_CACHE_QUALITY_HIGH);

        GridLayoutManager gridLayoutManager = new GridLayoutManager(getContext(), 1);
        recyclerView.setLayoutManager(gridLayoutManager);

        artistAdapter = new ArtistAdapter(artistList, getContext());
        recyclerView.setAdapter(artistAdapter);

        artistAdapter.setOnArtistItemClickListener(new ArtistAdapter.OnArtistItemClickListener() {
            @Override
            public void onArtistItemClick(ArtistItem artistItem) {
                openArtistDetail(artistItem);
            }

            @Override
            public void onPlayButtonClick(ArtistItem artistItem) {
                playAllSongsFromArtist(artistItem);
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

    private void checkPermissionAndLoadArtists() {
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
            loadArtistsFromDevice();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                loadArtistsFromDevice();
            } else {
                Toast.makeText(getContext(), "Permission denied. Cannot access music files.",
                        Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void loadArtistsFromDevice() {
        if (isLoading) return;

        isLoading = true;
        showLoading(true);

        executorService.execute(() -> {
            Map<String, ArtistItem> artistMap = new HashMap<>();
            ContentResolver contentResolver = requireContext().getContentResolver();
            Uri musicUri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;

            String[] projection = {
                    MediaStore.Audio.Media.ARTIST,
                    MediaStore.Audio.Media.ALBUM_ID,
                    MediaStore.Audio.Media._ID,
                    MediaStore.Audio.Media.DURATION
            };

            String selection = MediaStore.Audio.Media.IS_MUSIC + " != 0";
            String sortOrder = MediaStore.Audio.Media.ARTIST + " ASC";

            try (Cursor cursor = contentResolver.query(musicUri, projection, selection, null, sortOrder)) {
                if (cursor != null && cursor.moveToFirst()) {
                    int artistColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST);
                    int albumIdColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID);
                    int idColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID);
                    int durationColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION);

                    do {
                        String artistName = cursor.getString(artistColumn);
                        long albumId = cursor.getLong(albumIdColumn);
                        long duration = cursor.getLong(durationColumn);

                        if (artistName != null && !artistName.trim().isEmpty() &&
                                !artistName.equals("<unknown>")) {

                            if (!artistMap.containsKey(artistName)) {
                                Uri albumArtUri = Uri.parse("content://media/external/audio/albumart/" + albumId);

                                ArtistItem artistItem = new ArtistItem(
                                        artistName,
                                        albumArtUri,
                                        1,
                                        duration
                                );
                                artistMap.put(artistName, artistItem);
                            } else {
                                ArtistItem existingArtist = artistMap.get(artistName);
                                if (existingArtist != null) {
                                    existingArtist.setSongCount(existingArtist.getSongCount() + 1);
                                    existingArtist.addDuration(duration);
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
                    Toast.makeText(getContext(), "Error loading artists", Toast.LENGTH_SHORT).show();
                });
                return;
            }

            List<ArtistItem> tempArtistList = new ArrayList<>(artistMap.values());

            requireActivity().runOnUiThread(() -> {
                showLoading(false);
                isLoading = false;

                cachedArtistList = new ArrayList<>(tempArtistList);
                lastCacheTime = System.currentTimeMillis();

                artistList.clear();
                artistList.addAll(tempArtistList);

                if (artistAdapter != null) {
                    artistAdapter.notifyDataSetChanged();
                }

                updateUI();
            });
        });
    }

    private void updateUI() {
        if (binding == null) return;

        if (artistList.isEmpty()) {
            binding.emptyState.setVisibility(View.VISIBLE);
            binding.artistRecyclerView.setVisibility(View.GONE);
        } else {
            binding.emptyState.setVisibility(View.GONE);
            binding.artistRecyclerView.setVisibility(View.VISIBLE);
        }
    }

    private void showLoading(boolean show) {
        if (binding == null) return;

        if (show) {
            binding.loadingLayout.setVisibility(View.VISIBLE);
            binding.artistRecyclerView.setVisibility(View.GONE);
            binding.emptyState.setVisibility(View.GONE);

            if (binding.loadingCount != null) {
                binding.loadingCount.setVisibility(View.GONE);
            }
        } else {
            binding.loadingLayout.setVisibility(View.GONE);
        }
    }

    public void refreshData() {
        cachedArtistList = null;
        lastCacheTime = 0;
        loadArtistData();
    }

    public static void clearCache() {
        cachedArtistList = null;
        lastCacheTime = 0;
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
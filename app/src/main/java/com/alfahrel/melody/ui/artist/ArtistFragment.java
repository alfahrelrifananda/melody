package com.alfahrel.melody.ui.artist;

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
import com.alfahrel.melody.databinding.FragmentArtistBinding;
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

    // Shared prefs — MusicFragment reads this same key
    public static final String PREFS_NAME   = "ArtistsPrefs";
    public static final String KEY_ARTISTS  = "artists_full";
    private Gson gson;
    private SharedPreferences preferences;

    public View onCreateView(@NonNull LayoutInflater inflater,
                             ViewGroup container, Bundle savedInstanceState) {
        new ViewModelProvider(this).get(ArtistViewModel.class);

        binding = FragmentArtistBinding.inflate(inflater, container, false);
        View root = binding.getRoot();

        executorService = Executors.newSingleThreadExecutor();
        gson = GsonHelper.get();

        preferences = requireContext().getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE);

        setupRecyclerView();
        loadArtistData();

        return root;
    }

    // ── Pin ───────────────────────────────────────────────────────────────────

    private void togglePin(ArtistItem artist) {
        executorService.execute(() -> {
            boolean nowPinned = false;
            for (ArtistItem a : artistList) {
                if (a.getArtistName().equals(artist.getArtistName())) {
                    nowPinned = !a.isPinned();
                    a.setPinned(nowPinned);
                    break;
                }
            }
            saveArtistList();
            final boolean pinned = nowPinned;
            requireActivity().runOnUiThread(() -> {
                if (artistAdapter != null) artistAdapter.notifyDataSetChanged();
                Toast.makeText(requireContext(),
                        pinned ? "Pinned to Home" : "Unpinned from Home",
                        Toast.LENGTH_SHORT).show();
                broadcastChange();
            });
        });
    }

    /** Saves the full artist list (with pin flags) so MusicFragment can read it. */
    private void saveArtistList() {
        preferences.edit().putString(KEY_ARTISTS, gson.toJson(artistList)).apply();
    }

    /** Restores pin flags from the saved full list into the given list. */
    private void restorePinnedState(List<ArtistItem> list) {
        String json = preferences.getString(KEY_ARTISTS, null);
        if (json == null) return;
        Type type = new TypeToken<List<ArtistItem>>(){}.getType();
        List<ArtistItem> saved = gson.fromJson(json, type);
        if (saved == null) return;
        Map<String, Boolean> pinMap = new HashMap<>();
        for (ArtistItem a : saved) pinMap.put(a.getArtistName(), a.isPinned());
        for (ArtistItem a : list) {
            Boolean pinned = pinMap.get(a.getArtistName());
            if (pinned != null) a.setPinned(pinned);
        }
    }

    private void broadcastChange() {
        if (getContext() == null) return;
        try {
            Intent intent = new Intent("com.alfahrel.melody.ARTIST_CHANGED");
            intent.setPackage(getContext().getPackageName());
            getContext().sendBroadcast(intent);
        } catch (Exception e) { e.printStackTrace(); }
    }

    // ── Navigation / playback ─────────────────────────────────────────────────

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
            List<MusicItem> songs = loadSongsFromArtist(artistItem.getArtistName());
            if (!songs.isEmpty()) {
                requireActivity().runOnUiThread(() -> {
                    startArtistPlayback(songs);
                    Toast.makeText(getContext(), "Playing " + artistItem.getArtistName(), Toast.LENGTH_SHORT).show();
                });
            } else {
                requireActivity().runOnUiThread(() ->
                        Toast.makeText(getContext(), "No songs found for this artist", Toast.LENGTH_SHORT).show());
            }
        });
    }

    private List<MusicItem> loadSongsFromArtist(String artistName) {
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
                MediaStore.Audio.Media.IS_MUSIC + " != 0 AND " + MediaStore.Audio.Media.ARTIST + " = ?",
                new String[]{artistName},
                MediaStore.Audio.Media.ALBUM + " ASC, " + MediaStore.Audio.Media.TRACK + " ASC")) {
            if (cursor != null && cursor.moveToFirst()) {
                do {
                    long id       = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID));
                    String title  = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE));
                    String artist = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST));
                    String album  = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM));
                    long duration = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION));
                    String path   = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA));
                    long albumId  = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID));
                    songs.add(new MusicItem(id, title, artist, album, duration, path,
                            Uri.parse("content://media/external/audio/albumart/" + albumId)));
                } while (cursor.moveToNext());
            }
        } catch (Exception e) { e.printStackTrace(); }
        return songs;
    }

    private void startArtistPlayback(List<MusicItem> songs) {
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

    private void loadArtistData() {
        if (isCacheValid()) { loadFromCache(); return; }
        checkPermissionAndLoadArtists();
    }

    private boolean isCacheValid() {
        return cachedArtistList != null && !cachedArtistList.isEmpty() &&
                (System.currentTimeMillis() - lastCacheTime) < CACHE_DURATION;
    }

    private void loadFromCache() {
        if (binding == null) return;
        binding.artistRecyclerView.post(() -> {
            artistList.clear();
            artistList.addAll(cachedArtistList);
            restorePinnedState(artistList);
            if (artistAdapter != null) artistAdapter.notifyDataSetChanged();
            updateUI();
        });
    }

    private void setupRecyclerView() {
        RecyclerView rv = binding.artistRecyclerView;
        rv.setHasFixedSize(true);
        rv.setItemViewCacheSize(20);
        rv.setDrawingCacheEnabled(true);
        rv.setDrawingCacheQuality(View.DRAWING_CACHE_QUALITY_HIGH);
        rv.setLayoutManager(new GridLayoutManager(getContext(), 1));

        artistAdapter = new ArtistAdapter(artistList, getContext());
        rv.setAdapter(artistAdapter);

        artistAdapter.setOnArtistItemClickListener(new ArtistAdapter.OnArtistItemClickListener() {
            @Override public void onArtistItemClick(ArtistItem a)  { openArtistDetail(a); }
            @Override public void onPlayButtonClick(ArtistItem a)  { playAllSongsFromArtist(a); }
            @Override public void onPinClick(ArtistItem a)         { togglePin(a); }
        });
    }

    private boolean hasStoragePermission() {
        String p = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU
                ? Manifest.permission.READ_MEDIA_AUDIO : Manifest.permission.READ_EXTERNAL_STORAGE;
        return ContextCompat.checkSelfPermission(requireContext(), p) == PackageManager.PERMISSION_GRANTED;
    }

    private void checkPermissionAndLoadArtists() {
        String p = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU
                ? Manifest.permission.READ_MEDIA_AUDIO : Manifest.permission.READ_EXTERNAL_STORAGE;
        if (ContextCompat.checkSelfPermission(requireContext(), p) != PackageManager.PERMISSION_GRANTED)
            ActivityCompat.requestPermissions(requireActivity(), new String[]{p}, PERMISSION_REQUEST_CODE);
        else
            loadArtistsFromDevice();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED)
                loadArtistsFromDevice();
            else
                Toast.makeText(getContext(), "Permission denied.", Toast.LENGTH_SHORT).show();
        }
    }

    private void loadArtistsFromDevice() {
        if (isLoading) return;
        isLoading = true;
        showLoading(true);

        executorService.execute(() -> {
            Map<String, ArtistItem> artistMap = new HashMap<>();
            ContentResolver cr = requireContext().getContentResolver();
            String[] projection = { MediaStore.Audio.Media.ARTIST, MediaStore.Audio.Media.ALBUM_ID,
                    MediaStore.Audio.Media._ID, MediaStore.Audio.Media.DURATION };
            try (Cursor cursor = cr.query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, projection,
                    MediaStore.Audio.Media.IS_MUSIC + " != 0", null, MediaStore.Audio.Media.ARTIST + " ASC")) {
                if (cursor != null && cursor.moveToFirst()) {
                    do {
                        String artistName = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST));
                        long albumId      = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID));
                        long duration     = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION));
                        if (artistName != null && !artistName.trim().isEmpty() && !artistName.equals("<unknown>")) {
                            if (!artistMap.containsKey(artistName)) {
                                artistMap.put(artistName, new ArtistItem(artistName,
                                        Uri.parse("content://media/external/audio/albumart/" + albumId), 1, duration));
                            } else {
                                ArtistItem e = artistMap.get(artistName);
                                if (e != null) { e.setSongCount(e.getSongCount() + 1); e.addDuration(duration); }
                            }
                        }
                    } while (cursor.moveToNext());
                }
            } catch (Exception e) {
                e.printStackTrace();
                requireActivity().runOnUiThread(() -> { showLoading(false); isLoading = false;
                    Toast.makeText(getContext(), "Error loading artists", Toast.LENGTH_SHORT).show(); });
                return;
            }

            List<ArtistItem> tempList = new ArrayList<>(artistMap.values());
            restorePinnedState(tempList);

            requireActivity().runOnUiThread(() -> {
                showLoading(false);
                isLoading = false;
                cachedArtistList = new ArrayList<>(tempList);
                lastCacheTime = System.currentTimeMillis();
                artistList.clear();
                artistList.addAll(tempList);
                saveArtistList(); // ← persist full list so MusicFragment can read it
                if (artistAdapter != null) artistAdapter.notifyDataSetChanged();
                updateUI();
            });
        });
    }

    private void updateUI() {
        if (binding == null) return;
        binding.emptyState.setVisibility(artistList.isEmpty() ? View.VISIBLE : View.GONE);
        binding.artistRecyclerView.setVisibility(artistList.isEmpty() ? View.GONE : View.VISIBLE);
    }

    private void showLoading(boolean show) {
        if (binding == null) return;
        if (show) {
            binding.loadingLayout.setVisibility(View.VISIBLE);
            binding.artistRecyclerView.setVisibility(View.GONE);
            binding.emptyState.setVisibility(View.GONE);
            if (binding.loadingCount != null) binding.loadingCount.setVisibility(View.GONE);
        } else {
            binding.loadingLayout.setVisibility(View.GONE);
        }
    }

    public void refreshData() {
        cachedArtistList = null; lastCacheTime = 0;
        loadArtistData();
    }

    public static void clearCache() { cachedArtistList = null; lastCacheTime = 0; }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        if (executorService != null && !executorService.isShutdown()) executorService.shutdown();
        binding = null;
    }
}
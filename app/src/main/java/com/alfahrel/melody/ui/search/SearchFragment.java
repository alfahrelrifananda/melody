package com.alfahrel.melody.ui.search;

import android.Manifest;
import android.content.ContentResolver;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.viewpager2.adapter.FragmentStateAdapter;
import androidx.viewpager2.widget.ViewPager2;

import com.alfahrel.melody.R;
import com.alfahrel.melody.databinding.FragmentSearchBinding;
import com.alfahrel.melody.ui.album.AlbumItem;
import com.alfahrel.melody.ui.artist.ArtistItem;
import com.alfahrel.melody.ui.music.MusicItem;
import com.google.android.material.tabs.TabLayout;
import com.google.android.material.tabs.TabLayoutMediator;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class SearchFragment extends Fragment {

    private FragmentSearchBinding binding;
    private SearchSongsFragment songsFragment;
    private SearchAlbumsFragment albumsFragment;
    private SearchArtistsFragment artistsFragment;

    private final List<MusicItem> allSongs   = new ArrayList<>();
    private final List<AlbumItem> allAlbums  = new ArrayList<>();
    private final List<ArtistItem> allArtists = new ArrayList<>();

    private ExecutorService executorService;
    private ActivityResultLauncher<String> permissionLauncher;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        binding = FragmentSearchBinding.inflate(inflater, container, false);
        executorService = Executors.newSingleThreadExecutor();

        // Setup permission launcher
        permissionLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestPermission(),
                granted -> {
                    if (granted) {
                        loadAllData(); // Reload data immediately after permission is granted
                    } else {
                        Toast.makeText(getContext(), "Permission denied.", Toast.LENGTH_SHORT).show();
                    }
                });

        setupViewPager();
        setupSearch();

        // Check permission and load data
        if (hasPermission()) {
            loadAllData();
        } else {
            requestPermission();
        }

        return binding.getRoot();
    }

    @Override
    public void onResume() {
        super.onResume();
        if (hasPermission()) {
            loadAllData(); // Reload data if permission is already granted
        }
    }

    private boolean hasPermission() {
        String permission = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                ? Manifest.permission.READ_MEDIA_AUDIO
                : Manifest.permission.READ_EXTERNAL_STORAGE;
        return ContextCompat.checkSelfPermission(requireContext(), permission) == PackageManager.PERMISSION_GRANTED;
    }

    private void requestPermission() {
        String permission = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                ? Manifest.permission.READ_MEDIA_AUDIO
                : Manifest.permission.READ_EXTERNAL_STORAGE;
        permissionLauncher.launch(permission);
    }

    private void setupViewPager() {
        songsFragment   = new SearchSongsFragment();
        albumsFragment  = new SearchAlbumsFragment();
        artistsFragment = new SearchArtistsFragment();

        binding.viewPager.setAdapter(new FragmentStateAdapter(this) {
            @NonNull @Override
            public Fragment createFragment(int position) {
                switch (position) {
                    case 1:  return albumsFragment;
                    case 2:  return artistsFragment;
                    default: return songsFragment;
                }
            }
            @Override public int getItemCount() { return 3; }
        });

        binding.tabLayout.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
            @Override public void onTabSelected(TabLayout.Tab tab) {
                binding.viewPager.setCurrentItem(tab.getPosition(), true);
            }
            @Override public void onTabUnselected(TabLayout.Tab tab) {}
            @Override public void onTabReselected(TabLayout.Tab tab) {}
        });

        binding.viewPager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
            @Override public void onPageSelected(int position) {
                binding.tabLayout.selectTab(binding.tabLayout.getTabAt(position));
                applySearch();
            }
        });
    }
    private void setupSearch() {
        binding.searchEditText.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
            @Override public void onTextChanged(CharSequence s, int st, int b, int c) { applySearch(); }
            @Override public void afterTextChanged(Editable s) {}
        });
    }

    private void loadAllData() {
        executorService.execute(() -> {
            // Load ALL songs from MediaStore
            List<MusicItem> songs = new ArrayList<>();
            try (Cursor c = requireContext().getContentResolver().query(
                    MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                    new String[]{
                            MediaStore.Audio.Media._ID,
                            MediaStore.Audio.Media.TITLE,
                            MediaStore.Audio.Media.ARTIST,
                            MediaStore.Audio.Media.ALBUM,
                            MediaStore.Audio.Media.DURATION,
                            MediaStore.Audio.Media.DATA,
                            MediaStore.Audio.Media.ALBUM_ID
                    },
                    MediaStore.Audio.Media.IS_MUSIC + " != 0",
                    null,
                    MediaStore.Audio.Media.TITLE + " ASC")) {
                if (c != null && c.moveToFirst()) {
                    int colId     = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID);
                    int colTitle  = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE);
                    int colArtist = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST);
                    int colAlbum  = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM);
                    int colDur    = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION);
                    int colPath   = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA);
                    int colArtId  = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID);
                    do {
                        songs.add(new MusicItem(
                                c.getLong(colId),
                                c.getString(colTitle),
                                c.getString(colArtist),
                                c.getString(colAlbum),
                                c.getLong(colDur),
                                c.getString(colPath),
                                Uri.parse("content://media/external/audio/albumart/" + c.getLong(colArtId))
                        ));
                    } while (c.moveToNext());
                }
            } catch (Exception e) { e.printStackTrace(); }

            // Load ALL albums from MediaStore
            List<AlbumItem> albums = new ArrayList<>();
            ContentResolver cr = requireContext().getContentResolver();
            LinkedHashMap<Long, AlbumItem> albumMap = new LinkedHashMap<>();
            try (Cursor c = cr.query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                    new String[]{
                            MediaStore.Audio.Media.ALBUM,
                            MediaStore.Audio.Media.ARTIST,
                            MediaStore.Audio.Media.ALBUM_ID
                    },
                    MediaStore.Audio.Media.IS_MUSIC + " != 0",
                    null,
                    MediaStore.Audio.Media.ALBUM + " ASC")) {
                if (c != null && c.moveToFirst()) {
                    int colId   = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID);
                    int colName = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM);
                    int colArt  = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST);
                    do {
                        long   id   = c.getLong(colId);
                        String name = c.getString(colName);
                        String art  = c.getString(colArt);
                        if (name == null || name.trim().isEmpty()) continue;
                        if (!albumMap.containsKey(id))
                            albumMap.put(id, new AlbumItem(id, name, art != null ? art : "Unknown",
                                    Uri.parse("content://media/external/audio/albumart/" + id), 1));
                        else {
                            AlbumItem e = albumMap.get(id);
                            if (e != null) e.setSongCount(e.getSongCount() + 1);
                        }
                    } while (c.moveToNext());
                }
            } catch (Exception e) { e.printStackTrace(); }
            albums = new ArrayList<>(albumMap.values());

            // Load ALL artists from MediaStore
            List<ArtistItem> artists = new ArrayList<>();
            LinkedHashMap<String, ArtistItem> artistMap = new LinkedHashMap<>();
            try (Cursor c = cr.query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                    new String[]{
                            MediaStore.Audio.Media.ARTIST,
                            MediaStore.Audio.Media.ALBUM_ID,
                            MediaStore.Audio.Media.DURATION
                    },
                    MediaStore.Audio.Media.IS_MUSIC + " != 0",
                    null,
                    MediaStore.Audio.Media.ARTIST + " ASC")) {
                if (c != null && c.moveToFirst()) {
                    int colName = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST);
                    int colAid  = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID);
                    int colDur  = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION);
                    do {
                        String name = c.getString(colName);
                        long aid    = c.getLong(colAid);
                        long dur    = c.getLong(colDur);
                        if (name == null || name.trim().isEmpty() || name.equals("<unknown>")) continue;
                        if (!artistMap.containsKey(name))
                            artistMap.put(name, new ArtistItem(name,
                                    Uri.parse("content://media/external/audio/albumart/" + aid), 1, dur));
                        else {
                            ArtistItem e = artistMap.get(name);
                            if (e != null) {
                                e.setSongCount(e.getSongCount() + 1);
                                e.addDuration(dur);
                            }
                        }
                    } while (c.moveToNext());
                }
            } catch (Exception e) { e.printStackTrace(); }
            artists = new ArrayList<>(artistMap.values());

            // Update UI on the main thread
            List<AlbumItem> finalAlbums = albums;
            List<ArtistItem> finalArtists = artists;
            requireActivity().runOnUiThread(() -> {
                allSongs.clear();   allSongs.addAll(songs);
                allAlbums.clear();  allAlbums.addAll(finalAlbums);
                allArtists.clear(); allArtists.addAll(finalArtists);

                // Update fragments with the loaded data
                if (songsFragment != null)   songsFragment.updateSongs(allSongs);
                if (albumsFragment != null)  albumsFragment.updateAlbums(allAlbums);
                if (artistsFragment != null) artistsFragment.updateArtists(allArtists);

                applySearch(); // Apply search to show all data
            });
        });
    }

    private void applySearch() {
        if (binding == null) return;
        String raw = binding.searchEditText.getText() != null
                ? binding.searchEditText.getText().toString().trim() : "";
        String q = raw.toLowerCase(Locale.getDefault());

        // Songs
        List<MusicItem> matchedSongs = new ArrayList<>();
        for (MusicItem s : allSongs)
            if (q.isEmpty()
                    || s.getTitle().toLowerCase(Locale.getDefault()).contains(q)
                    || s.getArtist().toLowerCase(Locale.getDefault()).contains(q))
                matchedSongs.add(s);

        // Albums
        List<AlbumItem> matchedAlbums = new ArrayList<>();
        for (AlbumItem a : allAlbums)
            if (q.isEmpty()
                    || a.getAlbumName().toLowerCase(Locale.getDefault()).contains(q)
                    || a.getArtistName().toLowerCase(Locale.getDefault()).contains(q))
                matchedAlbums.add(a);

        // Artists
        List<ArtistItem> matchedArtists = new ArrayList<>();
        for (ArtistItem a : allArtists)
            if (q.isEmpty()
                    || a.getArtistName().toLowerCase(Locale.getDefault()).contains(q))
                matchedArtists.add(a);

        if (songsFragment != null)   songsFragment.updateSongs(matchedSongs);
        if (albumsFragment != null)  albumsFragment.updateAlbums(matchedAlbums);
        if (artistsFragment != null) artistsFragment.updateArtists(matchedArtists);
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        if (executorService != null && !executorService.isShutdown()) executorService.shutdown();
        binding = null;
    }
}
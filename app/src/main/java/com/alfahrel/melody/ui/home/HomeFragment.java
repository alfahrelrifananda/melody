package com.alfahrel.melody.ui.home;

import static android.app.Activity.RESULT_OK;

import android.Manifest;
import android.app.RecoverableSecurityException;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.IntentSender;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Parcelable;
import android.provider.MediaStore;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.IntentSenderRequest;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.ConcatAdapter;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.alfahrel.melody.R;
import com.alfahrel.melody.databinding.FragmentHomeBinding;
import com.alfahrel.melody.service.MusicService;
import com.alfahrel.melody.ui.album.AlbumDetailActivity;
import com.alfahrel.melody.ui.album.AlbumFragment;
import com.alfahrel.melody.ui.album.AlbumItem;
import com.alfahrel.melody.ui.artist.ArtistDetailActivity;
import com.alfahrel.melody.ui.artist.ArtistFragment;
import com.alfahrel.melody.ui.artist.ArtistItem;
import com.alfahrel.melody.ui.collection.Collection;
import com.alfahrel.melody.ui.collection.CollectionDetailActivity;
import com.alfahrel.melody.ui.collection.CollectionManager;
import com.alfahrel.melody.ui.music.MusicItem;
import com.alfahrel.melody.ui.pages.all.AllAlbumsActivity;
import com.alfahrel.melody.ui.pages.all.AllArtistsActivity;
import com.alfahrel.melody.ui.pages.all.AllSongsActivity;
import com.alfahrel.melody.ui.pages.nowplaying.AddToCollectionAdapter;
import com.alfahrel.melody.ui.pages.nowplaying.NowPlayingActivity;
import com.alfahrel.melody.utils.GsonHelper;
import com.alfahrel.melody.utils.JumbotronHeaderAdapter;
import com.alfahrel.melody.utils.PinnedItem;
import com.alfahrel.melody.utils.PinnedStripHeaderAdapter;
import com.alfahrel.melody.utils.PlayCountManager;
import com.alfahrel.melody.utils.SongDetailBottomSheet;
import com.alfahrel.melody.utils.SongOptionsBottomSheet;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputEditText;
import com.google.gson.reflect.TypeToken;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class HomeFragment extends Fragment {

    public interface HomeNavigationListener {
        void navigateToTab(int position);
    }

    private HomeNavigationListener navListener;

    private static final String SONG_PREFS_NAME = "SongsPrefs";
    private static final String KEY_SONGS       = "songs_full";
    private static final int    SECTION_LIMIT   = 4;

    private FragmentHomeBinding      binding;
    private JumbotronHeaderAdapter   jumbotronAdapter;
    private PinnedStripHeaderAdapter pinnedStripHeaderAdapter;
    private HomeSongsAdapter         songsAdapter;
    private HomeAlbumsAdapter        albumsAdapter;
    private HomeArtistsAdapter       artistsAdapter;

    private final List<MusicItem>  recentSongs = new ArrayList<>();
    private final List<AlbumItem>  albums      = new ArrayList<>();
    private final List<ArtistItem> artists     = new ArrayList<>();

    private ExecutorService                             executorService;
    private ExecutorService                             loaderExecutor;
    private CollectionManager                           collectionManager;
    private PlayCountManager                            playCountManager;
    private ActivityResultLauncher<String>              permissionLauncher;
    private ActivityResultLauncher<IntentSenderRequest> deletePermissionLauncher;
    private MusicItem                                   pendingDeleteItem = null;

    private final BroadcastReceiver stripUpdateReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) { refreshPinnedStrip(); }
    };

    private final BroadcastReceiver miniPlayerReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (!"MINI_PLAYER_VISIBILITY_CHANGED".equals(intent.getAction())) return;
            boolean isVisible = intent.getBooleanExtra("is_visible", false);
            int height        = intent.getIntExtra("height", 0);
            if (binding == null) return;
            RecyclerView rv = binding.homeRecyclerView;
            int base = (int) (100 * getResources().getDisplayMetrics().density);
            rv.setPadding(rv.getPaddingLeft(), rv.getPaddingTop(),
                    rv.getPaddingRight(), isVisible ? base + height : base);
        }
    };

    @Override
    public void onAttach(@NonNull Context context) {
        super.onAttach(context);
        if (context instanceof HomeNavigationListener) navListener = (HomeNavigationListener) context;
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        permissionLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestPermission(),
                granted -> {
                    if (granted) {
                        loadAllSections();
                        refreshPinnedStrip();
                    } else {
                        Toast.makeText(getContext(), "Permission denied.", Toast.LENGTH_SHORT).show();
                    }
                });
        setupDeletePermissionLauncher();
    }

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             ViewGroup container, Bundle savedInstanceState) {
        binding           = FragmentHomeBinding.inflate(inflater, container, false);
        executorService   = Executors.newSingleThreadExecutor();
        loaderExecutor    = Executors.newSingleThreadExecutor();
        collectionManager = new CollectionManager(requireContext());
        playCountManager  = new PlayCountManager(requireContext());

        setupRecyclerView();
        checkPermissionAndLoad();
        refreshPinnedStrip();

        return binding.getRoot();
    }

    @Override
    public void onResume() {
        super.onResume();
        registerReceivers();
        refreshPinnedStrip();
        if (hasPermission()) loadAllSections();
    }

    @Override
    public void onPause() {
        super.onPause();
        if (getActivity() == null) return;
        try { getActivity().unregisterReceiver(miniPlayerReceiver); }  catch (Exception ignored) {}
        try { getActivity().unregisterReceiver(stripUpdateReceiver); } catch (Exception ignored) {}
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        if (executorService != null && !executorService.isShutdown()) executorService.shutdown();
        binding = null;
    }

    private void setupRecyclerView() {
        jumbotronAdapter = new JumbotronHeaderAdapter();

        pinnedStripHeaderAdapter = new PinnedStripHeaderAdapter(
                this::handlePinnedClick,
                this::handleUnpin
        );

        songsAdapter = new HomeSongsAdapter(
                recentSongs,
                this::playSong,
                this::showSongOptions,
                () -> startActivity(new Intent(requireContext(), AllSongsActivity.class))
        );

        albumsAdapter = new HomeAlbumsAdapter(
                albums,
                album -> {
                    Intent i = new Intent(requireContext(), AlbumDetailActivity.class);
                    i.putExtra("album_item", album);
                    startActivity(i);
                },
                () -> {
                    List<AlbumItem> allAlbums = loadAllAlbums();
                    Intent i = new Intent(requireContext(), AllAlbumsActivity.class);
                    i.putParcelableArrayListExtra("albums", new ArrayList<>(allAlbums));
                    startActivity(i);
                }
        );

        artistsAdapter = new HomeArtistsAdapter(
                artists,
                artist -> {
                    Intent i = new Intent(requireContext(), ArtistDetailActivity.class);
                    i.putExtra("artist_item", artist);
                    startActivity(i);
                },
                () -> {
                    List<ArtistItem> allArtists = loadAllArtists();
                    Intent i = new Intent(requireContext(), AllArtistsActivity.class);
                    i.putParcelableArrayListExtra("artists", new ArrayList<>(allArtists));
                    startActivity(i);
                }
        );

        binding.homeRecyclerView.setLayoutManager(new LinearLayoutManager(getContext()));
        binding.homeRecyclerView.setAdapter(new ConcatAdapter(
//                jumbotronAdapter,
                pinnedStripHeaderAdapter,
                songsAdapter,
                albumsAdapter,
                artistsAdapter));
        binding.homeRecyclerView.setItemViewCacheSize(20);
    }

    private void setupDeletePermissionLauncher() {
        deletePermissionLauncher = registerForActivityResult(
                new ActivityResultContracts.StartIntentSenderForResult(),
                result -> {
                    if (result.getResultCode() == RESULT_OK && pendingDeleteItem != null) {
                        deleteSongAfterPermission(pendingDeleteItem);
                    } else {
                        Toast.makeText(requireContext(), "Permission denied to delete file", Toast.LENGTH_SHORT).show();
                    }
                    pendingDeleteItem = null;
                });
    }

    private void loadAllSections() {
        if (!hasPermission()) return;
        executorService.execute(() -> {
            List<MusicItem>  songs = loadRecentSongs();
            List<AlbumItem>  albs  = loadAlbums();
            List<ArtistItem> arts  = loadArtists();
            if (getActivity() == null) return;
            requireActivity().runOnUiThread(() -> {
                if (binding == null) return;
                recentSongs.clear(); recentSongs.addAll(songs); songsAdapter.notifyDataSetChanged();
                albums.clear();      albums.addAll(albs);       albumsAdapter.updateData();
                artists.clear();     artists.addAll(arts);      artistsAdapter.updateData();
                binding.homeRecyclerView.getAdapter().notifyDataSetChanged();
            });
        });
    }

    private List<MusicItem> loadRecentSongs() {
        List<MusicItem> list = new ArrayList<>();
        if (getContext() == null) return list;
        ContentResolver cr = requireContext().getContentResolver();
        String[] proj = {
                MediaStore.Audio.Media._ID,
                MediaStore.Audio.Media.TITLE,
                MediaStore.Audio.Media.ARTIST,
                MediaStore.Audio.Media.ALBUM,
                MediaStore.Audio.Media.DURATION,
                MediaStore.Audio.Media.DATA,
                MediaStore.Audio.Media.ALBUM_ID
        };
        try (Cursor c = cr.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, proj,
                MediaStore.Audio.Media.IS_MUSIC + " != 0",
                null,
                MediaStore.Audio.Media.DATE_ADDED + " DESC")) {
            if (c != null && c.moveToFirst()) {
                int colId     = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID);
                int colTitle  = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE);
                int colArtist = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST);
                int colAlbum  = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM);
                int colDur    = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION);
                int colPath   = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA);
                int colArtId  = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID);
                do {
                    list.add(new MusicItem(
                            c.getLong(colId),
                            c.getString(colTitle),
                            c.getString(colArtist),
                            c.getString(colAlbum),
                            c.getLong(colDur),
                            c.getString(colPath),
                            Uri.parse("content://media/external/audio/albumart/" + c.getLong(colArtId))
                    ));
                } while (c.moveToNext() && list.size() < SECTION_LIMIT);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return list;
    }

    private List<AlbumItem> loadAlbums() {
        List<AlbumItem> list = new ArrayList<>();
        if (getContext() == null) return list;
        ContentResolver cr = requireContext().getContentResolver();
        java.util.LinkedHashMap<Long, AlbumItem> map = new java.util.LinkedHashMap<>();
        try (Cursor c = cr.query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                new String[]{ MediaStore.Audio.Media.ALBUM, MediaStore.Audio.Media.ARTIST, MediaStore.Audio.Media.ALBUM_ID },
                MediaStore.Audio.Media.IS_MUSIC + " != 0", null, MediaStore.Audio.Media.ALBUM + " ASC")) {
            if (c != null && c.moveToFirst()) {
                int colId   = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID);
                int colName = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM);
                int colArt  = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST);
                do {
                    long   id   = c.getLong(colId);
                    String name = c.getString(colName);
                    String art  = c.getString(colArt);
                    if (name == null || name.trim().isEmpty()) continue;
                    if (!map.containsKey(id))
                        map.put(id, new AlbumItem(id, name, art != null ? art : "Unknown",
                                Uri.parse("content://media/external/audio/albumart/" + id), 1));
                    else {
                        AlbumItem e = map.get(id);
                        if (e != null) e.setSongCount(e.getSongCount() + 1);
                    }
                } while (c.moveToNext());
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return new ArrayList<>(map.values());
    }

    private List<ArtistItem> loadArtists() {
        List<ArtistItem> list = new ArrayList<>();
        if (getContext() == null) return list;
        ContentResolver cr = requireContext().getContentResolver();
        java.util.LinkedHashMap<String, ArtistItem> map = new java.util.LinkedHashMap<>();
        try (Cursor c = cr.query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                new String[]{ MediaStore.Audio.Media.ARTIST, MediaStore.Audio.Media.ALBUM_ID, MediaStore.Audio.Media.DURATION },
                MediaStore.Audio.Media.IS_MUSIC + " != 0", null, MediaStore.Audio.Media.ARTIST + " ASC")) {
            if (c != null && c.moveToFirst()) {
                int colName = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST);
                int colAid  = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID);
                int colDur  = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION);
                do {
                    String name = c.getString(colName);
                    long aid    = c.getLong(colAid);
                    long dur    = c.getLong(colDur);
                    if (name == null || name.trim().isEmpty() || name.equals("<unknown>")) continue;
                    if (!map.containsKey(name))
                        map.put(name, new ArtistItem(name,
                                Uri.parse("content://media/external/audio/albumart/" + aid), 1, dur));
                    else {
                        ArtistItem e = map.get(name);
                        if (e != null) { e.setSongCount(e.getSongCount() + 1); e.addDuration(dur); }
                    }
                } while (c.moveToNext());
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return new ArrayList<>(map.values()).subList(0, Math.min(map.size(), SECTION_LIMIT));
    }

    private List<AlbumItem> loadAllAlbums() {
        List<AlbumItem> list = new ArrayList<>();
        if (getContext() == null) return list;
        ContentResolver cr = requireContext().getContentResolver();
        java.util.LinkedHashMap<Long, AlbumItem> map = new java.util.LinkedHashMap<>();
        try (Cursor c = cr.query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                new String[]{ MediaStore.Audio.Media.ALBUM, MediaStore.Audio.Media.ARTIST, MediaStore.Audio.Media.ALBUM_ID },
                MediaStore.Audio.Media.IS_MUSIC + " != 0", null, MediaStore.Audio.Media.ALBUM + " ASC")) {
            if (c != null && c.moveToFirst()) {
                int colId   = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID);
                int colName = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM);
                int colArt  = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST);
                do {
                    long   id   = c.getLong(colId);
                    String name = c.getString(colName);
                    String art  = c.getString(colArt);
                    if (name == null || name.trim().isEmpty()) continue;
                    if (!map.containsKey(id))
                        map.put(id, new AlbumItem(id, name, art != null ? art : "Unknown",
                                Uri.parse("content://media/external/audio/albumart/" + id), 1));
                    else {
                        AlbumItem e = map.get(id);
                        if (e != null) e.setSongCount(e.getSongCount() + 1);
                    }
                } while (c.moveToNext());
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return new ArrayList<>(map.values());
    }

    private List<ArtistItem> loadAllArtists() {
        List<ArtistItem> list = new ArrayList<>();
        if (getContext() == null) return list;
        ContentResolver cr = requireContext().getContentResolver();
        java.util.LinkedHashMap<String, ArtistItem> map = new java.util.LinkedHashMap<>();
        try (Cursor c = cr.query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                new String[]{ MediaStore.Audio.Media.ARTIST, MediaStore.Audio.Media.ALBUM_ID, MediaStore.Audio.Media.DURATION },
                MediaStore.Audio.Media.IS_MUSIC + " != 0", null, MediaStore.Audio.Media.ARTIST + " ASC")) {
            if (c != null && c.moveToFirst()) {
                int colName = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST);
                int colAid  = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID);
                int colDur  = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION);
                do {
                    String name = c.getString(colName);
                    long aid    = c.getLong(colAid);
                    long dur    = c.getLong(colDur);
                    if (name == null || name.trim().isEmpty() || name.equals("<unknown>")) continue;
                    if (!map.containsKey(name))
                        map.put(name, new ArtistItem(name,
                                Uri.parse("content://media/external/audio/albumart/" + aid), 1, dur));
                    else {
                        ArtistItem e = map.get(name);
                        if (e != null) { e.setSongCount(e.getSongCount() + 1); e.addDuration(dur); }
                    }
                } while (c.moveToNext());
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return new ArrayList<>(map.values());
    }

    private void refreshPinnedStrip() {
        if (getContext() == null || pinnedStripHeaderAdapter == null) return;
        executorService.execute(() -> {
            List<PinnedItem> merged = new ArrayList<>();

            java.util.Set<Long>   validSongIds     = new java.util.HashSet<>();
            java.util.Set<Long>   validAlbumIds    = new java.util.HashSet<>();
            java.util.Set<String> validArtistNames = new java.util.HashSet<>();
            try {
                ContentResolver cr = requireContext().getContentResolver();
                try (Cursor c = cr.query(
                        MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                        new String[]{ MediaStore.Audio.Media._ID, MediaStore.Audio.Media.ALBUM_ID, MediaStore.Audio.Media.ARTIST },
                        MediaStore.Audio.Media.IS_MUSIC + " != 0", null, null)) {
                    if (c != null && c.moveToFirst()) {
                        int colId     = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID);
                        int colAlbId  = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID);
                        int colArtist = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST);
                        do {
                            validSongIds.add(c.getLong(colId));
                            validAlbumIds.add(c.getLong(colAlbId));
                            String artist = c.getString(colArtist);
                            if (artist != null && !artist.equals("<unknown>")) validArtistNames.add(artist);
                        } while (c.moveToNext());
                    }
                }
            } catch (Exception ignored) {}

            try {
                SharedPreferences cp = requireContext().getSharedPreferences("CollectionsPrefs", Context.MODE_PRIVATE);
                String json = cp.getString("collections", null);
                if (json != null) {
                    List<Collection> cols = GsonHelper.get().fromJson(json, new TypeToken<List<Collection>>(){}.getType());
                    if (cols != null) for (Collection col : cols) if (col.isPinned()) {
                        if (col.getMusicIds() != null) {
                            List<Long> validIds = new ArrayList<>();
                            for (Long id : col.getMusicIds())
                                if (validSongIds.contains(id)) validIds.add(id);
                            col.setMusicIds(validIds);
                        }
                        int cnt = col.getMusicIds() != null ? col.getMusicIds().size() : 0;
                        merged.add(new PinnedItem(PinnedItem.Type.COLLECTION, String.valueOf(col.getId()),
                                col.getName(), cnt + (cnt == 1 ? " song" : " songs"),
                                col.getCoverImageUri() != null ? Uri.parse(col.getCoverImageUri()) : null, col));
                    }
                }
            } catch (Exception ignored) {}

            try {
                SharedPreferences ap = requireContext().getSharedPreferences(AlbumFragment.PREFS_NAME, Context.MODE_PRIVATE);
                String json = ap.getString(AlbumFragment.KEY_ALBUMS, null);
                if (json != null) {
                    List<AlbumItem> albs = GsonHelper.get().fromJson(json, new TypeToken<List<AlbumItem>>(){}.getType());
                    if (albs != null) {
                        boolean changed = false;
                        for (AlbumItem a : albs) {
                            if (a.isPinned()) {
                                if (validAlbumIds.contains(a.getAlbumId())) {
                                    merged.add(new PinnedItem(PinnedItem.Type.ALBUM, String.valueOf(a.getAlbumId()),
                                            a.getAlbumName(), a.getArtistName(), a.getAlbumArtUri(), a));
                                } else {
                                    a.setPinned(false);
                                    changed = true;
                                }
                            }
                        }
                        if (changed) ap.edit().putString(AlbumFragment.KEY_ALBUMS, GsonHelper.get().toJson(albs)).apply();
                    }
                }
            } catch (Exception ignored) {}

            try {
                SharedPreferences ap = requireContext().getSharedPreferences(ArtistFragment.PREFS_NAME, Context.MODE_PRIVATE);
                String json = ap.getString(ArtistFragment.KEY_ARTISTS, null);
                if (json != null) {
                    List<ArtistItem> arts = GsonHelper.get().fromJson(json, new TypeToken<List<ArtistItem>>(){}.getType());
                    if (arts != null) {
                        boolean changed = false;
                        for (ArtistItem a : arts) {
                            if (a.isPinned()) {
                                if (validArtistNames.contains(a.getArtistName())) {
                                    merged.add(new PinnedItem(PinnedItem.Type.ARTIST, a.getArtistName(),
                                            a.getArtistName(), a.getFormattedSongCount(), a.getArtistImageUri(), a));
                                } else {
                                    a.setPinned(false);
                                    changed = true;
                                }
                            }
                        }
                        if (changed) ap.edit().putString(ArtistFragment.KEY_ARTISTS, GsonHelper.get().toJson(arts)).apply();
                    }
                }
            } catch (Exception ignored) {}

            try {
                SharedPreferences sp = requireContext().getSharedPreferences(SONG_PREFS_NAME, Context.MODE_PRIVATE);
                String json = sp.getString(KEY_SONGS, null);
                if (json != null) {
                    Type t = new TypeToken<List<MusicItem>>(){}.getType();
                    List<MusicItem> songs = GsonHelper.get().fromJson(json, t);
                    if (songs != null) {
                        boolean changed = false;
                        for (MusicItem s : songs) {
                            if (s.isPinned()) {
                                if (validSongIds.contains(s.getId())) {
                                    merged.add(new PinnedItem(PinnedItem.Type.SONG, String.valueOf(s.getId()),
                                            s.getTitle(), s.getArtist(), s.getAlbumArtUri(), s));
                                } else {
                                    s.setPinned(false);
                                    changed = true;
                                }
                            }
                        }
                        if (changed) sp.edit().putString(KEY_SONGS, GsonHelper.get().toJson(songs)).apply();
                    }
                }
            } catch (Exception ignored) {}

            final List<PinnedItem> finalList = merged;
            if (getActivity() != null)
                requireActivity().runOnUiThread(() -> {
                    if (pinnedStripHeaderAdapter != null) pinnedStripHeaderAdapter.updateItems(finalList);
                });
        });
    }

    private void playSong(MusicItem song) {
        if (getContext() == null) return;
        playCountManager.increment(song.getId());

        ArrayList<MusicItem> playlist = new ArrayList<>(recentSongs);
        int idx = 0;
        for (int i = 0; i < playlist.size(); i++)
            if (playlist.get(i).getId() == song.getId()) { idx = i; break; }

        Intent pi = new Intent(getContext(), MusicService.class);
        pi.setAction(MusicService.ACTION_SET_PLAYLIST);
        pi.putParcelableArrayListExtra("playlist", playlist);
        pi.putExtra("start_index", idx);
        getContext().startService(pi);

        Intent play = new Intent(getContext(), MusicService.class);
        play.setAction(MusicService.ACTION_PLAY);
        play.putExtra("music_item", song);
        getContext().startService(play);

        new android.os.Handler().postDelayed(() -> {
            if (getContext() == null) return;
            Intent intent = new Intent(getContext(), NowPlayingActivity.class);
            intent.putExtra("music_item", (Parcelable) song);
            startActivity(intent);
            if (getActivity() != null)
                getActivity().overridePendingTransition(R.anim.slide_in_bottom, R.anim.slide_out_top);
        }, 200);
    }

    private void showSongOptions(MusicItem song) {
        SongOptionsBottomSheet sheet = SongOptionsBottomSheet.newInstance(song);
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

    private void showDeleteConfirmationDialog(MusicItem song) {
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle("Delete song")
                .setMessage("Are you sure you want to delete \"" + song.getTitle() + "\"?")
                .setPositiveButton("Delete", (d, w) -> deleteSong(song))
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void deleteSong(MusicItem song) {
        if (song == null) return;
        try {
            Uri uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, song.getId());
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                try {
                    int rows = requireContext().getContentResolver().delete(uri, null, null);
                    if (rows > 0) onSongDeleteSuccess(song);
                    else Toast.makeText(requireContext(), "Failed to delete song", Toast.LENGTH_SHORT).show();
                } catch (SecurityException se) {
                    if (se instanceof RecoverableSecurityException) {
                        pendingDeleteItem = song;
                        IntentSender intentSender = ((RecoverableSecurityException) se)
                                .getUserAction().getActionIntent().getIntentSender();
                        deletePermissionLauncher.launch(new IntentSenderRequest.Builder(intentSender).build());
                    } else {
                        Toast.makeText(requireContext(), "Permission denied.", Toast.LENGTH_SHORT).show();
                    }
                }
            } else {
                int rows = requireContext().getContentResolver().delete(uri, null, null);
                if (rows > 0) { new java.io.File(song.getPath()).delete(); onSongDeleteSuccess(song); }
                else Toast.makeText(requireContext(), "Failed to delete song", Toast.LENGTH_SHORT).show();
            }
        } catch (Exception e) {
            Toast.makeText(requireContext(), "Error: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private void deleteSongAfterPermission(MusicItem song) {
        try {
            Uri uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, song.getId());
            int rows = requireContext().getContentResolver().delete(uri, null, null);
            if (rows > 0) onSongDeleteSuccess(song);
            else Toast.makeText(requireContext(), "Failed to delete song", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(requireContext(), "Error: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private void onSongDeleteSuccess(MusicItem song) {
        Toast.makeText(requireContext(), "Song deleted", Toast.LENGTH_SHORT).show();
        recentSongs.remove(song);
        songsAdapter.notifyDataSetChanged();
        Intent intent = new Intent("SONG_DELETED");
        intent.putExtra("song_id", song.getId());
        intent.setPackage(requireContext().getPackageName());
        requireContext().sendBroadcast(intent);
    }

    private void toggleSongPin(MusicItem song) {
        executorService.execute(() -> {
            boolean nowPinned = !song.isPinned();
            song.setPinned(nowPinned);
            saveSongList();
            requireActivity().runOnUiThread(() -> {
                songsAdapter.notifyDataSetChanged();
                Toast.makeText(requireContext(), nowPinned ? "Pinned to Home" : "Unpinned from Home", Toast.LENGTH_SHORT).show();
                broadcast("com.alfahrel.melody.SONG_PIN_CHANGED");
            });
        });
    }

    private void saveSongList() {
        SharedPreferences sp = requireContext().getSharedPreferences(SONG_PREFS_NAME, Context.MODE_PRIVATE);
        sp.edit().putString(KEY_SONGS, GsonHelper.get().toJson(recentSongs)).apply();
    }

    private void showAddToCollectionBottomSheet(MusicItem song) {
        if (song == null) return;
        List<Collection> collections = collectionManager.getAllCollections();
        BottomSheetDialog dialog = new BottomSheetDialog(requireContext());
        View view = LayoutInflater.from(requireContext()).inflate(R.layout.bottom_sheet_add_to_collection, null);
        RecyclerView rv        = view.findViewById(R.id.collectionsRecyclerView);
        TextView emptyText     = view.findViewById(R.id.emptyCollectionsText);
        View createBtn         = view.findViewById(R.id.createNewCollectionButton);
        rv.setLayoutManager(new LinearLayoutManager(requireContext()));
        if (collections.isEmpty()) {
            rv.setVisibility(View.GONE);
            emptyText.setVisibility(View.VISIBLE);
        } else {
            rv.setVisibility(View.VISIBLE);
            emptyText.setVisibility(View.GONE);
            AddToCollectionAdapter colAdapter = new AddToCollectionAdapter(
                    collections, song.getId(), collectionManager,
                    col -> {
                        boolean added = collectionManager.addSongToCollection(col.getId(), song.getId());
                        if (added) {
                            Toast.makeText(requireContext(), "Added to " + col.getName(), Toast.LENGTH_SHORT).show();
                            broadcast("com.alfahrel.melody.SONG_ADDED_TO_COLLECTION");
                            dialog.dismiss();
                        } else {
                            Toast.makeText(requireContext(), "Song already in " + col.getName(), Toast.LENGTH_SHORT).show();
                        }
                    });
            rv.setAdapter(colAdapter);
        }
        createBtn.setOnClickListener(v -> { dialog.dismiss(); showCreateCollectionDialog(song); });
        dialog.setContentView(view);
        dialog.show();
    }

    private void showCreateCollectionDialog(MusicItem song) {
        View dialogView = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_add_collection, null);
        TextInputEditText editName = dialogView.findViewById(R.id.editTextCollectionName);
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle("New collection")
                .setView(dialogView)
                .setPositiveButton("Create", (d, w) -> {
                    String name = editName.getText() != null ? editName.getText().toString().trim() : "";
                    if (!name.isEmpty()) createCollectionAndAddSong(name, song);
                    else Toast.makeText(requireContext(), "Collection name cannot be empty", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void createCollectionAndAddSong(String name, MusicItem song) {
        for (Collection c : collectionManager.getAllCollections()) {
            if (c.getName().equalsIgnoreCase(name)) {
                Toast.makeText(requireContext(), "Collection already exists", Toast.LENGTH_SHORT).show();
                return;
            }
        }
        Collection col = collectionManager.createCollection(name);
        collectionManager.addSongToCollection(col.getId(), song.getId());
        Toast.makeText(requireContext(), "Created \"" + name + "\" and added song", Toast.LENGTH_SHORT).show();
        broadcast("com.alfahrel.melody.COLLECTION_CREATED");
    }

    private void handlePinnedClick(PinnedItem item) {
        switch (item.getType()) {
            case COLLECTION:
                Intent ci = new Intent(requireContext(), CollectionDetailActivity.class);
                ci.putExtra("collection", (Collection) item.getOriginal());
                startActivity(ci);
                break;
            case ALBUM:
                Intent ai = new Intent(requireContext(), AlbumDetailActivity.class);
                ai.putExtra("album_item", (AlbumItem) item.getOriginal());
                startActivity(ai);
                break;
            case ARTIST:
                Intent ari = new Intent(requireContext(), ArtistDetailActivity.class);
                ari.putExtra("artist_item", (ArtistItem) item.getOriginal());
                startActivity(ari);
                break;
            case SONG:
                playSong((MusicItem) item.getOriginal());
                break;
        }
    }

    private void handleUnpin(PinnedItem item) {
        switch (item.getType()) {
            case SONG: {
                MusicItem song = (MusicItem) item.getOriginal();
                SharedPreferences sp = requireContext().getSharedPreferences(SONG_PREFS_NAME, Context.MODE_PRIVATE);
                String json = sp.getString(KEY_SONGS, null);
                if (json != null) {
                    Type type = new TypeToken<List<MusicItem>>(){}.getType();
                    List<MusicItem> songs = GsonHelper.get().fromJson(json, type);
                    if (songs != null) {
                        for (MusicItem s : songs)
                            if (s.getId() == song.getId()) { s.setPinned(false); break; }
                        sp.edit().putString(KEY_SONGS, GsonHelper.get().toJson(songs)).apply();
                    }
                }
                broadcast("com.alfahrel.melody.SONG_PIN_CHANGED");
                break;
            }
            case ALBUM: {
                AlbumItem album = (AlbumItem) item.getOriginal();
                SharedPreferences ap = requireContext().getSharedPreferences(AlbumFragment.PREFS_NAME, Context.MODE_PRIVATE);
                String json = ap.getString(AlbumFragment.KEY_ALBUMS, null);
                if (json != null) {
                    Type t = new TypeToken<List<AlbumItem>>(){}.getType();
                    List<AlbumItem> list = GsonHelper.get().fromJson(json, t);
                    if (list != null) {
                        for (AlbumItem a : list)
                            if (a.getAlbumId() == album.getAlbumId()) { a.setPinned(false); break; }
                        ap.edit().putString(AlbumFragment.KEY_ALBUMS, GsonHelper.get().toJson(list)).apply();
                    }
                }
                broadcast("com.alfahrel.melody.ALBUM_CHANGED");
                break;
            }
            case ARTIST: {
                ArtistItem artist = (ArtistItem) item.getOriginal();
                SharedPreferences ap = requireContext().getSharedPreferences(ArtistFragment.PREFS_NAME, Context.MODE_PRIVATE);
                String json = ap.getString(ArtistFragment.KEY_ARTISTS, null);
                if (json != null) {
                    Type t = new TypeToken<List<ArtistItem>>(){}.getType();
                    List<ArtistItem> list = GsonHelper.get().fromJson(json, t);
                    if (list != null) {
                        for (ArtistItem a : list)
                            if (a.getArtistName().equals(artist.getArtistName())) { a.setPinned(false); break; }
                        ap.edit().putString(ArtistFragment.KEY_ARTISTS, GsonHelper.get().toJson(list)).apply();
                    }
                }
                broadcast("com.alfahrel.melody.ARTIST_CHANGED");
                break;
            }
            case COLLECTION: {
                Collection col = (Collection) item.getOriginal();
                List<Collection> all = collectionManager.getAllCollections();
                for (Collection c : all) if (c.getId() == col.getId()) { c.setPinned(false); break; }
                collectionManager.saveCollections(all);
                broadcast("com.alfahrel.melody.COLLECTION_CHANGED");
                break;
            }
        }
        Toast.makeText(requireContext(), "\"" + item.getName() + "\" unpinned from Home", Toast.LENGTH_SHORT).show();
        refreshPinnedStrip();
    }

    private boolean hasPermission() {
        String p = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                ? Manifest.permission.READ_MEDIA_AUDIO : Manifest.permission.READ_EXTERNAL_STORAGE;
        return getContext() != null &&
                ContextCompat.checkSelfPermission(requireContext(), p) == PackageManager.PERMISSION_GRANTED;
    }

    private void checkPermissionAndLoad() {
        if (hasPermission()) {
            loadAllSections();
        } else {
            String permission = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                    ? Manifest.permission.READ_MEDIA_AUDIO
                    : Manifest.permission.READ_EXTERNAL_STORAGE;
            permissionLauncher.launch(permission);
        }
    }

    private void broadcast(String action) {
        if (getContext() == null) return;
        Intent i = new Intent(action);
        i.setPackage(requireContext().getPackageName());
        requireContext().sendBroadcast(i);
    }

    private void registerReceivers() {
        if (getActivity() == null) return;
        IntentFilter miniFilter = new IntentFilter("MINI_PLAYER_VISIBILITY_CHANGED");
        IntentFilter stripFilter = new IntentFilter();
        stripFilter.addAction("com.alfahrel.melody.ALBUM_CHANGED");
        stripFilter.addAction("com.alfahrel.melody.ARTIST_CHANGED");
        stripFilter.addAction("com.alfahrel.melody.COLLECTION_CHANGED");
        stripFilter.addAction("com.alfahrel.melody.SONG_PIN_CHANGED");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getActivity().registerReceiver(miniPlayerReceiver, miniFilter, Context.RECEIVER_NOT_EXPORTED);
            getActivity().registerReceiver(stripUpdateReceiver, stripFilter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            getActivity().registerReceiver(miniPlayerReceiver, miniFilter);
            getActivity().registerReceiver(stripUpdateReceiver, stripFilter);
        }
    }
}
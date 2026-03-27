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
import android.content.SharedPreferences;
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

import com.alfahrel.melody.utils.GsonHelper;
import com.bumptech.glide.Glide;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputEditText;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import com.alfahrel.melody.R;
import com.alfahrel.melody.databinding.SearchFragmentBinding;
import com.alfahrel.melody.service.MusicService;
import com.alfahrel.melody.ui.music.MusicAdapter;
import com.alfahrel.melody.ui.music.MusicItem;
import com.alfahrel.melody.ui.pages.nowplaying.NowPlayingActivity;
import com.alfahrel.melody.ui.pages.nowplaying.AddToCollectionAdapter;
import com.alfahrel.melody.ui.collection.Collection;
import com.alfahrel.melody.ui.collection.CollectionManager;
import com.alfahrel.melody.utils.SongDetailBottomSheet;
import com.alfahrel.melody.utils.SongOptionsBottomSheet;

import java.io.File;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static com.alfahrel.melody.ui.collection.CollectionFragment.ACTION_COLLECTION_CREATED;
import static com.alfahrel.melody.ui.collection.CollectionFragment.ACTION_SONG_ADDED_TO_COLLECTION;

public class SearchFragment extends Fragment {

    private static final String TAG = "SearchFragment";

    // ── Song pin persistence (mirrors MusicFragment) ──────────────────────────
    private static final String SONG_PREFS_NAME = "SongsPrefs";
    private static final String KEY_SONGS       = "songs_full";
    private Gson gson;
    private SharedPreferences songPrefs;

    private SearchFragmentBinding binding;
    private MusicAdapter searchAdapter;
    private List<MusicItem> allMusicList   = new ArrayList<>();
    private List<MusicItem> searchResults  = new ArrayList<>();
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
            if (isFragmentDestroyed) return;
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
                            updateMiniPlayerState(intent.getBooleanExtra("is_playing", false));
                            break;
                        case MusicService.ACTION_HIDE_MINI_PLAYER:
                            hideMiniPlayer();
                            break;
                        case "MINI_PLAYER_VISIBILITY_CHANGED":
                            adjustRecyclerViewPadding(
                                    intent.getBooleanExtra("is_visible", false),
                                    intent.getIntExtra("height", 0));
                            break;
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "Error handling broadcast: " + e.getMessage(), e);
            }
        }
    };

    // ── Fragment lifecycle ────────────────────────────────────────────────────

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        binding = SearchFragmentBinding.inflate(inflater, container, false);
        View root = binding.getRoot();

        executorService   = Executors.newSingleThreadExecutor();
        searchHandler     = new Handler(Looper.getMainLooper());
        collectionManager = new CollectionManager(requireContext());
        gson              = GsonHelper.get();
        songPrefs         = requireContext().getSharedPreferences(SONG_PREFS_NAME, Context.MODE_PRIVATE);

        setupDeletePermissionLauncher();
        initializeMiniPlayer();
        setupRecyclerView();
        setupSearchView();
        loadAllMusic();

        return root;
    }

    // ── Song pin ──────────────────────────────────────────────────────────────

    private void toggleSongPin(MusicItem song) {
        executorService.execute(() -> {
            // Update pin state in allMusicList (search results share the same instances)
            boolean nowPinned = false;
            for (MusicItem s : allMusicList) {
                if (s.getId() == song.getId()) {
                    nowPinned = !s.isPinned();
                    s.setPinned(nowPinned);
                    break;
                }
            }
            // Mirror into searchResults too (same object, but be safe)
            for (MusicItem s : searchResults) {
                if (s.getId() == song.getId()) {
                    s.setPinned(nowPinned);
                    break;
                }
            }
            saveSongPinnedState();
            final boolean pinned = nowPinned;
            if (getActivity() != null) {
                getActivity().runOnUiThread(() -> {
                    if (searchAdapter != null) searchAdapter.notifyDataSetChanged();
                    Toast.makeText(requireContext(),
                            pinned ? "Pinned to Home" : "Unpinned from Home",
                            Toast.LENGTH_SHORT).show();
                });
            }
        });
    }

    private void saveSongPinnedState() {
        // Merge pin states from allMusicList back into the persisted full list
        String json = songPrefs.getString(KEY_SONGS, null);
        List<MusicItem> all = new ArrayList<>();
        if (json != null) {
            Type type = new TypeToken<List<MusicItem>>(){}.getType();
            List<MusicItem> saved = gson.fromJson(json, type);
            if (saved != null) all = saved;
        }
        Map<Long, Boolean> pinMap = new HashMap<>();
        for (MusicItem s : allMusicList) pinMap.put(s.getId(), s.isPinned());
        for (MusicItem s : all) {
            if (pinMap.containsKey(s.getId())) s.setPinned(pinMap.get(s.getId()));
        }
        songPrefs.edit().putString(KEY_SONGS, gson.toJson(all)).apply();
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

    // ── Delete permission launcher ────────────────────────────────────────────

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
                        Toast.makeText(requireContext(), "Permission denied to delete file", Toast.LENGTH_SHORT).show();
                        pendingDeleteItem = null;
                    }
                });
    }

    // ── Mini player ───────────────────────────────────────────────────────────

    private void initializeMiniPlayer() {
        try {
            miniPlayerContainer = binding.miniPlayerContainer;
            miniAlbumArt        = binding.miniAlbumArt;
            miniSongTitle       = binding.miniSongTitle;
            miniArtistName      = binding.miniArtistName;
            miniPlayPauseButton = binding.miniPlayPauseButton;
            miniNextButton      = binding.miniNextButton;
            miniCloseButton     = binding.miniCloseButton;

            miniPlayerContainer.setOnClickListener(v -> { if (!isFragmentDestroyed) openNowPlayingActivity(); });
            miniPlayPauseButton.setOnClickListener(v -> sendServiceAction(MusicService.ACTION_TOGGLE_PLAY_PAUSE));
            miniNextButton.setOnClickListener(v -> sendServiceAction(MusicService.ACTION_NEXT));
            miniCloseButton.setOnClickListener(v -> { sendServiceAction(MusicService.ACTION_STOP); hideMiniPlayer(); });

        } catch (Exception e) {
            Log.e(TAG, "Error initializing mini player", e);
        }
    }

    private void sendServiceAction(String action) {
        if (isFragmentDestroyed || getActivity() == null) return;
        try {
            Intent i = new Intent(getActivity(), MusicService.class);
            i.setAction(action);
            getActivity().startService(i);
        } catch (Exception e) { Log.e(TAG, "Service error: " + action, e); }
    }

    // ── RecyclerView ──────────────────────────────────────────────────────────

    private void setupRecyclerView() {
        searchAdapter = new MusicAdapter(searchResults, getContext());
        binding.searchRecyclerView.setLayoutManager(new LinearLayoutManager(getContext()));
        binding.searchRecyclerView.setAdapter(searchAdapter);

        searchAdapter.setOnMusicItemClickListener(new MusicAdapter.OnMusicItemClickListener() {
            @Override public void onMusicItemClick(MusicItem musicItem) {
                startMusicServiceAndOpenNowPlaying(musicItem);
            }
            @Override public void onOptionClick(MusicItem musicItem) {
                showSongOptionsSheet(musicItem);
            }
        });

        // No long-press listener – vert icon handles everything.
    }

    // ── Song options bottom sheet ─────────────────────────────────────────────

    private void showSongOptionsSheet(MusicItem musicItem) {
        if (getContext() == null || musicItem == null) return;
        SongOptionsBottomSheet sheet = SongOptionsBottomSheet.newInstance(musicItem);
        sheet.setListener(new SongOptionsBottomSheet.SongOptionsListener() {
            @Override public void onAddToCollection(MusicItem item) {
                showAddToCollectionBottomSheet(item);
            }
            @Override public void onViewDetails(MusicItem item) {
                SongDetailBottomSheet.newInstance(item)
                        .show(getChildFragmentManager(), "song_detail");
            }
            @Override public void onDelete(MusicItem item) {
                showDeleteConfirmationDialog(item);
            }
            @Override public void onPin(MusicItem item) {
                toggleSongPin(item);
            }
        });
        sheet.show(getChildFragmentManager(), "song_options");
    }

    // ── Delete ────────────────────────────────────────────────────────────────

    private void showDeleteConfirmationDialog(MusicItem musicItem) {
        if (getContext() == null || musicItem == null) return;
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle("Delete Song")
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
                                ((RecoverableSecurityException) se).getUserAction()
                                        .getActionIntent().getIntentSender()).build());
                    } else {
                        Toast.makeText(requireContext(), "Permission denied.", Toast.LENGTH_SHORT).show();
                    }
                }
            } else {
                int rows = requireContext().getContentResolver().delete(uri, null, null);
                if (rows > 0) { new File(musicItem.getPath()).delete(); onSongDeleteSuccess(musicItem); }
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
        Toast.makeText(requireContext(), "Song deleted successfully", Toast.LENGTH_SHORT).show();
        searchResults.remove(musicItem);
        allMusicList.remove(musicItem);
        if (searchAdapter != null) searchAdapter.notifyDataSetChanged();
        String currentQuery = binding.searchEditText.getText().toString().trim();
        updateUI(currentQuery);
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
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle("New Collection").setView(dialogView)
                .setPositiveButton("Create", (dialog, which) -> {
                    String name = editTextName.getText() != null
                            ? editTextName.getText().toString().trim() : "";
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

    // ── Search ────────────────────────────────────────────────────────────────

    private void setupSearchView() {
        binding.searchEditText.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable s) {
                String query = s.toString().trim();
                binding.clearButton.setVisibility(query.isEmpty() ? View.GONE : View.VISIBLE);
                if (searchRunnable != null) searchHandler.removeCallbacks(searchRunnable);
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
            @Override public void onGlobalLayout() {
                binding.searchEditText.getViewTreeObserver().removeOnGlobalLayoutListener(this);
                showKeyboard();
            }
        });
    }

    private void showKeyboard() {
        if (getActivity() == null || isFragmentDestroyed || binding == null) return;
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

    private void performSearch(String query) {
        if (query.isEmpty()) { showInitialState(); return; }
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
        if (query.isEmpty()) showInitialState();
        else if (searchResults.isEmpty()) showNoResults(query);
        else showResults();
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

    // ── Load music ────────────────────────────────────────────────────────────

    private void loadAllMusic() {
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
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED)
                loadMusicFromDevice();
            else
                Toast.makeText(getContext(), "Permission denied. Cannot search music files.", Toast.LENGTH_SHORT).show();
        }
    }

    private void loadMusicFromDevice() {
        executorService.execute(() -> {
            List<MusicItem> temp = new ArrayList<>();
            Uri musicUri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;
            String[] projection = {
                    MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE,
                    MediaStore.Audio.Media.ARTIST, MediaStore.Audio.Media.ALBUM,
                    MediaStore.Audio.Media.DURATION, MediaStore.Audio.Media.DATA,
                    MediaStore.Audio.Media.ALBUM_ID
            };
            try (Cursor cursor = requireContext().getContentResolver().query(
                    musicUri, projection,
                    MediaStore.Audio.Media.IS_MUSIC + " != 0", null,
                    MediaStore.Audio.Media.TITLE + " ASC")) {
                if (cursor != null && cursor.moveToFirst()) {
                    do {
                        long   id       = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID));
                        String title    = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE));
                        String artist   = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST));
                        String album    = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM));
                        long   duration = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION));
                        String path     = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA));
                        long   albumId  = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID));
                        temp.add(new MusicItem(id, title, artist, album, duration, path,
                                Uri.parse("content://media/external/audio/albumart/" + albumId)));
                    } while (cursor.moveToNext());
                }
            } catch (Exception e) {
                e.printStackTrace();
                if (getActivity() != null)
                    getActivity().runOnUiThread(() ->
                            Toast.makeText(getContext(), "Error loading music files", Toast.LENGTH_SHORT).show());
                return;
            }

            restoreSongPinnedState(temp);

            if (getActivity() != null) {
                getActivity().runOnUiThread(() -> {
                    allMusicList.clear();
                    allMusicList.addAll(temp);
                });
            }
        });
    }

    // ── Playback ──────────────────────────────────────────────────────────────

    private void startMusicServiceAndOpenNowPlaying(MusicItem musicItem) {
        startMusicServiceWithPlaylist(musicItem);
        new Handler().postDelayed(() -> openNowPlaying(musicItem), 200);
    }

    private void startMusicServiceWithPlaylist(MusicItem selectedSong) {
        if (getContext() == null || searchResults.isEmpty()) return;
        int idx = 0;
        for (int i = 0; i < searchResults.size(); i++)
            if (searchResults.get(i).getId() == selectedSong.getId()) { idx = i; break; }

        Intent pl = new Intent(getContext(), MusicService.class);
        pl.setAction(MusicService.ACTION_SET_PLAYLIST);
        pl.putParcelableArrayListExtra("playlist", new ArrayList<>(searchResults));
        pl.putExtra("start_index", idx);
        getContext().startService(pl);

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

    private void openNowPlayingActivity() {
        if (isFragmentDestroyed || currentPlayingItem == null || getActivity() == null) return;
        try {
            Intent intent = new Intent(getActivity(), NowPlayingActivity.class);
            intent.putExtra("music_item", currentPlayingItem);
            startActivity(intent);
            getActivity().overridePendingTransition(R.anim.slide_in_bottom, R.anim.slide_out_top);
        } catch (Exception e) { Log.e(TAG, "Error opening NowPlaying", e); }
    }

    // ── Mini player state ─────────────────────────────────────────────────────

    public void showMiniPlayer(MusicItem item) {
        if (isFragmentDestroyed || getActivity() == null || item == null) return;
        try {
            currentPlayingItem = item;
            miniSongTitle.setText(item.getTitle());
            miniArtistName.setText(item.getArtist());
            try {
                Glide.with(this).load(item.getAlbumArtUri())
                        .placeholder(R.drawable.ic_outline_music_note_24)
                        .error(R.drawable.ic_outline_music_note_24)
                        .into(miniAlbumArt);
            } catch (Exception ignored) {}

            if (!isMiniPlayerVisible) {
                isMiniPlayerVisible = true;
                miniPlayerContainer.setVisibility(View.VISIBLE);
                miniPlayerContainer.setTranslationY(miniPlayerContainer.getHeight());
                miniPlayerContainer.animate().translationY(0).setDuration(300).start();
            }
            updateMiniPlayerPlayButton();
        } catch (Exception e) { Log.e(TAG, "Error showing mini player", e); }
    }

    public void hideMiniPlayer() {
        if (isFragmentDestroyed) return;
        try {
            if (isMiniPlayerVisible && miniPlayerContainer != null) {
                isMiniPlayerVisible = false;
                miniPlayerContainer.animate().translationY(miniPlayerContainer.getHeight()).setDuration(300)
                        .withEndAction(() -> { if (!isFragmentDestroyed) miniPlayerContainer.setVisibility(View.GONE); })
                        .start();
            }
        } catch (Exception e) { Log.e(TAG, "Error hiding mini player", e); }
    }

    public void updateMiniPlayerState(boolean playing) {
        if (isFragmentDestroyed) return;
        isPlaying = playing;
        updateMiniPlayerPlayButton();
    }

    private void updateMiniPlayerPlayButton() {
        if (isFragmentDestroyed || miniPlayPauseButton == null) return;
        miniPlayPauseButton.setIconResource(
                isPlaying ? R.drawable.ic_baseline_pause_24 : R.drawable.ic_baseline_play_arrow_24);
    }

    private void adjustRecyclerViewPadding(boolean isVisible, int height) {
        if (binding != null && binding.searchRecyclerView != null) {
            RecyclerView rv = binding.searchRecyclerView;
            rv.setPadding(rv.getPaddingLeft(), rv.getPaddingTop(), rv.getPaddingRight(),
                    isVisible ? height : 0);
        }
    }

    // ── BroadcastReceiver ─────────────────────────────────────────────────────

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private void registerMusicUpdateReceiver() {
        if (isReceiverRegistered || getActivity() == null) return;
        try {
            IntentFilter f = new IntentFilter();
            f.addAction(MusicService.ACTION_MUSIC_UPDATED);
            f.addAction(MusicService.ACTION_PLAYBACK_STATE_CHANGED);
            f.addAction(MusicService.ACTION_HIDE_MINI_PLAYER);
            f.addAction("MINI_PLAYER_VISIBILITY_CHANGED");

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                getActivity().registerReceiver(miniPlayerReceiver, f, Context.RECEIVER_NOT_EXPORTED);
            else
                getActivity().registerReceiver(miniPlayerReceiver, f);

            isReceiverRegistered = true;
        } catch (Exception e) {
            Log.e(TAG, "Error registering receiver", e);
            isReceiverRegistered = false;
        }
    }

    // ── Fragment lifecycle ────────────────────────────────────────────────────

    @Override
    public void onResume() {
        super.onResume();
        registerMusicUpdateReceiver();
        if (getActivity() != null) sendServiceAction(MusicService.ACTION_REQUEST_STATE);
    }

    @Override
    public void onPause() {
        super.onPause();
        if (getActivity() != null && isReceiverRegistered) {
            try { getActivity().unregisterReceiver(miniPlayerReceiver); isReceiverRegistered = false; }
            catch (IllegalArgumentException ignored) {}
        }
        if (miniPlayerContainer != null) miniPlayerContainer.clearAnimation();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        isFragmentDestroyed = true;
        if (isReceiverRegistered && getActivity() != null) {
            try { getActivity().unregisterReceiver(miniPlayerReceiver); }
            catch (Exception ignored) {}
            finally { isReceiverRegistered = false; }
        }
        if (executorService != null && !executorService.isShutdown()) executorService.shutdown();
        if (searchHandler != null && searchRunnable != null) searchHandler.removeCallbacks(searchRunnable);
        currentPlayingItem = null;
        try {
            if (getActivity() != null && !getActivity().isDestroyed() && miniAlbumArt != null)
                Glide.with(this).clear(miniAlbumArt);
        } catch (Exception ignored) {}
        binding = null;
    }
}
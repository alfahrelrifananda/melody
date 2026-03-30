package com.alfahrel.melody.ui.search;

import android.app.RecoverableSecurityException;
import android.content.Context;
import android.content.Intent;
import android.content.IntentSender;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Parcelable;
import android.provider.MediaStore;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.IntentSenderRequest;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.alfahrel.melody.R;
import com.alfahrel.melody.service.MusicService;
import com.alfahrel.melody.ui.collection.Collection;
import com.alfahrel.melody.ui.collection.CollectionManager;
import com.alfahrel.melody.ui.music.MusicAdapter;
import com.alfahrel.melody.ui.music.MusicItem;
import com.alfahrel.melody.ui.pages.nowplaying.AddToCollectionAdapter;
import com.alfahrel.melody.ui.pages.nowplaying.NowPlayingActivity;
import com.alfahrel.melody.utils.GsonHelper;
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

public class SearchSongsFragment extends Fragment {

    private static final String SONG_PREFS_NAME = "SongsPrefs";
    private static final String KEY_SONGS       = "songs_full";

    private RecyclerView      recyclerView;
    private LinearLayout      emptyState;
    private MusicAdapter      adapter;
    private CollectionManager collectionManager;
    private PlayCountManager  playCountManager;

    private MusicItem pendingDeleteItem = null;
    private ActivityResultLauncher<IntentSenderRequest> deletePermissionLauncher;

    private final List<MusicItem> songs = new ArrayList<>();

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        deletePermissionLauncher = registerForActivityResult(
                new ActivityResultContracts.StartIntentSenderForResult(),
                result -> {
                    if (result.getResultCode() == android.app.Activity.RESULT_OK
                            && pendingDeleteItem != null) {
                        deleteSongAfterPermission(pendingDeleteItem);
                    } else {
                        Toast.makeText(requireContext(),
                                "Permission denied to delete file", Toast.LENGTH_SHORT).show();
                    }
                    pendingDeleteItem = null;
                });
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_search_songs, container, false);
        recyclerView      = view.findViewById(R.id.recyclerView);
        emptyState        = view.findViewById(R.id.emptyState);
        collectionManager = new CollectionManager(requireContext());
        playCountManager  = new PlayCountManager(requireContext());

        adapter = new MusicAdapter(songs, getContext());
        recyclerView.setLayoutManager(new LinearLayoutManager(getContext()));
        recyclerView.setAdapter(adapter);

        adapter.setOnMusicItemClickListener(new MusicAdapter.OnMusicItemClickListener() {
            @Override public void onMusicItemClick(MusicItem song) { playSong(song); }
            @Override public void onOptionClick(MusicItem song)    { showSongOptions(song); }
        });
        adapter.setOnMusicItemLongClickListener(song -> { showSongOptions(song); return true; });

        return view;
    }

    public void updateSongs(List<MusicItem> newSongs) {
        songs.clear();
        songs.addAll(newSongs);
        if (adapter != null)     adapter.notifyDataSetChanged();
        if (emptyState != null)  emptyState.setVisibility(songs.isEmpty() ? View.VISIBLE : View.GONE);
        if (recyclerView != null) recyclerView.setVisibility(songs.isEmpty() ? View.GONE : View.VISIBLE);
    }

    // ── Playback ──────────────────────────────────────────────────────────────

    private void playSong(MusicItem song) {
        playCountManager.increment(song.getId());
        int idx = songs.indexOf(song);
        if (idx < 0) idx = 0;

        Intent pi = new Intent(requireContext(), MusicService.class);
        pi.setAction(MusicService.ACTION_SET_PLAYLIST);
        pi.putParcelableArrayListExtra("playlist", new ArrayList<>(songs));
        pi.putExtra("start_index", idx);
        requireContext().startService(pi);

        Intent play = new Intent(requireContext(), MusicService.class);
        play.setAction(MusicService.ACTION_PLAY);
        play.putExtra("music_item", song);
        requireContext().startService(play);

        new android.os.Handler().postDelayed(() -> {
            if (getContext() == null) return;
            Intent intent = new Intent(requireContext(), NowPlayingActivity.class);
            intent.putExtra("music_item", (Parcelable) song);
            startActivity(intent);
            if (getActivity() != null)
                getActivity().overridePendingTransition(R.anim.slide_in_bottom, R.anim.slide_out_top);
        }, 200);
    }

    // ── Song options bottom sheet ─────────────────────────────────────────────

    private void showSongOptions(MusicItem song) {
        if (song == null) return;
        SongOptionsBottomSheet sheet = SongOptionsBottomSheet.newInstance(song);
        sheet.setListener(new SongOptionsBottomSheet.SongOptionsListener() {
            @Override public void onAddToCollection(MusicItem item) { showAddToCollectionBottomSheet(item); }
            @Override public void onViewDetails(MusicItem item) {
                SongDetailBottomSheet.newInstance(item)
                        .show(getChildFragmentManager(), "detail");
            }
            @Override public void onDelete(MusicItem item) { showDeleteConfirmationDialog(item); }
            @Override public void onPin(MusicItem item) { toggleSongPin(item); }
        });
        sheet.show(getChildFragmentManager(), "song_options");
    }

    // ── Delete ────────────────────────────────────────────────────────────────

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
            Uri uri = android.content.ContentUris.withAppendedId(
                    MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, song.getId());
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                try {
                    int rows = requireContext().getContentResolver().delete(uri, null, null);
                    if (rows > 0) onSongDeleteSuccess(song);
                    else Toast.makeText(requireContext(),
                            "Failed to delete song", Toast.LENGTH_SHORT).show();
                } catch (SecurityException se) {
                    if (se instanceof RecoverableSecurityException) {
                        pendingDeleteItem = song;
                        IntentSender sender = ((RecoverableSecurityException) se)
                                .getUserAction().getActionIntent().getIntentSender();
                        deletePermissionLauncher.launch(
                                new IntentSenderRequest.Builder(sender).build());
                    } else {
                        Toast.makeText(requireContext(),
                                "Permission denied.", Toast.LENGTH_SHORT).show();
                    }
                }
            } else {
                int rows = requireContext().getContentResolver().delete(uri, null, null);
                if (rows > 0) {
                    new java.io.File(song.getPath()).delete();
                    onSongDeleteSuccess(song);
                } else {
                    Toast.makeText(requireContext(),
                            "Failed to delete song", Toast.LENGTH_SHORT).show();
                }
            }
        } catch (Exception e) {
            Toast.makeText(requireContext(), "Error: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private void deleteSongAfterPermission(MusicItem song) {
        try {
            Uri uri = android.content.ContentUris.withAppendedId(
                    MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, song.getId());
            int rows = requireContext().getContentResolver().delete(uri, null, null);
            if (rows > 0) onSongDeleteSuccess(song);
            else Toast.makeText(requireContext(),
                    "Failed to delete song", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(requireContext(), "Error: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private void onSongDeleteSuccess(MusicItem song) {
        songs.remove(song);
        if (adapter != null) adapter.notifyDataSetChanged();
        if (emptyState != null)
            emptyState.setVisibility(songs.isEmpty() ? View.VISIBLE : View.GONE);
        Toast.makeText(requireContext(), "Song deleted", Toast.LENGTH_SHORT).show();
        Intent intent = new Intent("SONG_DELETED");
        intent.putExtra("song_id", song.getId());
        intent.setPackage(requireContext().getPackageName());
        requireContext().sendBroadcast(intent);
    }

    // ── Pin ───────────────────────────────────────────────────────────────────

    private void toggleSongPin(MusicItem song) {
        boolean nowPinned = !song.isPinned();
        song.setPinned(nowPinned);

        // Persist into SongsPrefs (same store HomeFragment uses)
        SharedPreferences sp = requireContext()
                .getSharedPreferences(SONG_PREFS_NAME, Context.MODE_PRIVATE);
        String json = sp.getString(KEY_SONGS, null);
        if (json != null) {
            Type type = new TypeToken<List<MusicItem>>() {}.getType();
            List<MusicItem> saved = GsonHelper.get().fromJson(json, type);
            if (saved != null) {
                for (MusicItem s : saved) {
                    if (s.getId() == song.getId()) { s.setPinned(nowPinned); break; }
                }
                sp.edit().putString(KEY_SONGS, GsonHelper.get().toJson(saved)).apply();
            }
        }

        broadcast("com.alfahrel.melody.SONG_PIN_CHANGED");
        adapter.notifyDataSetChanged();
        Toast.makeText(requireContext(),
                nowPinned ? "Pinned to Home" : "Unpinned from Home",
                Toast.LENGTH_SHORT).show();
    }

    // ── Add to collection ─────────────────────────────────────────────────────

    private void showAddToCollectionBottomSheet(MusicItem song) {
        if (song == null) return;
        List<Collection> collections = collectionManager.getAllCollections();
        BottomSheetDialog dialog = new BottomSheetDialog(requireContext());
        View view = LayoutInflater.from(requireContext())
                .inflate(R.layout.bottom_sheet_add_to_collection, null);
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
                            Toast.makeText(requireContext(),
                                    "Added to " + col.getName(), Toast.LENGTH_SHORT).show();
                            broadcast("com.alfahrel.melody.SONG_ADDED_TO_COLLECTION");
                            dialog.dismiss();
                        } else {
                            Toast.makeText(requireContext(),
                                    "Song already in " + col.getName(), Toast.LENGTH_SHORT).show();
                        }
                    });
            rv.setAdapter(colAdapter);
        }
        createBtn.setOnClickListener(v -> { dialog.dismiss(); showCreateCollectionDialog(song); });
        dialog.setContentView(view);
        dialog.show();
    }

    private void showCreateCollectionDialog(MusicItem song) {
        View dialogView = LayoutInflater.from(requireContext())
                .inflate(R.layout.dialog_add_collection, null);
        TextInputEditText editName = dialogView.findViewById(R.id.editTextCollectionName);
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle("New collection")
                .setView(dialogView)
                .setPositiveButton("Create", (d, w) -> {
                    String name = editName.getText() != null
                            ? editName.getText().toString().trim() : "";
                    if (!name.isEmpty()) createCollectionAndAddSong(name, song);
                    else Toast.makeText(requireContext(),
                            "Collection name cannot be empty", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void createCollectionAndAddSong(String name, MusicItem song) {
        for (Collection c : collectionManager.getAllCollections()) {
            if (c.getName().equalsIgnoreCase(name)) {
                Toast.makeText(requireContext(),
                        "Collection already exists", Toast.LENGTH_SHORT).show();
                return;
            }
        }
        Collection col = collectionManager.createCollection(name);
        collectionManager.addSongToCollection(col.getId(), song.getId());
        Toast.makeText(requireContext(),
                "Created \"" + name + "\" and added song", Toast.LENGTH_SHORT).show();
        broadcast("com.alfahrel.melody.COLLECTION_CREATED");
    }

    // ── Helper ────────────────────────────────────────────────────────────────

    private void broadcast(String action) {
        if (getContext() == null) return;
        Intent i = new Intent(action);
        i.setPackage(requireContext().getPackageName());
        requireContext().sendBroadcast(i);
    }
}
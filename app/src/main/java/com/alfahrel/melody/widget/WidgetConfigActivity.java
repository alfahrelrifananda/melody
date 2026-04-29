package com.alfahrel.melody.widget;

import android.app.Activity;
import android.appwidget.AppWidgetManager;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.provider.MediaStore;
import android.util.Log;
import android.view.View;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.alfahrel.melody.R;
import com.alfahrel.melody.ui.collection.Collection;
import com.alfahrel.melody.ui.collection.CollectionManager;

import java.util.ArrayList;
import java.util.List;

/**
 * Shown when the user long-presses → "Add Widget" → Melody Widget on the home screen.
 * The user picks a Collection or Album, then the widget is placed.
 *
 * Result flow:
 *   1. User selects a source → we save it in SharedPreferences keyed by appWidgetId.
 *   2. Call AppWidgetManager.updateAppWidget() to render immediately.
 *   3. Set RESULT_OK and finish.
 */
public class WidgetConfigActivity extends AppCompatActivity
        implements WidgetConfigAdapter.OnSourceSelectedListener {

    private static final String TAG = "WidgetConfigActivity";

    private int appWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID;
    private WidgetConfigAdapter adapter;
    private ProgressBar progressBar;
    private TextView emptyText;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Important: set RESULT_CANCELED so if the user backs out, no widget is placed.
        setResult(RESULT_CANCELED);

        setContentView(R.layout.activity_widget_config);

        // Extract the appWidgetId from the intent
        Bundle extras = getIntent().getExtras();
        if (extras != null) {
            appWidgetId = extras.getInt(
                    AppWidgetManager.EXTRA_APPWIDGET_ID,
                    AppWidgetManager.INVALID_APPWIDGET_ID);
        }

        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish();
            return;
        }

        setupUI();
        loadSources();
    }

    // =========================================================================
    // UI setup
    // =========================================================================

    private void setupUI() {
        RecyclerView recyclerView = findViewById(R.id.config_recycler_view);
        progressBar = findViewById(R.id.config_progress);
        emptyText   = findViewById(R.id.config_empty_text);

        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        adapter = new WidgetConfigAdapter(this);
        recyclerView.setAdapter(adapter);

        // Optional: close button / cancel
        View cancelBtn = findViewById(R.id.config_cancel_button);
        if (cancelBtn != null) {
            cancelBtn.setOnClickListener(v -> finish());
        }
    }

    // =========================================================================
    // Data loading (AsyncTask — simple enough for config, no ViewModel needed)
    // =========================================================================

    private void loadSources() {
        progressBar.setVisibility(View.VISIBLE);
        emptyText.setVisibility(View.GONE);

        new LoadSourcesTask(this).execute();
    }

    @SuppressWarnings("deprecation")
    private static class LoadSourcesTask extends AsyncTask<Void, Void, List<WidgetSource>> {

        private final WidgetConfigActivity activity;

        LoadSourcesTask(WidgetConfigActivity activity) {
            this.activity = activity;
        }

        @Override
        protected List<WidgetSource> doInBackground(Void... voids) {
            List<WidgetSource> sources = new ArrayList<>();

            // --- Collections ---
            CollectionManager mgr = new CollectionManager(activity);
            List<Collection> collections = mgr.getAllCollections();
            for (Collection col : collections) {
                if (col.getSongCount() == 0) continue; // skip empty collections

                String coverUri = null;
                // Try to get album art from the first song in the collection
                if (!col.getMusicIds().isEmpty()) {
                    coverUri = getAlbumArtUriForSong(activity, col.getMusicIds().get(0));
                }
                if (coverUri == null && col.getCoverImageUri() != null) {
                    coverUri = col.getCoverImageUri();
                }

                sources.add(new WidgetSource(
                        MelodyWidget.TYPE_COLLECTION,
                        col.getId(),
                        col.getName(),
                        col.getSongCount() + (col.getSongCount() == 1 ? " song" : " songs"),
                        coverUri
                ));
            }

            // --- Albums ---
            List<WidgetSource> albums = loadAlbums(activity);
            sources.addAll(albums);

            return sources;
        }

        @Override
        protected void onPostExecute(List<WidgetSource> sources) {
            if (activity.isFinishing() || activity.isDestroyed()) return;

            activity.progressBar.setVisibility(View.GONE);

            if (sources.isEmpty()) {
                activity.emptyText.setVisibility(View.VISIBLE);
            } else {
                activity.adapter.setData(sources);
            }
        }

        private static List<WidgetSource> loadAlbums(Context ctx) {
            List<WidgetSource> result = new ArrayList<>();
            ContentResolver resolver = ctx.getContentResolver();

            String[] projection = {
                    MediaStore.Audio.Albums._ID,
                    MediaStore.Audio.Albums.ALBUM,
                    MediaStore.Audio.Albums.ARTIST,
                    MediaStore.Audio.Albums.NUMBER_OF_SONGS,
                    MediaStore.Audio.Albums.ALBUM_ART
            };
            String sortOrder = MediaStore.Audio.Albums.ALBUM + " ASC";

            try (Cursor cursor = resolver.query(
                    MediaStore.Audio.Albums.EXTERNAL_CONTENT_URI,
                    projection, null, null, sortOrder)) {

                if (cursor == null) return result;

                int idIdx       = cursor.getColumnIndexOrThrow(MediaStore.Audio.Albums._ID);
                int albumIdx    = cursor.getColumnIndexOrThrow(MediaStore.Audio.Albums.ALBUM);
                int artistIdx   = cursor.getColumnIndexOrThrow(MediaStore.Audio.Albums.ARTIST);
                int countIdx    = cursor.getColumnIndexOrThrow(MediaStore.Audio.Albums.NUMBER_OF_SONGS);
                int artIdx      = cursor.getColumnIndex(MediaStore.Audio.Albums.ALBUM_ART);

                while (cursor.moveToNext()) {
                    long albumId    = cursor.getLong(idIdx);
                    String album    = cursor.getString(albumIdx);
                    String artist   = cursor.getString(artistIdx);
                    int count       = cursor.getInt(countIdx);
                    String artPath  = artIdx >= 0 ? cursor.getString(artIdx) : null;

                    // Build a content URI for the art (more reliable than file path on newer Android)
                    String artUri = "content://media/external/audio/albumart/" + albumId;

                    result.add(new WidgetSource(
                            MelodyWidget.TYPE_ALBUM,
                            albumId,
                            album != null ? album : "Unknown Album",
                            (artist != null ? artist : "Unknown Artist")
                                    + " • " + count + (count == 1 ? " song" : " songs"),
                            artUri
                    ));
                }
            } catch (Exception e) {
                Log.e(TAG, "Error loading albums: " + e.getMessage(), e);
            }
            return result;
        }

        private static String getAlbumArtUriForSong(Context ctx, long musicId) {
            ContentResolver resolver = ctx.getContentResolver();
            String[] proj = { MediaStore.Audio.Media.ALBUM_ID };
            String sel    = MediaStore.Audio.Media._ID + " = ?";
            try (Cursor c = resolver.query(
                    MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                    proj, sel, new String[]{ String.valueOf(musicId) }, null)) {
                if (c != null && c.moveToFirst()) {
                    long albumId = c.getLong(0);
                    return "content://media/external/audio/albumart/" + albumId;
                }
            } catch (Exception ignored) {}
            return null;
        }
    }

    // =========================================================================
    // Selection callback
    // =========================================================================

    @Override
    public void onSourceSelected(WidgetSource source) {
        // Save config for this widget instance
        SharedPreferences prefs = getSharedPreferences(MelodyWidget.PREFS_NAME, MODE_PRIVATE);
        prefs.edit()
                .putString(MelodyWidget.KEY_SOURCE_TYPE + appWidgetId, source.type)
                .putLong(  MelodyWidget.KEY_SOURCE_ID   + appWidgetId, source.id)
                .putString(MelodyWidget.KEY_SOURCE_NAME + appWidgetId, source.name)
                .putString(MelodyWidget.KEY_COVER_URI   + appWidgetId,
                        source.coverUri != null ? source.coverUri : "")
                .apply();

        // Trigger immediate widget render
        AppWidgetManager manager = AppWidgetManager.getInstance(this);
        MelodyWidget.updateWidget(this, manager, appWidgetId);

        // Return OK — the widget is placed on the home screen
        Intent resultIntent = new Intent();
        resultIntent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId);
        setResult(RESULT_OK, resultIntent);
        finish();
    }
}
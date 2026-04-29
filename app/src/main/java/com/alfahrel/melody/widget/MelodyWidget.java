package com.alfahrel.melody.widget;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;
import android.util.Log;
import android.widget.RemoteViews;

import com.bumptech.glide.Glide;
import com.bumptech.glide.request.target.AppWidgetTarget;
import com.bumptech.glide.request.transition.Transition;
import com.alfahrel.melody.MainActivity;
import com.alfahrel.melody.R;
import com.alfahrel.melody.service.MusicService;
import com.alfahrel.melody.ui.collection.Collection;
import com.alfahrel.melody.ui.collection.CollectionManager;
import com.alfahrel.melody.ui.music.MusicItem;

import java.util.ArrayList;
import java.util.List;

public class MelodyWidget extends AppWidgetProvider {

    private static final String TAG = "MelodyWidget";

    public static final String ACTION_WIDGET_SHUFFLE = "com.alfahrel.melody.WIDGET_SHUFFLE";
    public static final String ACTION_WIDGET_PLAY    = "com.alfahrel.melody.WIDGET_PLAY";

    static final String PREFS_NAME          = "MelodyWidgetPrefs";
    static final String KEY_SOURCE_TYPE     = "source_type_";
    static final String KEY_SOURCE_ID       = "source_id_";
    static final String KEY_SOURCE_NAME     = "source_name_";
    static final String KEY_COVER_URI       = "cover_uri_";
    static final String KEY_NOW_PLAYING     = "now_playing_";
    static final String KEY_PROGRESS        = "progress_";   // 0-1000
    static final String KEY_DURATION        = "duration_";   // ms
    static final String KEY_IS_PLAYING      = "is_playing_"; // boolean

    public static final String TYPE_COLLECTION = "collection";
    public static final String TYPE_ALBUM      = "album";

    // =========================================================================
    // AppWidgetProvider callbacks
    // =========================================================================

    @Override
    public void onUpdate(Context context, AppWidgetManager appWidgetManager, int[] appWidgetIds) {
        for (int id : appWidgetIds) {
            updateWidget(context, appWidgetManager, id);
        }
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        super.onReceive(context, intent);

        String action = intent.getAction();
        if (action == null) return;

        int widgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID,
                AppWidgetManager.INVALID_APPWIDGET_ID);

        switch (action) {
            case ACTION_WIDGET_SHUFFLE:
                handlePlay(context, widgetId, true);
                break;

            case ACTION_WIDGET_PLAY:
                handlePlayOrToggle(context, widgetId);
                break;

            case MusicService.ACTION_MUSIC_UPDATED:
                refreshAllWidgets(context, intent);
                break;

            // FIX: listen for play/pause state changes to update the button icon
            case MusicService.ACTION_PLAYBACK_STATE_CHANGED:
                boolean isPlaying = intent.getBooleanExtra("is_playing", false);
                // Persist the state so updateWidget() can read it on redraw
                persistIsPlaying(context, isPlaying);
                updatePlayPauseIcon(context, isPlaying);
                break;
        }
    }

    @Override
    public void onDeleted(Context context, int[] appWidgetIds) {
        SharedPreferences.Editor editor =
                context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit();
        for (int id : appWidgetIds) {
            editor.remove(KEY_SOURCE_TYPE  + id);
            editor.remove(KEY_SOURCE_ID    + id);
            editor.remove(KEY_SOURCE_NAME  + id);
            editor.remove(KEY_COVER_URI    + id);
            editor.remove(KEY_NOW_PLAYING  + id);
            editor.remove(KEY_PROGRESS     + id);
            editor.remove(KEY_DURATION     + id);
            editor.remove(KEY_IS_PLAYING   + id);
        }
        editor.apply();
    }

    // =========================================================================
    // Widget rendering
    // =========================================================================

    static void updateWidget(Context context, AppWidgetManager manager, int widgetId) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String type       = prefs.getString(KEY_SOURCE_TYPE  + widgetId, null);
        String name       = prefs.getString(KEY_SOURCE_NAME  + widgetId, "Melody");
        String coverUri   = prefs.getString(KEY_COVER_URI    + widgetId, null);
        long   sourceId   = prefs.getLong(KEY_SOURCE_ID      + widgetId, -1);
        String nowPlaying = prefs.getString(KEY_NOW_PLAYING  + widgetId, "Tap \u25B6 to play");
        int    progress   = prefs.getInt(KEY_PROGRESS        + widgetId, 0);
        boolean playing   = prefs.getBoolean(KEY_IS_PLAYING  + widgetId, false);

        RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.widget_melody);

        // Song is considered active if it's been loaded (not the default/error states)
        boolean songActive = nowPlaying != null
                && !nowPlaying.equals("Tap \u25B6 to play")
                && !nowPlaying.equals("No songs found");

        views.setTextViewText(R.id.widget_source_name, name);
        views.setTextViewText(R.id.widget_song_count,  getSongCountLabel(context, type, sourceId));
        views.setTextViewText(R.id.widget_song_title,  nowPlaying);

        // Show play button only when a song is active (loading, playing, or paused)
        views.setViewVisibility(R.id.widget_btn_play,
                songActive ? android.view.View.VISIBLE : android.view.View.GONE);

        // Play/pause icon
        views.setImageViewResource(R.id.widget_btn_play,
                playing ? R.drawable.ic_baseline_pause_24
                        : R.drawable.ic_baseline_play_arrow_24);

        // Cover art
        if (coverUri != null && !coverUri.isEmpty()) {
            AppWidgetTarget awt = new AppWidgetTarget(
                    context, R.id.widget_album_art, views, widgetId) {
                @Override
                public void onResourceReady(Bitmap resource,
                                            Transition<? super Bitmap> transition) {
                    super.onResourceReady(resource, transition);
                }
            };
            Glide.with(context.getApplicationContext())
                    .asBitmap()
                    .load(Uri.parse(coverUri))
                    .placeholder(R.drawable.ic_outline_music_note_24)
                    .error(R.drawable.ic_outline_music_note_24)
                    .into(awt);
        } else {
            views.setImageViewResource(R.id.widget_album_art,
                    R.drawable.ic_outline_music_note_24);
        }

        // Buttons
        views.setOnClickPendingIntent(R.id.widget_btn_shuffle,
                buildWidgetIntent(context, widgetId, ACTION_WIDGET_SHUFFLE));
        views.setOnClickPendingIntent(R.id.widget_btn_play,
                buildWidgetIntent(context, widgetId, ACTION_WIDGET_PLAY));

        // Tap album art → open app
        Intent openApp = new Intent(context, MainActivity.class);
        openApp.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent openAppPi = PendingIntent.getActivity(context, widgetId, openApp,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        views.setOnClickPendingIntent(R.id.widget_album_art, openAppPi);

        manager.updateAppWidget(widgetId, views);
    }

    // =========================================================================
    // Playback handling
    // =========================================================================

    /**
     * If the widget already has a song loaded, send a toggle-play-pause so the
     * button feels instant. Otherwise do a full play-from-source.
     */
    private void handlePlayOrToggle(Context context, int widgetId) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String nowPlaying = prefs.getString(KEY_NOW_PLAYING + widgetId, null);

        boolean songAlreadyLoaded = nowPlaying != null
                && !nowPlaying.equals("Tap \u25B6 to play")
                && !nowPlaying.equals("Loading\u2026");

        if (songAlreadyLoaded) {
            // FIX: use startForegroundService so this works from widget on Android 8+
            sendServiceAction(context, MusicService.ACTION_TOGGLE_PLAY_PAUSE);
        } else {
            handlePlay(context, widgetId, false);
        }
    }

    private void handlePlay(Context context, int widgetId, boolean shuffle) {
        if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID) return;

        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String type     = prefs.getString(KEY_SOURCE_TYPE + widgetId, null);
        long   sourceId = prefs.getLong(KEY_SOURCE_ID     + widgetId, -1);

        if (type == null || sourceId == -1) {
            Log.w(TAG, "Widget " + widgetId + " has no source configured");
            return;
        }

        updateNowPlayingLabel(context, widgetId, "Loading\u2026");

        final String finalType = type;
        new Thread(() -> {
            List<MusicItem> songs = resolveSongs(context, finalType, sourceId);
            if (songs == null || songs.isEmpty()) {
                updateNowPlayingLabel(context, widgetId, "No songs found");
                return;
            }

            if (shuffle) {
                java.util.Collections.shuffle(songs);
            }

            Intent playlistIntent = new Intent(context, MusicService.class);
            playlistIntent.setAction(MusicService.ACTION_SET_PLAYLIST);
            playlistIntent.putParcelableArrayListExtra("playlist", new ArrayList<>(songs));
            playlistIntent.putExtra("start_index", 0);
            // FIX: use startForegroundService on Android 8+
            startServiceCompat(context, playlistIntent);

            Intent playIntent = new Intent(context, MusicService.class);
            playIntent.setAction(MusicService.ACTION_PLAY);
            playIntent.putExtra("music_item", songs.get(0));
            startServiceCompat(context, playIntent);

            String label = songs.get(0).getTitle() + " \u2014 " + songs.get(0).getArtist();
            updateNowPlayingLabel(context, widgetId, label);

        }).start();
    }

    // =========================================================================
    // Now-playing / progress / icon update helpers
    // =========================================================================

    /**
     * Called when MusicService broadcasts ACTION_MUSIC_UPDATED.
     * Updates song title, progress bar, and play/pause icon on every widget instance.
     */
    private void refreshAllWidgets(Context context, Intent updateIntent) {
        MusicItem item    = updateIntent.getParcelableExtra("music_item");
        int position      = updateIntent.getIntExtra("current_position", 0);
        int duration      = updateIntent.getIntExtra("duration", 0);
        // FIX: read is_playing from the broadcast (MusicService already sends this)
        boolean isPlaying = updateIntent.getBooleanExtra("is_playing", false);

        if (item == null) return;

        String label       = item.getTitle() + " \u2014 " + item.getArtist();
        int scaledProgress = (duration > 0) ? (int) ((position / (float) duration) * 1000) : 0;

        AppWidgetManager manager = AppWidgetManager.getInstance(context);
        int[] ids = manager.getAppWidgetIds(
                new ComponentName(context, MelodyWidget.class));
        SharedPreferences.Editor editor =
                context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit();

        for (int widgetId : ids) {
            editor.putString(KEY_NOW_PLAYING + widgetId, label);
            editor.putInt(KEY_PROGRESS       + widgetId, scaledProgress);
            editor.putBoolean(KEY_IS_PLAYING + widgetId, isPlaying);

            RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.widget_melody);
            views.setTextViewText(R.id.widget_song_title, label);
            // FIX: update icon to match actual playing state
            views.setImageViewResource(R.id.widget_btn_play,
                    isPlaying ? R.drawable.ic_baseline_pause_24
                            : R.drawable.ic_baseline_play_arrow_24);
            manager.partiallyUpdateAppWidget(widgetId, views);
        }
        editor.apply();
    }

    /**
     * Called when MusicService broadcasts ACTION_PLAYBACK_STATE_CHANGED.
     * Only updates the play/pause button icon — lightweight partial update.
     */
    private static void updatePlayPauseIcon(Context context, boolean isPlaying) {
        AppWidgetManager manager = AppWidgetManager.getInstance(context);
        int[] ids = manager.getAppWidgetIds(
                new ComponentName(context, MelodyWidget.class));
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);

        for (int widgetId : ids) {
            String nowPlaying = prefs.getString(KEY_NOW_PLAYING + widgetId, "Tap \u25B6 to play");
            boolean songActive = nowPlaying != null
                    && !nowPlaying.equals("Tap \u25B6 to play")
                    && !nowPlaying.equals("No songs found");

            RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.widget_melody);
            views.setViewVisibility(R.id.widget_btn_play,
                    songActive ? android.view.View.VISIBLE : android.view.View.GONE);
            views.setImageViewResource(R.id.widget_btn_play,
                    isPlaying ? R.drawable.ic_baseline_pause_24
                            : R.drawable.ic_baseline_play_arrow_24);
            manager.partiallyUpdateAppWidget(widgetId, views);
        }
    }

    private static void persistIsPlaying(Context context, boolean isPlaying) {
        AppWidgetManager manager = AppWidgetManager.getInstance(context);
        int[] ids = manager.getAppWidgetIds(
                new ComponentName(context, MelodyWidget.class));
        SharedPreferences.Editor editor =
                context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit();
        for (int widgetId : ids) {
            editor.putBoolean(KEY_IS_PLAYING + widgetId, isPlaying);
        }
        editor.apply();
    }

    private static void updateNowPlayingLabel(Context context, int widgetId, String label) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_NOW_PLAYING + widgetId, label)
                .apply();

        AppWidgetManager manager = AppWidgetManager.getInstance(context);
        RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.widget_melody);
        views.setTextViewText(R.id.widget_song_title, label);
        manager.partiallyUpdateAppWidget(widgetId, views);
    }

    // =========================================================================
    // Song resolution
    // =========================================================================

    private static List<MusicItem> resolveSongs(Context ctx, String type, long sourceId) {
        if (TYPE_COLLECTION.equals(type)) return resolveCollectionSongs(ctx, sourceId);
        if (TYPE_ALBUM.equals(type))      return resolveAlbumSongs(ctx, sourceId);
        return new ArrayList<>();
    }

    private static List<MusicItem> resolveCollectionSongs(Context ctx, long collectionId) {
        CollectionManager manager = new CollectionManager(ctx);
        Collection collection = manager.getCollection(collectionId);
        if (collection == null) return new ArrayList<>();

        List<MusicItem> result = new ArrayList<>();
        for (long musicId : collection.getMusicIds()) {
            MusicItem item = queryMusicItemById(ctx, musicId);
            if (item != null) result.add(item);
        }
        return result;
    }

    private static List<MusicItem> resolveAlbumSongs(Context ctx, long albumId) {
        List<MusicItem> result = new ArrayList<>();
        ContentResolver resolver = ctx.getContentResolver();

        String[] projection = {
                MediaStore.Audio.Media._ID,
                MediaStore.Audio.Media.TITLE,
                MediaStore.Audio.Media.ARTIST,
                MediaStore.Audio.Media.ALBUM,
                MediaStore.Audio.Media.DURATION,
                MediaStore.Audio.Media.DATA,
                MediaStore.Audio.Media.ALBUM_ID
        };
        String selection = MediaStore.Audio.Media.ALBUM_ID + " = ? AND "
                + MediaStore.Audio.Media.IS_MUSIC + " != 0";
        String sortOrder = MediaStore.Audio.Media.TRACK + " ASC";

        try (Cursor cursor = resolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                projection, selection,
                new String[]{ String.valueOf(albumId) }, sortOrder)) {

            if (cursor == null) return result;

            int idIdx      = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID);
            int titleIdx   = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE);
            int artistIdx  = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST);
            int albumIdx   = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM);
            int durIdx     = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION);
            int albumIdIdx = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID);

            while (cursor.moveToNext()) {
                long id  = cursor.getLong(idIdx);
                long aId = cursor.getLong(albumIdIdx);
                Uri artUri     = Uri.parse("content://media/external/audio/albumart/" + aId);
                Uri contentUri = Uri.withAppendedPath(
                        MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, String.valueOf(id));

                result.add(new MusicItem(id,
                        cursor.getString(titleIdx),
                        cursor.getString(artistIdx),
                        cursor.getString(albumIdx),
                        cursor.getLong(durIdx),
                        contentUri.toString(), artUri));
            }
        } catch (Exception e) {
            Log.e(TAG, "Error resolving album songs: " + e.getMessage(), e);
        }
        return result;
    }

    private static MusicItem queryMusicItemById(Context ctx, long musicId) {
        ContentResolver resolver = ctx.getContentResolver();
        String[] projection = {
                MediaStore.Audio.Media._ID,
                MediaStore.Audio.Media.TITLE,
                MediaStore.Audio.Media.ARTIST,
                MediaStore.Audio.Media.ALBUM,
                MediaStore.Audio.Media.DURATION,
                MediaStore.Audio.Media.DATA,
                MediaStore.Audio.Media.ALBUM_ID
        };
        try (Cursor cursor = resolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                projection,
                MediaStore.Audio.Media._ID + " = ?",
                new String[]{ String.valueOf(musicId) }, null)) {

            if (cursor != null && cursor.moveToFirst()) {
                long id      = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID));
                long albumId = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID));
                Uri artUri   = Uri.parse("content://media/external/audio/albumart/" + albumId);
                Uri contentUri = Uri.withAppendedPath(
                        MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, String.valueOf(id));

                return new MusicItem(id,
                        cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)),
                        cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)),
                        cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)),
                        cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)),
                        contentUri.toString(), artUri);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error querying song " + musicId + ": " + e.getMessage(), e);
        }
        return null;
    }

    // =========================================================================
    // Misc helpers
    // =========================================================================

    private static String getSongCountLabel(Context ctx, String type, long sourceId) {
        if (type == null || sourceId == -1) return "";
        if (TYPE_COLLECTION.equals(type)) {
            CollectionManager mgr = new CollectionManager(ctx);
            Collection col = mgr.getCollection(sourceId);
            if (col == null) return "";
            int count = col.getSongCount();
            return count + (count == 1 ? " song" : " songs");
        } else if (TYPE_ALBUM.equals(type)) {
            String[] proj = { MediaStore.Audio.Media._ID };
            String sel = MediaStore.Audio.Media.ALBUM_ID + " = ? AND "
                    + MediaStore.Audio.Media.IS_MUSIC + " != 0";
            try (Cursor c = ctx.getContentResolver().query(
                    MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                    proj, sel, new String[]{ String.valueOf(sourceId) }, null)) {
                int count = c != null ? c.getCount() : 0;
                return count + (count == 1 ? " song" : " songs");
            } catch (Exception e) {
                return "";
            }
        }
        return "";
    }

    private static PendingIntent buildWidgetIntent(Context ctx, int widgetId, String action) {
        Intent intent = new Intent(ctx, MelodyWidget.class);
        intent.setAction(action);
        intent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId);
        intent.setData(Uri.parse("melody://widget/" + widgetId + "/" + action));
        return PendingIntent.getBroadcast(ctx, widgetId, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    /**
     * FIX: use startForegroundService on Android 8+ so widget button presses
     * work even when the app is not in the foreground.
     */
    private static void sendServiceAction(Context ctx, String action) {
        Intent intent = new Intent(ctx, MusicService.class);
        intent.setAction(action);
        startServiceCompat(ctx, intent);
    }

    private static void startServiceCompat(Context ctx, Intent intent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ctx.startForegroundService(intent);
        } else {
            ctx.startService(intent);
        }
    }
}
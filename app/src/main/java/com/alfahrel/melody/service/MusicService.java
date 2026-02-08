package com.alfahrel.melody.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaMetadataRetriever;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.support.v4.media.MediaMetadataCompat;
import android.support.v4.media.session.MediaSessionCompat;
import android.support.v4.media.session.PlaybackStateCompat;
import android.util.Log;

import androidx.core.app.NotificationCompat;
import androidx.media.session.MediaButtonReceiver;

import com.alfahrel.melody.MainActivity;
import com.alfahrel.melody.R;
import com.alfahrel.melody.ui.music.MusicItem;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

public class MusicService extends Service implements
        MediaPlayer.OnPreparedListener,
        MediaPlayer.OnCompletionListener,
        MediaPlayer.OnErrorListener,
        AudioManager.OnAudioFocusChangeListener {

    private static final String TAG = "MusicService";
    private static final int NOTIFICATION_ID = 1;
    private static final String CHANNEL_ID = "MusicPlaybackChannel";

    public static final String ACTION_PLAY = "ACTION_PLAY";
    public static final String ACTION_PAUSE = "ACTION_PAUSE";
    public static final String ACTION_TOGGLE_PLAY_PAUSE = "ACTION_TOGGLE_PLAY_PAUSE";
    public static final String ACTION_NEXT = "ACTION_NEXT";
    public static final String ACTION_PREVIOUS = "ACTION_PREVIOUS";
    public static final String ACTION_STOP = "ACTION_STOP";
    public static final String ACTION_SEEK = "ACTION_SEEK";
    public static final String ACTION_REQUEST_STATE = "ACTION_REQUEST_STATE";
    public static final String ACTION_TOGGLE_SHUFFLE = "ACTION_TOGGLE_SHUFFLE";
    public static final String ACTION_TOGGLE_REPEAT = "ACTION_TOGGLE_REPEAT";
    public static final String ACTION_SET_PLAYLIST = "ACTION_SET_PLAYLIST";

    public static final String ACTION_MUSIC_UPDATED = "ACTION_MUSIC_UPDATED";
    public static final String ACTION_PLAYBACK_STATE_CHANGED = "ACTION_PLAYBACK_STATE_CHANGED";
    public static final String ACTION_HIDE_MINI_PLAYER = "ACTION_HIDE_MINI_PLAYER";
    public static final String ACTION_SHUFFLE_STATE_CHANGED = "ACTION_SHUFFLE_STATE_CHANGED";
    public static final String ACTION_REPEAT_STATE_CHANGED = "ACTION_REPEAT_STATE_CHANGED";

    public static final int REPEAT_OFF = 0;
    public static final int REPEAT_ALL = 1;
    public static final int REPEAT_ONE = 2;

    private MediaPlayer mediaPlayer;
    private MediaSessionCompat mediaSession;
    private AudioManager audioManager;
    private AudioFocusRequest audioFocusRequest;
    private NotificationManager notificationManager;

    private MusicItem currentSong;
    private boolean isPlaying = false;
    private boolean isPrepared = false;
    private boolean isServiceDestroyed = false;
    private Handler handler = new Handler(Looper.getMainLooper());

    private List<MusicItem> playlist = new ArrayList<>();
    private List<MusicItem> originalPlaylist = new ArrayList<>();
    private int currentIndex = -1;

    private boolean isShuffleEnabled = false;
    private int repeatMode = REPEAT_OFF;
    private Random random = new Random();

    public class MusicBinder extends Binder {
        public MusicService getService() {
            return MusicService.this;
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();

        audioManager = (AudioManager) getSystemService(AUDIO_SERVICE);
        notificationManager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);

        createNotificationChannel();
        initializeMediaPlayer();
        initializeMediaSession();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "Music Playback",
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("Controls for music playback");
            channel.setShowBadge(false);
            channel.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
            notificationManager.createNotificationChannel(channel);
        }
    }

    private void initializeMediaPlayer() {
        mediaPlayer = new MediaPlayer();
        mediaPlayer.setOnPreparedListener(this);
        mediaPlayer.setOnCompletionListener(this);
        mediaPlayer.setOnErrorListener(this);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            AudioAttributes audioAttributes = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build();
            mediaPlayer.setAudioAttributes(audioAttributes);
        } else {
            mediaPlayer.setAudioStreamType(AudioManager.STREAM_MUSIC);
        }
    }

    private void initializeMediaSession() {
        mediaSession = new MediaSessionCompat(this, TAG);
        mediaSession.setFlags(
                MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS |
                        MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS
        );

        mediaSession.setCallback(new MediaSessionCompat.Callback() {
            @Override
            public void onPlay() {
                resumeMusic();
            }

            @Override
            public void onPause() {
                pauseMusic();
            }

            @Override
            public void onStop() {
                stopMusic();
            }

            @Override
            public void onSkipToNext() {
                playNext();
            }

            @Override
            public void onSkipToPrevious() {
                playPrevious();
            }

            @Override
            public void onSeekTo(long pos) {
                seekTo((int) pos);
            }

            @Override
            public void onSetShuffleMode(int shuffleMode) {
                setShuffleMode(shuffleMode == PlaybackStateCompat.SHUFFLE_MODE_ALL);
            }

            @Override
            public void onSetRepeatMode(int repeatMode) {
                switch (repeatMode) {
                    case PlaybackStateCompat.REPEAT_MODE_NONE:
                        setRepeatMode(REPEAT_OFF);
                        break;
                    case PlaybackStateCompat.REPEAT_MODE_ALL:
                        setRepeatMode(REPEAT_ALL);
                        break;
                    case PlaybackStateCompat.REPEAT_MODE_ONE:
                        setRepeatMode(REPEAT_ONE);
                        break;
                }
            }
        });

        mediaSession.setActive(true);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        MediaButtonReceiver.handleIntent(mediaSession, intent);

        if (isServiceDestroyed) {
            return START_NOT_STICKY;
        }

        try {
            if (intent != null && intent.getAction() != null) {
                String action = intent.getAction();

                switch (action) {
                    case ACTION_PLAY:
                        MusicItem musicItem = intent.getParcelableExtra("music_item");
                        if (musicItem != null) {
                            playMusic(musicItem);
                        } else {
                            resumeMusic();
                        }
                        break;
                    case ACTION_PAUSE:
                        pauseMusic();
                        break;
                    case ACTION_TOGGLE_PLAY_PAUSE:
                        togglePlayPause();
                        break;
                    case ACTION_NEXT:
                        playNext();
                        break;
                    case ACTION_PREVIOUS:
                        playPrevious();
                        break;
                    case ACTION_STOP:
                        stopMusic();
                        break;
                    case ACTION_SEEK:
                        int seekPosition = intent.getIntExtra("seek_position", 0);
                        seekTo(seekPosition);
                        break;
                    case ACTION_REQUEST_STATE:
                        broadcastCurrentState();
                        break;
                    case ACTION_TOGGLE_SHUFFLE:
                        toggleShuffle();
                        break;
                    case ACTION_TOGGLE_REPEAT:
                        toggleRepeat();
                        break;
                    case ACTION_SET_PLAYLIST:
                        ArrayList<MusicItem> newPlaylist = intent.getParcelableArrayListExtra("playlist");
                        int startIndex = intent.getIntExtra("start_index", 0);
                        if (newPlaylist != null) {
                            setPlaylist(newPlaylist, startIndex);
                        }
                        break;
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error in onStartCommand: " + e.getMessage(), e);
        }

        return START_STICKY;
    }

    private boolean requestAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build())
                    .setAcceptsDelayedFocusGain(true)
                    .setOnAudioFocusChangeListener(this)
                    .build();
            return audioManager.requestAudioFocus(audioFocusRequest) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
        } else {
            return audioManager.requestAudioFocus(this, AudioManager.STREAM_MUSIC,
                    AudioManager.AUDIOFOCUS_GAIN) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
        }
    }

    private void abandonAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && audioFocusRequest != null) {
            audioManager.abandonAudioFocusRequest(audioFocusRequest);
        } else {
            audioManager.abandonAudioFocus(this);
        }
    }

    private void setPlaylist(List<MusicItem> newPlaylist, int startIndex) {
        originalPlaylist.clear();
        originalPlaylist.addAll(newPlaylist);

        playlist.clear();
        playlist.addAll(newPlaylist);

        if (isShuffleEnabled) {
            shufflePlaylist(startIndex);
        } else {
            currentIndex = Math.max(0, Math.min(startIndex, playlist.size() - 1));
        }
    }

    private void playMusic(MusicItem musicItem) {
        if (isServiceDestroyed || musicItem == null) {
            return;
        }

        if (!requestAudioFocus()) {
            Log.w(TAG, "Could not gain audio focus");
            return;
        }

        try {
            if (mediaPlayer != null) {
                try {
                    if (mediaPlayer.isPlaying()) {
                        mediaPlayer.stop();
                    }
                    mediaPlayer.release();
                } catch (Exception e) {
                    Log.e(TAG, "Error stopping previous media player: " + e.getMessage(), e);
                }
                mediaPlayer = null;
            }

            currentSong = musicItem;

            if (playlist.isEmpty()) {
                ArrayList<MusicItem> singleSongPlaylist = new ArrayList<>();
                singleSongPlaylist.add(musicItem);
                setPlaylist(singleSongPlaylist, 0);
            } else {
                updateCurrentIndex(musicItem);

                if (currentIndex == -1) {
                    playlist.add(musicItem);
                    originalPlaylist.add(musicItem);
                    currentIndex = playlist.size() - 1;
                }
            }

            initializeMediaPlayer();
            mediaPlayer.setDataSource(this, Uri.parse(musicItem.getPath()));
            mediaPlayer.prepareAsync();

        } catch (IOException e) {
            Log.e(TAG, "IOException in playMusic: " + e.getMessage(), e);
        } catch (Exception e) {
            Log.e(TAG, "Unexpected error in playMusic: " + e.getMessage(), e);
        }
    }

    private void updateCurrentIndex(MusicItem musicItem) {
        for (int i = 0; i < playlist.size(); i++) {
            if (playlist.get(i).getId() == musicItem.getId()) {
                currentIndex = i;
                break;
            }
        }
    }

    private void resumeMusic() {
        if (isServiceDestroyed) {
            return;
        }

        if (!requestAudioFocus()) {
            Log.w(TAG, "Could not gain audio focus");
            return;
        }

        try {
            if (mediaPlayer != null && isPrepared && !mediaPlayer.isPlaying()) {
                mediaPlayer.start();
                isPlaying = true;
                updatePlaybackState();
                showNotification();
                broadcastPlaybackState();
            }
        } catch (Exception e) {
            Log.e(TAG, "Error in resumeMusic: " + e.getMessage(), e);
        }
    }

    private void pauseMusic() {
        if (isServiceDestroyed) {
            return;
        }

        try {
            if (mediaPlayer != null && isPlaying && mediaPlayer.isPlaying()) {
                mediaPlayer.pause();
                isPlaying = false;
                updatePlaybackState();
                showNotification();
                broadcastPlaybackState();
            }
        } catch (Exception e) {
            Log.e(TAG, "Error in pauseMusic: " + e.getMessage(), e);
        }
    }

    private void stopMusic() {
        if (isServiceDestroyed) {
            return;
        }

        try {
            if (mediaPlayer != null) {
                try {
                    if (mediaPlayer.isPlaying()) {
                        mediaPlayer.stop();
                    }
                    mediaPlayer.release();
                } catch (Exception e) {
                    Log.e(TAG, "Error releasing media player: " + e.getMessage(), e);
                }
                mediaPlayer = null;
            }

            isPlaying = false;
            isPrepared = false;
            currentSong = null;
            currentIndex = -1;

            abandonAudioFocus();
            updatePlaybackState();
            stopForeground(true);
            sendHideMiniPlayer();
            stopSelf();
        } catch (Exception e) {
            Log.e(TAG, "Error in stopMusic: " + e.getMessage(), e);
        }
    }

    private void togglePlayPause() {
        if (isPlaying) {
            pauseMusic();
        } else {
            resumeMusic();
        }
    }

    private void playNext() {
        if (isServiceDestroyed || playlist.isEmpty()) {
            return;
        }

        try {
            MusicItem nextSong = getNextSong();
            if (nextSong != null) {
                playMusic(nextSong);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error in playNext: " + e.getMessage(), e);
        }
    }

    private void playPrevious() {
        if (isServiceDestroyed || playlist.isEmpty()) {
            return;
        }

        try {
            if (mediaPlayer != null && isPlaying) {
                try {
                    int currentPosition = mediaPlayer.getCurrentPosition();

                    if (currentPosition > 3000) {
                        mediaPlayer.seekTo(0);
                        updatePlaybackState();
                        showNotification();
                        return;
                    }
                } catch (IllegalStateException e) {
                }
            }

            MusicItem previousSong = getPreviousSong();
            if (previousSong != null) {
                playMusic(previousSong);
            } else {
                if (mediaPlayer != null) {
                    try {
                        mediaPlayer.seekTo(0);
                        updatePlaybackState();
                        showNotification();
                    } catch (Exception e) {
                        Log.e(TAG, "Error seeking to start: " + e.getMessage(), e);
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error in playPrevious: " + e.getMessage(), e);
        }
    }

    private void seekTo(int position) {
        if (mediaPlayer != null && isPrepared) {
            try {
                mediaPlayer.seekTo(position);
                updatePlaybackState();
                showNotification();
                Log.d(TAG, "Seeked to position: " + position);
            } catch (Exception e) {
                Log.e(TAG, "Error seeking: " + e.getMessage(), e);
            }
        }
    }

    private MusicItem getNextSong() {
        if (playlist.isEmpty()) {
            return null;
        }

        if (currentIndex < 0 || currentIndex >= playlist.size()) {
            currentIndex = 0;
            return playlist.get(currentIndex);
        }

        int nextIndex = currentIndex + 1;

        if (nextIndex >= playlist.size()) {
            if (repeatMode == REPEAT_ALL) {
                nextIndex = 0;
            } else {
                return null;
            }
        }

        if (nextIndex >= 0 && nextIndex < playlist.size()) {
            currentIndex = nextIndex;
            return playlist.get(currentIndex);
        } else {
            return null;
        }
    }

    private MusicItem getPreviousSong() {
        if (playlist.isEmpty()) {
            return null;
        }

        if (currentIndex < 0 || currentIndex >= playlist.size()) {
            currentIndex = playlist.size() - 1;
            return playlist.get(currentIndex);
        }

        int prevIndex = currentIndex - 1;

        if (prevIndex < 0) {
            if (repeatMode == REPEAT_ALL) {
                prevIndex = playlist.size() - 1;
            } else {
                return null;
            }
        }

        if (prevIndex >= 0 && prevIndex < playlist.size()) {
            currentIndex = prevIndex;
            return playlist.get(currentIndex);
        } else {
            return null;
        }
    }

    private void handleSongCompletion() {
        switch (repeatMode) {
            case REPEAT_ONE:
                if (currentSong != null) {
                    playMusic(currentSong);
                }
                break;
            case REPEAT_ALL:
                playNext();
                break;
            case REPEAT_OFF:
            default:
                MusicItem nextSong = getNextSong();
                if (nextSong != null) {
                    playMusic(nextSong);
                } else {
                    pauseMusic();
                }
                break;
        }
    }

    private void toggleShuffle() {
        isShuffleEnabled = !isShuffleEnabled;
        setShuffleMode(isShuffleEnabled);
    }

    private void setShuffleMode(boolean enabled) {
        isShuffleEnabled = enabled;

        if (isShuffleEnabled) {
            shufflePlaylist(currentIndex);
        } else {
            restoreOriginalOrder();
        }

        updatePlaybackState();
        broadcastShuffleState();
    }

    private void toggleRepeat() {
        repeatMode = (repeatMode + 1) % 3;
        setRepeatMode(repeatMode);
    }

    private void setRepeatMode(int mode) {
        repeatMode = mode;
        updatePlaybackState();
        broadcastRepeatState();
    }

    private void shufflePlaylist(int currentPlayingIndex) {
        if (playlist.isEmpty()) return;

        MusicItem currentPlayingSong = null;
        if (currentPlayingIndex >= 0 && currentPlayingIndex < playlist.size()) {
            currentPlayingSong = playlist.get(currentPlayingIndex);
        }

        playlist.clear();
        playlist.addAll(originalPlaylist);

        if (currentPlayingSong != null) {
            playlist.remove(currentPlayingSong);
        }

        Collections.shuffle(playlist, random);

        if (currentPlayingSong != null) {
            playlist.add(0, currentPlayingSong);
            currentIndex = 0;
        } else {
            currentIndex = -1;
        }
    }

    private void restoreOriginalOrder() {
        MusicItem currentPlayingSong = null;
        if (currentIndex >= 0 && currentIndex < playlist.size()) {
            currentPlayingSong = playlist.get(currentIndex);
        }

        playlist.clear();
        playlist.addAll(originalPlaylist);

        currentIndex = -1;
        if (currentPlayingSong != null) {
            for (int i = 0; i < playlist.size(); i++) {
                if (playlist.get(i).getId() == currentPlayingSong.getId()) {
                    currentIndex = i;
                    break;
                }
            }
        }
    }

    private void updatePlaybackState() {
        int state = isPlaying ? PlaybackStateCompat.STATE_PLAYING :
                isPrepared ? PlaybackStateCompat.STATE_PAUSED : PlaybackStateCompat.STATE_STOPPED;
        long position = isPrepared && mediaPlayer != null ? mediaPlayer.getCurrentPosition() : 0;

        PlaybackStateCompat playbackState = new PlaybackStateCompat.Builder()
                .setActions(
                        PlaybackStateCompat.ACTION_PLAY |
                                PlaybackStateCompat.ACTION_PAUSE |
                                PlaybackStateCompat.ACTION_STOP |
                                PlaybackStateCompat.ACTION_SKIP_TO_NEXT |
                                PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS |
                                PlaybackStateCompat.ACTION_SEEK_TO |
                                PlaybackStateCompat.ACTION_SET_SHUFFLE_MODE |
                                PlaybackStateCompat.ACTION_SET_REPEAT_MODE
                )
                .setState(state, position, 1.0f)
                .build();

        mediaSession.setPlaybackState(playbackState);

        updateMediaSessionModes();
    }

    private void updateMediaSessionModes() {
        int shuffleMode = isShuffleEnabled ?
                PlaybackStateCompat.SHUFFLE_MODE_ALL : PlaybackStateCompat.SHUFFLE_MODE_NONE;
        mediaSession.setShuffleMode(shuffleMode);

        int sessionRepeatMode;
        switch (repeatMode) {
            case REPEAT_OFF:
                sessionRepeatMode = PlaybackStateCompat.REPEAT_MODE_NONE;
                break;
            case REPEAT_ALL:
                sessionRepeatMode = PlaybackStateCompat.REPEAT_MODE_ALL;
                break;
            case REPEAT_ONE:
                sessionRepeatMode = PlaybackStateCompat.REPEAT_MODE_ONE;
                break;
            default:
                sessionRepeatMode = PlaybackStateCompat.REPEAT_MODE_NONE;
                break;
        }
        mediaSession.setRepeatMode(sessionRepeatMode);
    }

    private void updateMediaMetadata() {
        if (currentSong == null) return;

        MediaMetadataCompat.Builder builder = new MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, currentSong.getTitle())
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, currentSong.getArtist())
                .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, currentSong.getAlbum());

        if (mediaPlayer != null && isPrepared) {
            builder.putLong(MediaMetadataCompat.METADATA_KEY_DURATION, mediaPlayer.getDuration());
        }

        Bitmap albumArt = getAlbumArt(currentSong);
        if (albumArt != null) {
            builder.putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, albumArt);
            builder.putBitmap(MediaMetadataCompat.METADATA_KEY_ART, albumArt);
        }

        mediaSession.setMetadata(builder.build());
    }

    private void showNotification() {
        if (currentSong == null) return;

        Intent activityIntent = new Intent(this, MainActivity.class);
        PendingIntent activityPendingIntent = PendingIntent.getActivity(
                this, 0, activityIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_outline_music_note_24)
                .setContentTitle(currentSong.getTitle())
                .setContentText(currentSong.getArtist())
                .setSubText(currentSong.getAlbum())
                .setContentIntent(activityPendingIntent)
                .setDeleteIntent(MediaButtonReceiver.buildMediaButtonPendingIntent(
                        this, PlaybackStateCompat.ACTION_STOP))
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setOnlyAlertOnce(true)
                .addAction(R.drawable.ic_baseline_skip_previous_24, "Previous",
                        MediaButtonReceiver.buildMediaButtonPendingIntent(
                                this, PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS))
                .addAction(
                        isPlaying ? R.drawable.ic_baseline_pause_24 : R.drawable.ic_baseline_play_arrow_24,
                        isPlaying ? "Pause" : "Play",
                        MediaButtonReceiver.buildMediaButtonPendingIntent(
                                this, isPlaying ? PlaybackStateCompat.ACTION_PAUSE : PlaybackStateCompat.ACTION_PLAY))
                .addAction(R.drawable.ic_baseline_skip_next_24, "Next",
                        MediaButtonReceiver.buildMediaButtonPendingIntent(
                                this, PlaybackStateCompat.ACTION_SKIP_TO_NEXT))
                .setStyle(new androidx.media.app.NotificationCompat.MediaStyle()
                        .setMediaSession(mediaSession.getSessionToken())
                        .setShowActionsInCompactView(0, 1, 2)
                        .setShowCancelButton(true)
                        .setCancelButtonIntent(MediaButtonReceiver.buildMediaButtonPendingIntent(
                                this, PlaybackStateCompat.ACTION_STOP)));

        Bitmap albumArt = getAlbumArt(currentSong);
        if (albumArt != null) {
            builder.setLargeIcon(albumArt);

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                builder.setColorized(true);
            }
        }

        startForeground(NOTIFICATION_ID, builder.build());
    }

    private Bitmap getAlbumArt(MusicItem musicItem) {
        try {
            MediaMetadataRetriever retriever = new MediaMetadataRetriever();
            retriever.setDataSource(this, Uri.parse(musicItem.getPath()));

            byte[] albumArtBytes = retriever.getEmbeddedPicture();
            retriever.release();

            if (albumArtBytes != null) {
                BitmapFactory.Options options = new BitmapFactory.Options();
                options.inJustDecodeBounds = true;
                BitmapFactory.decodeByteArray(albumArtBytes, 0, albumArtBytes.length, options);

                int targetSize = (int) (256 * getResources().getDisplayMetrics().density);
                options.inSampleSize = calculateInSampleSize(options, targetSize, targetSize);
                options.inJustDecodeBounds = false;

                Bitmap bitmap = BitmapFactory.decodeByteArray(albumArtBytes, 0, albumArtBytes.length, options);

                if (bitmap != null) {
                    int size = Math.min(bitmap.getWidth(), bitmap.getHeight());
                    return Bitmap.createBitmap(bitmap,
                            (bitmap.getWidth() - size) / 2,
                            (bitmap.getHeight() - size) / 2,
                            size, size);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error loading album art: " + e.getMessage(), e);
        }

        return null;
    }

    private int calculateInSampleSize(BitmapFactory.Options options, int reqWidth, int reqHeight) {
        final int height = options.outHeight;
        final int width = options.outWidth;
        int inSampleSize = 1;

        if (height > reqHeight || width > reqWidth) {
            final int halfHeight = height / 2;
            final int halfWidth = width / 2;

            while ((halfHeight / inSampleSize) >= reqHeight
                    && (halfWidth / inSampleSize) >= reqWidth) {
                inSampleSize *= 2;
            }
        }

        return inSampleSize;
    }

    @Override
    public void onPrepared(MediaPlayer mp) {
        isPrepared = true;
        updateMediaMetadata();
        resumeMusic();
        broadcastMusicUpdate();
    }

    @Override
    public void onCompletion(MediaPlayer mp) {
        handleSongCompletion();
    }

    @Override
    public boolean onError(MediaPlayer mp, int what, int extra) {
        Log.e(TAG, "MediaPlayer error: " + what + ", " + extra);
        isPrepared = false;
        return false;
    }

    @Override
    public void onAudioFocusChange(int focusChange) {
        switch (focusChange) {
            case AudioManager.AUDIOFOCUS_GAIN:
                if (!isPlaying && isPrepared) {
                    resumeMusic();
                }
                if (mediaPlayer != null) {
                    mediaPlayer.setVolume(1.0f, 1.0f);
                }
                break;
            case AudioManager.AUDIOFOCUS_LOSS:
                stopMusic();
                break;
            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT:
                if (isPlaying) {
                    pauseMusic();
                }
                break;
            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK:
                if (mediaPlayer != null) {
                    mediaPlayer.setVolume(0.3f, 0.3f);
                }
                break;
        }
    }

    private void broadcastMusicUpdate() {
        if (isServiceDestroyed || currentSong == null) {
            return;
        }

        try {
            Intent intent = new Intent(ACTION_MUSIC_UPDATED);
            intent.setPackage(getPackageName());
            intent.putExtra("music_item", currentSong);
            intent.putExtra("is_playing", isPlaying);
            sendBroadcast(intent);
        } catch (Exception e) {
            Log.e(TAG, "Error broadcasting music update: " + e.getMessage(), e);
        }
    }

    private void broadcastPlaybackState() {
        if (isServiceDestroyed) {
            return;
        }

        try {
            Intent intent = new Intent(ACTION_PLAYBACK_STATE_CHANGED);
            intent.setPackage(getPackageName());
            intent.putExtra("is_playing", isPlaying);
            sendBroadcast(intent);
        } catch (Exception e) {
            Log.e(TAG, "Error broadcasting playback state: " + e.getMessage(), e);
        }
    }

    private void broadcastShuffleState() {
        if (isServiceDestroyed) {
            return;
        }

        try {
            Intent intent = new Intent(ACTION_SHUFFLE_STATE_CHANGED);
            intent.setPackage(getPackageName());
            intent.putExtra("is_shuffle_enabled", isShuffleEnabled);
            sendBroadcast(intent);
        } catch (Exception e) {
            Log.e(TAG, "Error broadcasting shuffle state: " + e.getMessage(), e);
        }
    }

    private void broadcastRepeatState() {
        if (isServiceDestroyed) {
            return;
        }

        try {
            Intent intent = new Intent(ACTION_REPEAT_STATE_CHANGED);
            intent.setPackage(getPackageName());
            intent.putExtra("repeat_mode", repeatMode);
            sendBroadcast(intent);
        } catch (Exception e) {
            Log.e(TAG, "Error broadcasting repeat state: " + e.getMessage(), e);
        }
    }

    private void sendHideMiniPlayer() {
        Intent intent = new Intent(ACTION_HIDE_MINI_PLAYER);
        intent.setPackage(getPackageName());
        sendBroadcast(intent);
    }

    private void broadcastCurrentState() {
        if (isServiceDestroyed) {
            return;
        }

        try {
            if (currentSong != null) {
                broadcastMusicUpdate();
                broadcastShuffleState();
                broadcastRepeatState();
            }
        } catch (Exception e) {
            Log.e(TAG, "Error broadcasting current state: " + e.getMessage(), e);
        }
    }

    public MediaPlayer getMediaPlayer() {
        return mediaPlayer;
    }

    public MusicItem getCurrentSong() {
        return currentSong;
    }

    public boolean isPlaying() {
        return isPlaying;
    }

    public boolean isShuffleEnabled() {
        return isShuffleEnabled;
    }

    public int getRepeatMode() {
        return repeatMode;
    }

    public List<MusicItem> getPlaylist() {
        return new ArrayList<>(playlist);
    }

    public int getCurrentIndex() {
        return currentIndex;
    }

    public List<MusicItem> getUpcomingQueue() {
        if (playlist.isEmpty() || currentIndex < 0 || currentIndex >= playlist.size()) {
            return new ArrayList<>();
        }

        List<MusicItem> upcomingQueue = new ArrayList<>();
        for (int i = currentIndex + 1; i < playlist.size(); i++) {
            upcomingQueue.add(playlist.get(i));
        }

        return upcomingQueue;
    }

    public void reshufflePlaylist() {
        if (isShuffleEnabled && !playlist.isEmpty()) {
            shufflePlaylist(currentIndex);
        }
    }

    /**
     * Move a queue item from one position to another
     * @param fromPosition Original position of the item (in upcoming queue, not absolute playlist position)
     * @param toPosition Target position for the item (in upcoming queue, not absolute playlist position)
     */
    public void moveQueueItem(int fromPosition, int toPosition) {
        if (playlist == null || fromPosition < 0 || toPosition < 0) {
            return;
        }

        int absoluteFromPos = currentIndex + 1 + fromPosition;
        int absoluteToPos = currentIndex + 1 + toPosition;

        if (absoluteFromPos >= playlist.size() || absoluteToPos >= playlist.size() ||
                absoluteFromPos <= currentIndex || absoluteToPos <= currentIndex) {
            return;
        }

        MusicItem item = playlist.remove(absoluteFromPos);
        playlist.add(absoluteToPos, item);

        if (!isShuffleEnabled && !originalPlaylist.isEmpty()) {
            for (int i = 0; i < originalPlaylist.size(); i++) {
                if (originalPlaylist.get(i).getId() == item.getId()) {
                    originalPlaylist.remove(i);
                    int originalInsertPos = Math.min(absoluteToPos, originalPlaylist.size());
                    originalPlaylist.add(originalInsertPos, item);
                    break;
                }
            }
        }

        Log.d(TAG, "Queue item moved from position " + fromPosition + " to " + toPosition +
                " (absolute: " + absoluteFromPos + " to " + absoluteToPos + ")");
    }

    /**
     * Remove an item from the queue
     * @param position Position in the upcoming queue (not absolute playlist position)
     */
    public void removeQueueItem(int position) {
        if (playlist == null || position < 0) {
            return;
        }

        int absolutePos = currentIndex + 1 + position;

        if (absolutePos >= playlist.size() || absolutePos <= currentIndex) {
            return;
        }

        MusicItem itemToRemove = playlist.get(absolutePos);

        playlist.remove(absolutePos);

        if (!originalPlaylist.isEmpty()) {
            for (int i = 0; i < originalPlaylist.size(); i++) {
                if (originalPlaylist.get(i).getId() == itemToRemove.getId()) {
                    originalPlaylist.remove(i);
                    break;
                }
            }
        }

        Log.d(TAG, "Queue item removed at position " + position +
                " (absolute: " + absolutePos + ") - " + itemToRemove.getTitle());
    }

    @Override
    public IBinder onBind(Intent intent) {
        return new MusicBinder();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();

        isServiceDestroyed = true;

        try {
            if (mediaPlayer != null) {
                try {
                    if (mediaPlayer.isPlaying()) {
                        mediaPlayer.stop();
                    }
                    mediaPlayer.release();
                } catch (Exception e) {
                    Log.e(TAG, "Error releasing media player in onDestroy: " + e.getMessage(), e);
                } finally {
                    mediaPlayer = null;
                }
            }

            if (mediaSession != null) {
                mediaSession.release();
                mediaSession = null;
            }

            if (handler != null) {
                handler.removeCallbacksAndMessages(null);
            }

            abandonAudioFocus();

            try {
                stopForeground(true);
                if (notificationManager != null) {
                    notificationManager.cancel(NOTIFICATION_ID);
                }
            } catch (Exception e) {
                Log.e(TAG, "Error stopping foreground in onDestroy: " + e.getMessage(), e);
            }

            currentSong = null;
            isPlaying = false;
            isPrepared = false;
            playlist.clear();
            originalPlaylist.clear();
            currentIndex = -1;

        } catch (Exception e) {
            Log.e(TAG, "Error in onDestroy: " + e.getMessage(), e);
        }
    }
}
package com.alfahrel.melody.ui.pages.nowplaying;

import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.media.MediaPlayer;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.renderscript.Allocation;
import android.renderscript.Element;
import android.renderscript.RenderScript;
import android.renderscript.ScriptIntrinsicBlur;
import android.util.Log;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.drawable.DrawableCompat;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.request.target.CustomTarget;
import com.bumptech.glide.request.transition.Transition;
import com.alfahrel.melody.R;
import com.alfahrel.melody.databinding.ActivityNowPlayingBinding;
import com.alfahrel.melody.service.MusicService;
import com.alfahrel.melody.ui.collection.Collection;
import com.alfahrel.melody.ui.collection.CollectionManager;
import com.alfahrel.melody.ui.music.MusicItem;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.color.DynamicColors;
import com.google.android.material.textfield.TextInputEditText;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

public class NowPlayingActivity extends AppCompatActivity {

    private static final String LYRICS_PREFS = "LyricsPreferences";

    private static final String ACTION_COLLECTION_CHANGED = "com.alfahrel.melody.COLLECTION_CHANGED";
    private static final String ACTION_COLLECTION_CREATED = "com.alfahrel.melody.COLLECTION_CREATED";
    private static final String ACTION_SONG_ADDED_TO_COLLECTION = "com.alfahrel.melody.SONG_ADDED_TO_COLLECTION";
    private static final String ACTION_SONG_REMOVED_FROM_COLLECTION = "com.alfahrel.melody.SONG_REMOVED_FROM_COLLECTION";

    private ActivityNowPlayingBinding binding;
    private MusicService musicService;
    private boolean serviceBound = false;
    private MusicItem currentSong;
    private Handler handler = new Handler(Looper.getMainLooper());
    private Runnable updateSeekBar;
    private boolean isPlaying = false;
    private boolean isDraggingSeekBar = false;

    private boolean isShuffleEnabled = false;
    private int repeatMode = MusicService.REPEAT_OFF;

    private ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            MusicService.MusicBinder binder = (MusicService.MusicBinder) service;
            musicService = binder.getService();
            serviceBound = true;

            updateUIFromService();
            startSeekBarUpdates();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            serviceBound = false;
            musicService = null;
        }
    };

    private BroadcastReceiver musicUpdateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (action != null) {
                switch (action) {
                    case MusicService.ACTION_PLAYBACK_STATE_CHANGED:
                        boolean playing = intent.getBooleanExtra("is_playing", false);
                        isPlaying = playing;
                        updatePlayPauseButton();
                        if (playing) {
                            startSeekBarUpdates();
                        } else {
                            stopSeekBarUpdates();
                        }
                        break;
                    case MusicService.ACTION_MUSIC_UPDATED:
                        MusicItem updatedSong = intent.getParcelableExtra("music_item");
                        if (updatedSong != null) {
                            currentSong = updatedSong;
                            setupNowPlaying();
                        }
                        break;
                    case MusicService.ACTION_SHUFFLE_STATE_CHANGED:
                        isShuffleEnabled = intent.getBooleanExtra("is_shuffle_enabled", false);
                        updateShuffleButton();
                        break;
                    case MusicService.ACTION_REPEAT_STATE_CHANGED:
                        repeatMode = intent.getIntExtra("repeat_mode", MusicService.REPEAT_OFF);
                        updateRepeatButton();
                        break;
                }
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        DynamicColors.applyToActivityIfAvailable(this);

        super.onCreate(savedInstanceState);
        binding = ActivityNowPlayingBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        Intent intent = getIntent();
        if (intent.hasExtra("music_item")) {
            currentSong = intent.getParcelableExtra("music_item");
            setupNowPlaying();
        } else {
            finish();
            return;
        }

        setupClickListeners();
        setupProgressIndicator();
        bindToMusicService();
        registerMusicUpdateReceiver();
    }

    private void bindToMusicService() {
        Intent serviceIntent = new Intent(this, MusicService.class);
        bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE);
    }

    private void registerMusicUpdateReceiver() {
        try {
            IntentFilter filter = new IntentFilter();
            filter.addAction(MusicService.ACTION_PLAYBACK_STATE_CHANGED);
            filter.addAction(MusicService.ACTION_MUSIC_UPDATED);
            filter.addAction(MusicService.ACTION_SHUFFLE_STATE_CHANGED);
            filter.addAction(MusicService.ACTION_REPEAT_STATE_CHANGED);

            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(musicUpdateReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
            } else {
                registerReceiver(musicUpdateReceiver, filter);
            }

        } catch (Exception e) {
            Log.e("NowPlayingActivity", "Error registering broadcast receiver: " + e.getMessage(), e);
        }
    }

    /**
     * Broadcast collection changes to update CollectionFragment
     */
    private void broadcastCollectionChange(String action) {
        try {
            Intent intent = new Intent(action);
            intent.setPackage(getPackageName());
            sendBroadcast(intent);
            Log.d("NowPlayingActivity", "Broadcast sent: " + action);
        } catch (Exception e) {
            Log.e("NowPlayingActivity", "Error broadcasting collection change: " + e.getMessage(), e);
        }
    }

    private void updateUIFromService() {
        if (musicService != null) {
            MusicItem serviceSong = musicService.getCurrentSong();
            if (serviceSong != null) {
                if (currentSong == null || serviceSong.getId() != currentSong.getId()) {
                    currentSong = serviceSong;
                    setupNowPlaying();
                }
                isPlaying = musicService.isPlaying();
                updatePlayPauseButton();
                updateProgressFromService();
            }

            isShuffleEnabled = musicService.isShuffleEnabled();
            repeatMode = musicService.getRepeatMode();
            updateShuffleButton();
            updateRepeatButton();
        }
    }

    private void updateProgressFromService() {
        if (musicService != null && musicService.getMediaPlayer() != null) {
            try {
                MediaPlayer mediaPlayer = musicService.getMediaPlayer();
                int currentPosition = mediaPlayer.getCurrentPosition();
                int duration = mediaPlayer.getDuration();

                if (duration > 0) {
                    int progress = (int) (((float) currentPosition / duration) * 100);
                    binding.seekBar.setProgress(progress);
                    binding.currentTime.setText(formatDuration(currentPosition));
                }
            } catch (IllegalStateException e) {
                Log.e("NowPlayingActivity", "Error getting playback position: " + e.getMessage());
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private void setupProgressIndicator() {
        binding.seekBar.setIndeterminate(false);
        binding.seekBar.setMax(100);

        binding.seekBar.setOnTouchListener((v, event) -> {
            int leftPadding = v.getPaddingLeft();
            int rightPadding = v.getPaddingRight();
            int usableWidth = v.getWidth() - leftPadding - rightPadding;
            float adjustedX = event.getX() - leftPadding;

            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    isDraggingSeekBar = true;
                    handleProgressTouch(adjustedX, usableWidth);
                    return true;

                case MotionEvent.ACTION_MOVE:
                    if (isDraggingSeekBar) {
                        handleProgressTouch(adjustedX, usableWidth);
                    }
                    return true;

                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    if (isDraggingSeekBar) {
                        handleProgressTouch(adjustedX, usableWidth);
                        seekToPosition(adjustedX, usableWidth);
                        isDraggingSeekBar = false;
                    }
                    return true;
            }
            return false;
        });
    }

    private void setupNowPlaying() {
        if (currentSong == null) return;

        String name = currentSong.getTitle();
        String band = currentSong.getArtist();
        binding.songTitle.setText(name + " - " + band);
        binding.songTitle.setSelected(true);
        binding.totalDuration.setText(formatDuration(currentSong.getDuration()));

        if (musicService == null) {
            binding.currentTime.setText("0:00");
            binding.seekBar.setProgress(0);
        }

        loadAlbumArt();
    }

    private void loadAlbumArt() {
        Glide.with(this)
                .asBitmap()
                .load(currentSong.getAlbumArtUri())
                .placeholder(R.drawable.ic_outline_music_note_24)
                .error(R.drawable.ic_outline_music_note_24)
                .into(new CustomTarget<Bitmap>() {
                    @Override
                    public void onResourceReady(Bitmap bitmap, Transition<? super Bitmap> transition) {
                        binding.albumArt.setImageBitmap(bitmap);
                        applyBlurredBackground(bitmap);
                    }

                    @Override
                    public void onLoadCleared(Drawable placeholder) {
                        binding.albumArt.setImageDrawable(placeholder);
                        binding.blurredBackground.setImageResource(R.drawable.ic_outline_music_note_24);
                    }
                });
    }

    private void applyBlurredBackground(Bitmap originalBitmap) {
        try {
            Bitmap blurredBitmap = blurBitmap(originalBitmap, 25f);
            binding.blurredBackground.setImageBitmap(blurredBitmap);
        } catch (Exception e) {
            Log.e("NowPlayingActivity", "Error applying blur effect: " + e.getMessage(), e);
            binding.blurredBackground.setImageBitmap(originalBitmap);
            binding.blurredBackground.setAlpha(0.3f);
        }
    }

    private Bitmap blurBitmap(Bitmap bitmap, float radius) {
        Bitmap outputBitmap = Bitmap.createBitmap(bitmap.getWidth(), bitmap.getHeight(), bitmap.getConfig());
        RenderScript rs = RenderScript.create(this);

        try {
            ScriptIntrinsicBlur blurScript = ScriptIntrinsicBlur.create(rs, Element.U8_4(rs));
            Allocation inputAllocation = Allocation.createFromBitmap(rs, bitmap);
            Allocation outputAllocation = Allocation.createFromBitmap(rs, outputBitmap);

            blurScript.setRadius(Math.min(25f, Math.max(1f, radius)));
            blurScript.setInput(inputAllocation);
            blurScript.forEach(outputAllocation);
            outputAllocation.copyTo(outputBitmap);

            inputAllocation.destroy();
            outputAllocation.destroy();
            blurScript.destroy();
        } catch (Exception e) {
            Log.e("NowPlayingActivity", "RenderScript blur failed: " + e.getMessage(), e);
            return bitmap;
        } finally {
            rs.destroy();
        }

        return outputBitmap;
    }

    private void setupClickListeners() {
        binding.playPauseButton.setOnClickListener(v -> togglePlayPause());
        binding.previousButton.setOnClickListener(v -> playPrevious());
        binding.nextButton.setOnClickListener(v -> playNext());
        binding.shuffleButton.setOnClickListener(v -> toggleShuffle());
        binding.repeatButton.setOnClickListener(v -> toggleRepeat());
        binding.addToCollection.setOnClickListener(v -> showAddToCollectionBottomSheet());
        binding.queueButton.setOnClickListener(v -> showQueueBottomSheet());
    }

    private void showAddToCollectionBottomSheet() {
        if (currentSong == null) {
            Toast.makeText(this, "No song is currently playing", Toast.LENGTH_SHORT).show();
            return;
        }

        CollectionManager collectionManager = new CollectionManager(this);
        List<Collection> collections = collectionManager.getAllCollections();

        BottomSheetDialog bottomSheetDialog = new BottomSheetDialog(this);
        View view = LayoutInflater.from(this).inflate(R.layout.bottom_sheet_add_to_collection, null);

        RecyclerView collectionsRecyclerView = view.findViewById(R.id.collectionsRecyclerView);
        android.widget.TextView emptyCollectionsText = view.findViewById(R.id.emptyCollectionsText);
        MaterialButton createNewCollectionButton = view.findViewById(R.id.createNewCollectionButton);

        collectionsRecyclerView.setLayoutManager(new LinearLayoutManager(this));

        if (collections.isEmpty()) {
            collectionsRecyclerView.setVisibility(View.GONE);
            emptyCollectionsText.setVisibility(View.VISIBLE);
        } else {
            collectionsRecyclerView.setVisibility(View.VISIBLE);
            emptyCollectionsText.setVisibility(View.GONE);

            AddToCollectionAdapter adapter = new AddToCollectionAdapter(
                    collections,
                    currentSong.getId(),
                    collectionManager,
                    collection -> {
                        boolean added = collectionManager.addSongToCollection(collection.getId(), currentSong.getId());
                        if (added) {
                            Toast.makeText(this, "Added to " + collection.getName(), Toast.LENGTH_SHORT).show();
                            broadcastCollectionChange(ACTION_SONG_ADDED_TO_COLLECTION);
                            bottomSheetDialog.dismiss();
                        } else {
                            Toast.makeText(this, "Song already in " + collection.getName(), Toast.LENGTH_SHORT).show();
                        }
                    }
            );
            collectionsRecyclerView.setAdapter(adapter);
        }

        createNewCollectionButton.setOnClickListener(v -> {
            bottomSheetDialog.dismiss();
            showCreateCollectionDialog();
        });

        bottomSheetDialog.setContentView(view);
        bottomSheetDialog.show();
    }

    private void showCreateCollectionDialog() {
        View dialogView = LayoutInflater.from(this)
                .inflate(R.layout.dialog_add_collection, null);

        TextInputEditText editTextName = dialogView.findViewById(R.id.editTextCollectionName);

        new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle("New Collection")
                .setView(dialogView)
                .setPositiveButton("Create", (dialog, which) -> {
                    String collectionName = editTextName.getText().toString().trim();
                    if (!collectionName.isEmpty()) {
                        createCollectionAndAddSong(collectionName);
                    } else {
                        Toast.makeText(this, "Collection name cannot be empty",
                                Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void createCollectionAndAddSong(String collectionName) {
        if (currentSong == null) return;

        CollectionManager collectionManager = new CollectionManager(this);

        List<Collection> existingCollections = collectionManager.getAllCollections();
        for (Collection collection : existingCollections) {
            if (collection.getName().equalsIgnoreCase(collectionName)) {
                Toast.makeText(this, "Collection already exists", Toast.LENGTH_SHORT).show();
                return;
            }
        }

        Collection newCollection = collectionManager.createCollection(collectionName);

        boolean added = collectionManager.addSongToCollection(newCollection.getId(), currentSong.getId());

        if (added) {
            Toast.makeText(this, "Created \"" + collectionName + "\" and added song",
                    Toast.LENGTH_SHORT).show();
            broadcastCollectionChange(ACTION_COLLECTION_CREATED);
        } else {
            Toast.makeText(this, "Collection created", Toast.LENGTH_SHORT).show();
            broadcastCollectionChange(ACTION_COLLECTION_CREATED);
        }
    }
    private List<MusicItem> getUpcomingQueue() {
        if (musicService != null) {
            return musicService.getUpcomingQueue();
        }
        return new ArrayList<>();
    }

    private void handleProgressTouch(float adjustedX, int usableWidth) {
        if (musicService != null && musicService.getMediaPlayer() != null) {
            MediaPlayer mediaPlayer = musicService.getMediaPlayer();

            float progressPercent = Math.max(0, Math.min(1, adjustedX / usableWidth));
            int newProgress = (int) (progressPercent * 100);
            binding.seekBar.setProgress(newProgress);

            int seekPosition = (int) (progressPercent * mediaPlayer.getDuration());
            binding.currentTime.setText(formatDuration(seekPosition));
        }
    }

    private void seekToPosition(float adjustedX, int usableWidth) {
        if (musicService != null && musicService.getMediaPlayer() != null) {
            MediaPlayer mediaPlayer = musicService.getMediaPlayer();

            float progressPercent = Math.max(0, Math.min(1, adjustedX / usableWidth));
            int seekPosition = (int) (progressPercent * mediaPlayer.getDuration());

            Intent serviceIntent = new Intent(this, MusicService.class);
            serviceIntent.setAction(MusicService.ACTION_SEEK);
            serviceIntent.putExtra("seek_position", seekPosition);
            startService(serviceIntent);
        }
    }

    private void togglePlayPause() {
        Intent serviceIntent = new Intent(this, MusicService.class);
        serviceIntent.setAction(MusicService.ACTION_TOGGLE_PLAY_PAUSE);
        startService(serviceIntent);
    }

    private void playNext() {
        Intent serviceIntent = new Intent(this, MusicService.class);
        serviceIntent.setAction(MusicService.ACTION_NEXT);
        startService(serviceIntent);
    }

    private void playPrevious() {
        Intent serviceIntent = new Intent(this, MusicService.class);
        serviceIntent.setAction(MusicService.ACTION_PREVIOUS);
        startService(serviceIntent);
    }

    private void toggleShuffle() {
        Intent serviceIntent = new Intent(this, MusicService.class);
        serviceIntent.setAction(MusicService.ACTION_TOGGLE_SHUFFLE);
        startService(serviceIntent);
    }

    private void toggleRepeat() {
        Intent serviceIntent = new Intent(this, MusicService.class);
        serviceIntent.setAction(MusicService.ACTION_TOGGLE_REPEAT);
        startService(serviceIntent);
    }

    private void updatePlayPauseButton() {
        String buttonText = isPlaying ? "PAUSE" : "PLAY";
        binding.playPauseButton.setText(buttonText);
    }

    private void updateShuffleButton() {
        if (isShuffleEnabled) {
            binding.shuffleButton.setAlpha(1.0f);
        } else {
            binding.shuffleButton.setAlpha(0.6f);
        }
    }

    private void updateRepeatButton() {
        int activeColor = getColorFromAttr(com.google.android.material.R.attr.colorPrimaryVariant);
        int inactiveColor = getColorFromAttr(com.google.android.material.R.attr.colorOnSurfaceVariant);

        switch (repeatMode) {
            case MusicService.REPEAT_OFF:
                binding.repeatButton.setAlpha(0.6f);
                binding.repeatButton.setCompoundDrawablesWithIntrinsicBounds(
                        R.drawable.ic_outline_repeat_24, 0, 0, 0
                );
                binding.repeatButton.setContentDescription("Repeat Off");
                applyTintToCompoundDrawables(binding.repeatButton, inactiveColor);
                break;
            case MusicService.REPEAT_ALL:
                binding.repeatButton.setAlpha(1.0f);
                binding.repeatButton.setCompoundDrawablesWithIntrinsicBounds(
                        R.drawable.ic_outline_repeat_24, 0, 0, 0
                );
                binding.repeatButton.setContentDescription("Repeat All");
                applyTintToCompoundDrawables(binding.repeatButton, activeColor);
                break;
            case MusicService.REPEAT_ONE:
                binding.repeatButton.setAlpha(1.0f);
                binding.repeatButton.setCompoundDrawablesWithIntrinsicBounds(
                        R.drawable.ic_baseline_repeat_one_24, 0, 0, 0
                );
                binding.repeatButton.setContentDescription("Repeat One");
                applyTintToCompoundDrawables(binding.repeatButton, activeColor);
                break;
        }
    }

    private int getColorFromAttr(int attrResId) {
        TypedValue typedValue = new TypedValue();
        getTheme().resolveAttribute(attrResId, typedValue, true);
        return typedValue.data;
    }

    private void applyTintToCompoundDrawables(Button button, int color) {
        Drawable[] drawables = button.getCompoundDrawables();
        for (Drawable drawable : drawables) {
            if (drawable != null) {
                DrawableCompat.setTint(drawable, color);
            }
        }
    }

    private void startSeekBarUpdates() {
        stopSeekBarUpdates();

        updateSeekBar = new Runnable() {
            @Override
            public void run() {
                if (musicService != null && musicService.getMediaPlayer() != null && isPlaying && !isDraggingSeekBar) {
                    MediaPlayer mediaPlayer = musicService.getMediaPlayer();
                    try {
                        int currentPosition = mediaPlayer.getCurrentPosition();
                        int duration = mediaPlayer.getDuration();

                        if (duration > 0) {
                            int progress = (int) (((float) currentPosition / duration) * 100);
                            binding.seekBar.setProgress(progress);
                            binding.currentTime.setText(formatDuration(currentPosition));
                        }
                    } catch (IllegalStateException e) {
                    }
                }

                if (isPlaying) {
                    handler.postDelayed(this, 1000);
                }
            }
        };
        handler.post(updateSeekBar);
    }

    private void stopSeekBarUpdates() {
        if (updateSeekBar != null) {
            handler.removeCallbacks(updateSeekBar);
        }
    }

    private String formatDuration(long duration) {
        long minutes = TimeUnit.MILLISECONDS.toMinutes(duration);
        long seconds = TimeUnit.MILLISECONDS.toSeconds(duration) - TimeUnit.MINUTES.toSeconds(minutes);
        return String.format("%02d:%02d", minutes, seconds);
    }

    public void onBackPressedDispatcher() {
        super.onBackPressed();
        overridePendingTransition(0, R.anim.slide_out_bottom);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();

        stopSeekBarUpdates();

        if (serviceBound) {
            unbindService(serviceConnection);
            serviceBound = false;
        }

        if (musicUpdateReceiver != null) {
            unregisterReceiver(musicUpdateReceiver);
        }
    }

    private void showQueueBottomSheet() {
        BottomSheetDialog bottomSheetDialog = new BottomSheetDialog(this);
        View view = LayoutInflater.from(this).inflate(R.layout.bottom_sheet_queue, null);

        RecyclerView queueRecyclerView = view.findViewById(R.id.queueRecyclerView);
        android.widget.TextView emptyQueueText = view.findViewById(R.id.emptyQueueText);

        queueRecyclerView.setLayoutManager(new LinearLayoutManager(this));

        List<MusicItem> queueList = getUpcomingQueue();

        if (queueList.isEmpty()) {
            queueRecyclerView.setVisibility(View.GONE);
            emptyQueueText.setVisibility(View.VISIBLE);
        } else {
            queueRecyclerView.setVisibility(View.VISIBLE);
            emptyQueueText.setVisibility(View.GONE);

            QueueAdapter queueAdapter = new QueueAdapter(
                    queueList,
                    (fromPosition, toPosition) -> {
                        if (musicService != null) {
                            musicService.moveQueueItem(fromPosition, toPosition);
                        }
                    },
                    (position, removedItem) -> {
                        if (musicService != null) {
                            musicService.removeQueueItem(position);
                            Toast.makeText(this, "Removed from queue", Toast.LENGTH_SHORT).show();
                        }

                        if (queueList.isEmpty()) {
                            queueRecyclerView.setVisibility(View.GONE);
                            emptyQueueText.setVisibility(View.VISIBLE);
                        }
                    }
            );
            queueRecyclerView.setAdapter(queueAdapter);

            ItemTouchHelper itemTouchHelper = new ItemTouchHelper(new QueueItemTouchHelperCallback(queueAdapter));
            itemTouchHelper.attachToRecyclerView(queueRecyclerView);
        }

        bottomSheetDialog.setContentView(view);
        bottomSheetDialog.show();
    }

    private static class QueueAdapter extends RecyclerView.Adapter<QueueAdapter.QueueViewHolder> {

        private List<MusicItem> queueList;
        private OnQueueItemMovedListener moveListener;
        private OnQueueItemRemovedListener removeListener;

        public interface OnQueueItemMovedListener {
            void onQueueItemMoved(int fromPosition, int toPosition);
        }

        public interface OnQueueItemRemovedListener {
            void onQueueItemRemoved(int position, MusicItem item);
        }

        public QueueAdapter(List<MusicItem> queueList, OnQueueItemMovedListener moveListener,
                            OnQueueItemRemovedListener removeListener) {
            this.queueList = queueList;
            this.moveListener = moveListener;
            this.removeListener = removeListener;
        }

        @Override
        public QueueViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_queue_song, parent, false);
            return new QueueViewHolder(view);
        }

        @Override
        public void onBindViewHolder(QueueViewHolder holder, int position) {
            MusicItem item = queueList.get(position);
            holder.bind(item, position);
        }

        @Override
        public int getItemCount() {
            return queueList.size();
        }

        public boolean onItemMove(int fromPosition, int toPosition) {
            if (fromPosition < toPosition) {
                for (int i = fromPosition; i < toPosition; i++) {
                    java.util.Collections.swap(queueList, i, i + 1);
                }
            } else {
                for (int i = fromPosition; i > toPosition; i--) {
                    java.util.Collections.swap(queueList, i, i - 1);
                }
            }
            notifyItemMoved(fromPosition, toPosition);
            return true;
        }

        public void onItemMoveFinished(int fromPosition, int toPosition) {
            if (moveListener != null) {
                moveListener.onQueueItemMoved(fromPosition, toPosition);
            }
        }

        public void onItemSwiped(int position) {
            if (position >= 0 && position < queueList.size()) {
                MusicItem removedItem = queueList.remove(position);
                notifyItemRemoved(position);

                if (removeListener != null) {
                    removeListener.onQueueItemRemoved(position, removedItem);
                }
            }
        }

        static class QueueViewHolder extends RecyclerView.ViewHolder {

            private android.widget.TextView queuePosition;
            private android.widget.ImageView queueAlbumArt;
            private android.widget.TextView queueSongTitle;
            private android.widget.TextView queueSongArtist;
            private android.widget.TextView queueSongDuration;
            private android.widget.ImageView dragHandle;

            public QueueViewHolder(View itemView) {
                super(itemView);
                queuePosition = itemView.findViewById(R.id.queuePosition);
                queueAlbumArt = itemView.findViewById(R.id.queueAlbumArt);
                queueSongTitle = itemView.findViewById(R.id.queueSongTitle);
                queueSongArtist = itemView.findViewById(R.id.queueSongArtist);
                queueSongDuration = itemView.findViewById(R.id.queueSongDuration);
                dragHandle = itemView.findViewById(R.id.dragHandle);
            }

            void bind(MusicItem item, int position) {
                queuePosition.setText(String.valueOf(position + 1));
                queueSongTitle.setText(item.getTitle());
                queueSongArtist.setText(item.getArtist());

                long duration = item.getDuration();
                long minutes = TimeUnit.MILLISECONDS.toMinutes(duration);
                long seconds = TimeUnit.MILLISECONDS.toSeconds(duration) - TimeUnit.MINUTES.toSeconds(minutes);
                String formattedDuration = String.format("%d:%02d", minutes, seconds);
                queueSongDuration.setText(formattedDuration);

                Glide.with(itemView.getContext())
                        .load(item.getAlbumArtUri())
                        .placeholder(R.drawable.ic_outline_music_note_24)
                        .error(R.drawable.ic_outline_music_note_24)
                        .into(queueAlbumArt);
            }
        }
    }

    private static class QueueItemTouchHelperCallback extends ItemTouchHelper.Callback {

        private final QueueAdapter adapter;
        private int dragFrom = -1;
        private int dragTo = -1;

        public QueueItemTouchHelperCallback(QueueAdapter adapter) {
            this.adapter = adapter;
        }

        @Override
        public boolean isLongPressDragEnabled() {
            return true;
        }

        @Override
        public boolean isItemViewSwipeEnabled() {
            return true;
        }

        @Override
        public int getMovementFlags(RecyclerView recyclerView, RecyclerView.ViewHolder viewHolder) {
            int dragFlags = ItemTouchHelper.UP | ItemTouchHelper.DOWN;
            int swipeFlags = ItemTouchHelper.START | ItemTouchHelper.END;
            return makeMovementFlags(dragFlags, swipeFlags);
        }

        @Override
        public boolean onMove(RecyclerView recyclerView, RecyclerView.ViewHolder viewHolder,
                              RecyclerView.ViewHolder target) {
            int fromPosition = viewHolder.getAdapterPosition();
            int toPosition = target.getAdapterPosition();

            if (dragFrom == -1) {
                dragFrom = fromPosition;
            }
            dragTo = toPosition;

            adapter.onItemMove(fromPosition, toPosition);
            return true;
        }

        @Override
        public void onSwiped(RecyclerView.ViewHolder viewHolder, int direction) {
            int position = viewHolder.getAdapterPosition();
            adapter.onItemSwiped(position);
        }

        @Override
        public void onSelectedChanged(RecyclerView.ViewHolder viewHolder, int actionState) {
            super.onSelectedChanged(viewHolder, actionState);

            if (actionState == ItemTouchHelper.ACTION_STATE_DRAG) {
                viewHolder.itemView.setAlpha(0.7f);
                viewHolder.itemView.setScaleX(1.05f);
                viewHolder.itemView.setScaleY(1.05f);
            }
        }

        @Override
        public void clearView(RecyclerView recyclerView, RecyclerView.ViewHolder viewHolder) {
            super.clearView(recyclerView, viewHolder);

            viewHolder.itemView.setAlpha(1.0f);
            viewHolder.itemView.setScaleX(1.0f);
            viewHolder.itemView.setScaleY(1.0f);

            if (dragFrom != -1 && dragTo != -1 && dragFrom != dragTo) {
                adapter.onItemMoveFinished(dragFrom, dragTo);
            }

            dragFrom = -1;
            dragTo = -1;
        }

        @Override
        public void onChildDraw(Canvas c, RecyclerView recyclerView, RecyclerView.ViewHolder viewHolder,
                                float dX, float dY, int actionState, boolean isCurrentlyActive) {
            if (actionState == ItemTouchHelper.ACTION_STATE_SWIPE) {
                final float alpha = 1.0f - Math.abs(dX) / (float) viewHolder.itemView.getWidth();
                viewHolder.itemView.setAlpha(alpha);
                viewHolder.itemView.setTranslationX(dX);
            } else {
                super.onChildDraw(c, recyclerView, viewHolder, dX, dY, actionState, isCurrentlyActive);
            }
        }
    }
}
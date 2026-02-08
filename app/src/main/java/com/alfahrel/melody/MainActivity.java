package com.alfahrel.melody;

import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.view.animation.LinearInterpolator;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import com.alfahrel.melody.ui.pages.search.SearchActivity;
import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.android.material.appbar.AppBarLayout;
import com.google.android.material.appbar.CollapsingToolbarLayout;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowCompat;
import androidx.viewpager2.widget.ViewPager2;

import com.bumptech.glide.Glide;
import com.alfahrel.melody.databinding.ActivityMainBinding;
import com.alfahrel.melody.ui.music.MusicItem;
import com.alfahrel.melody.ui.pages.nowplaying.NowPlayingActivity;
import com.alfahrel.melody.service.MusicService;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "MainActivity";
    private static final int REFRESH_ANIMATION_DURATION = 1000;
    private static final int REFRESH_COOLDOWN_DURATION = 2000;
    private static final int MINI_PLAYER_ANIMATION_DURATION = 300;
    private static final float TOOLBAR_FADE_THRESHOLD = 0.7f;

    private ActivityMainBinding binding;
    private TextView toolbarTitle;
    private CollapsingToolbarLayout collapsingToolbar;
    private String currentTitle = "melody";

    private ViewPager2 viewPager;
    private MainViewPagerAdapter pagerAdapter;
    private BottomNavigationView navView;

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
    private boolean isActivityDestroyed = false;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private final BroadcastReceiver musicUpdateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (isActivityDestroyed || isFinishing() || isDestroyed()) {
                return;
            }

            try {
                String action = intent.getAction();
                if (action == null) return;

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
                        boolean playingState = intent.getBooleanExtra("is_playing", false);
                        updateMiniPlayerState(playingState);
                        break;
                    case MusicService.ACTION_HIDE_MINI_PLAYER:
                        hideMiniPlayer();
                        break;
                }
            } catch (Exception e) {
                Log.e(TAG, "Error handling broadcast: " + e.getMessage(), e);
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        try {
            enableEdgeToEdge();

            binding = ActivityMainBinding.inflate(getLayoutInflater());
            setContentView(binding.getRoot());

            setupWindowInsets();

            if (!initializeToolbarComponents()) {
                Log.e(TAG, "Failed to initialize toolbar components");
                return;
            }

            if (!initializeMiniPlayer()) {
                Log.e(TAG, "Failed to initialize mini player components");
                return;
            }

            setupViewPagerAndNavigation();
            setupToolbarActions();
            setupSearchButton();
            registerMusicUpdateReceiver();

        } catch (Exception e) {
            Log.e(TAG, "Error in onCreate: " + e.getMessage(), e);
            finish();
        }
    }

    private boolean initializeToolbarComponents() {
        try {
            MaterialToolbar toolbar = findViewById(R.id.toolbar);
            toolbarTitle = findViewById(R.id.toolbar_title_main);
            collapsingToolbar = findViewById(R.id.collapsing_toolbar);
            AppBarLayout appBarLayout = findViewById(R.id.app_bar_layout);

            if (toolbar == null || toolbarTitle == null || collapsingToolbar == null || appBarLayout == null) {
                Log.e(TAG, "One or more toolbar components are null");
                return false;
            }

            setSupportActionBar(toolbar);
            if (getSupportActionBar() != null) {
                getSupportActionBar().setDisplayHomeAsUpEnabled(false);
            }

            collapsingToolbar.setTitle(currentTitle);
            setupCollapsingToolbarTitleAnimation(appBarLayout);

            return true;
        } catch (Exception e) {
            Log.e(TAG, "Error initializing toolbar: " + e.getMessage(), e);
            return false;
        }
    }

    private void setupViewPagerAndNavigation() {
        try {
            viewPager = findViewById(R.id.view_pager);
            navView = findViewById(R.id.nav_view);

            if (viewPager == null || navView == null) {
                Log.e(TAG, "ViewPager or BottomNavigationView is null");
                return;
            }

            pagerAdapter = new MainViewPagerAdapter(this);
            viewPager.setAdapter(pagerAdapter);

            viewPager.setOffscreenPageLimit(1);

            // Disable swipe gestures
            viewPager.setUserInputEnabled(true);

            viewPager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
                @Override
                public void onPageSelected(int position) {
                    super.onPageSelected(position);
                    if (!isActivityDestroyed) {
                        updateNavigationSelection(position);
                        updateTitleForPosition(position);
                    }
                }
            });

            navView.setOnItemSelectedListener(item -> {
                int itemId = item.getItemId();
                int position = getPositionForMenuId(itemId);
                if (position != -1) {
                    viewPager.setCurrentItem(position, true); // false = no animation
                    return true;
                }
                return false;
            });

            Log.d(TAG, "ViewPager and Navigation setup completed successfully");

        } catch (Exception e) {
            Log.e(TAG, "Error setting up ViewPager: " + e.getMessage(), e);
        }
    }
    private int getPositionForMenuId(int menuId) {
        if (menuId == R.id.navigation_music) {
            return 0;
        } else if (menuId == R.id.navigation_album) {
            return 1;
        } else if (menuId == R.id.navigation_artist) {
            return 2;
        } else if (menuId == R.id.navigation_collection) {
            return 3;
        }
        return -1;
    }

    private void updateNavigationSelection(int position) {
        switch (position) {
            case 0:
                navView.setSelectedItemId(R.id.navigation_music);
                break;
            case 1:
                navView.setSelectedItemId(R.id.navigation_album);
                break;
            case 2:
                navView.setSelectedItemId(R.id.navigation_artist);
                break;
            case 3:
                navView.setSelectedItemId(R.id.navigation_collection);
                break;
        }
    }

    private void updateTitleForPosition(int position) {
        switch (position) {
            case 0:
                currentTitle = "Music";
                break;
            case 1:
                currentTitle = "Albums";
                break;
            case 2:
                currentTitle = "Artist";
                break;
            case 3:
                currentTitle = "Collection";
                break;
            case 4:
                currentTitle = "Settings";
                break;
            default:
                currentTitle = "melody";
                break;
        }

        if (toolbarTitle != null) {
            toolbarTitle.setText(currentTitle);
        }
        if (collapsingToolbar != null) {
            collapsingToolbar.setTitle(currentTitle);
        }
    }

    private void setupToolbarActions() {
        try {
            MaterialButton refreshButton = findViewById(R.id.refresh_button);
            if (refreshButton != null) {
                refreshButton.setOnClickListener(v -> refreshMusicFragmentData());
            }
        } catch (Exception e) {
            Log.e(TAG, "Error setting up toolbar actions: " + e.getMessage(), e);
        }
    }

    private void setupSearchButton() {
        MaterialButton searchButton = findViewById(R.id.search_button);
        if (searchButton != null) {
            searchButton.setOnClickListener(v -> {
                Intent searchIntent = new Intent(this, SearchActivity.class);
                startActivity(searchIntent);
            });
        }
    }

    private void refreshMusicFragmentData() {
        try {
            MaterialButton refreshButton = findViewById(R.id.refresh_button);
            if (refreshButton == null) return;

            animateRefreshButton(refreshButton);
            clearAllFragmentCaches();

            int currentPosition = viewPager.getCurrentItem();

            switch (currentPosition) {
                case 0:
                    showRefreshToast("Refreshing music library...");
                    break;
                case 1:
                    showRefreshToast("Refreshing album library...");
                    break;
                case 2:
                    showRefreshToast("Refreshing artist library...");
                    break;
                default:
                    showRefreshToast("Refreshing library...");
                    break;
            }

            pagerAdapter.notifyDataSetChanged();

        } catch (Exception e) {
            Log.e(TAG, "Error refreshing fragments: " + e.getMessage(), e);
            showRefreshToast("Error refreshing library");
            resetRefreshButton();
        }
    }

    private void animateRefreshButton(MaterialButton refreshButton) {
        refreshButton.animate()
                .rotation(360f)
                .setDuration(REFRESH_ANIMATION_DURATION)
                .setInterpolator(new LinearInterpolator())
                .withEndAction(() -> {
                    if (!isActivityDestroyed && refreshButton != null) {
                        refreshButton.setRotation(0f);
                    }
                })
                .start();

        refreshButton.setEnabled(false);
        mainHandler.postDelayed(() -> {
            if (!isActivityDestroyed && refreshButton != null) {
                refreshButton.setEnabled(true);
            }
        }, REFRESH_COOLDOWN_DURATION);
    }

    private void clearAllFragmentCaches() {
        com.alfahrel.melody.ui.album.AlbumFragment.clearCache();
        com.alfahrel.melody.ui.artist.ArtistFragment.clearCache();
    }

    private void showRefreshToast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    private void resetRefreshButton() {
        MaterialButton refreshButton = findViewById(R.id.refresh_button);
        if (refreshButton != null) {
            refreshButton.setEnabled(true);
            refreshButton.setRotation(0f);
        }
    }

    private void enableEdgeToEdge() {
        try {
            getWindow().setNavigationBarColor(android.graphics.Color.TRANSPARENT);

            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                getWindow().setNavigationBarContrastEnforced(false);
            }

            WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        } catch (Exception e) {
            Log.e(TAG, "Error enabling edge-to-edge: " + e.getMessage(), e);
        }
    }

    private void setupWindowInsets() {
        try {
            View rootView = binding.getRoot();
            if (rootView == null) return;

            ViewCompat.setOnApplyWindowInsetsListener(rootView, (v, windowInsets) -> {
                windowInsets.getInsets(WindowInsetsCompat.Type.navigationBars());
                return WindowInsetsCompat.CONSUMED;
            });
        } catch (Exception e) {
            Log.e(TAG, "Error setting up window insets: " + e.getMessage(), e);
        }
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private void registerMusicUpdateReceiver() {
        try {
            if (!isReceiverRegistered && musicUpdateReceiver != null) {
                IntentFilter filter = new IntentFilter();
                filter.addAction(MusicService.ACTION_MUSIC_UPDATED);
                filter.addAction(MusicService.ACTION_PLAYBACK_STATE_CHANGED);
                filter.addAction(MusicService.ACTION_HIDE_MINI_PLAYER);

                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                    registerReceiver(musicUpdateReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
                } else {
                    registerReceiver(musicUpdateReceiver, filter);
                }

                isReceiverRegistered = true;
            }
        } catch (Exception e) {
            Log.e(TAG, "Error registering broadcast receiver: " + e.getMessage(), e);
            isReceiverRegistered = false;
        }
    }

    private boolean initializeMiniPlayer() {
        try {
            miniPlayerContainer = findViewById(R.id.miniPlayerContainer);
            miniAlbumArt = findViewById(R.id.miniAlbumArt);
            miniSongTitle = findViewById(R.id.miniSongTitle);
            miniArtistName = findViewById(R.id.miniArtistName);
            miniPlayPauseButton = findViewById(R.id.miniPlayPauseButton);
            miniNextButton = findViewById(R.id.miniNextButton);
            miniCloseButton = findViewById(R.id.miniCloseButton);

            if (miniPlayerContainer == null || miniAlbumArt == null ||
                    miniSongTitle == null || miniArtistName == null ||
                    miniPlayPauseButton == null || miniNextButton == null || miniCloseButton == null) {
                Log.e(TAG, "One or more mini player components are null");
                return false;
            }

            setupMiniPlayerClickListeners();
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Error initializing mini player: " + e.getMessage(), e);
            return false;
        }
    }

    private void setupMiniPlayerClickListeners() {
        miniPlayerContainer.setOnClickListener(v -> {
            if (!isActivityDestroyed) {
                openNowPlayingActivity();
            }
        });

        miniPlayPauseButton.setOnClickListener(v -> {
            if (!isActivityDestroyed) {
                sendMusicServiceAction(MusicService.ACTION_TOGGLE_PLAY_PAUSE);
            }
        });

        miniNextButton.setOnClickListener(v -> {
            if (!isActivityDestroyed) {
                sendMusicServiceAction(MusicService.ACTION_NEXT);
            }
        });

        miniCloseButton.setOnClickListener(v -> {
            if (!isActivityDestroyed) {
                sendMusicServiceAction(MusicService.ACTION_STOP);
                hideMiniPlayer();
            }
        });
    }

    private void sendMusicServiceAction(String action) {
        try {
            Intent serviceIntent = new Intent(this, MusicService.class);
            serviceIntent.setAction(action);
            startService(serviceIntent);
        } catch (Exception e) {
            Log.e(TAG, "Error sending service action " + action + ": " + e.getMessage(), e);
        }
    }

    public void showMiniPlayer(MusicItem musicItem) {
        if (isActivityDestroyed || isFinishing() || isDestroyed() || musicItem == null) {
            return;
        }

        try {
            currentPlayingItem = musicItem;

            if (miniSongTitle == null || miniArtistName == null ||
                    miniAlbumArt == null || miniPlayerContainer == null) {
                Log.e(TAG, "Mini player components are null");
                return;
            }

            miniSongTitle.setText(musicItem.getTitle());
            miniArtistName.setText(musicItem.getArtist());

            loadAlbumArt(musicItem);

            if (!isMiniPlayerVisible) {
                animateMiniPlayerIn();
            }

            updateMiniPlayerPlayButton();
        } catch (Exception e) {
            Log.e(TAG, "Error showing mini player: " + e.getMessage(), e);
        }
    }

    private void loadAlbumArt(MusicItem musicItem) {
        try {
            Glide.with(this)
                    .load(musicItem.getAlbumArtUri())
                    .placeholder(R.drawable.ic_outline_music_note_24)
                    .error(R.drawable.ic_outline_music_note_24)
                    .into(miniAlbumArt);
        } catch (Exception e) {
            Log.e(TAG, "Error loading album art: " + e.getMessage(), e);
        }
    }

    private void animateMiniPlayerIn() {
        isMiniPlayerVisible = true;
        miniPlayerContainer.setVisibility(View.VISIBLE);
        miniPlayerContainer.setTranslationY(miniPlayerContainer.getHeight());
        miniPlayerContainer.animate()
                .translationY(0)
                .setDuration(MINI_PLAYER_ANIMATION_DURATION)
                .withEndAction(() -> {
                    if (!isActivityDestroyed) {
                        broadcastMiniPlayerVisibility(true);
                    }
                })
                .start();
    }

    public void hideMiniPlayer() {
        if (isActivityDestroyed || isFinishing() || isDestroyed()) {
            return;
        }

        try {
            if (isMiniPlayerVisible && miniPlayerContainer != null) {
                isMiniPlayerVisible = false;
                miniPlayerContainer.animate()
                        .translationY(miniPlayerContainer.getHeight())
                        .setDuration(MINI_PLAYER_ANIMATION_DURATION)
                        .withEndAction(() -> {
                            if (!isActivityDestroyed && miniPlayerContainer != null) {
                                miniPlayerContainer.setVisibility(View.GONE);
                                broadcastMiniPlayerVisibility(false);
                            }
                        })
                        .start();
            }
        } catch (Exception e) {
            Log.e(TAG, "Error hiding mini player: " + e.getMessage(), e);
        }
    }

    private void broadcastMiniPlayerVisibility(boolean isVisible) {
        if (isActivityDestroyed) return;

        try {
            Intent intent = new Intent("MINI_PLAYER_VISIBILITY_CHANGED");
            intent.putExtra("is_visible", isVisible);
            intent.setPackage(getPackageName());
            sendBroadcast(intent);
        } catch (Exception e) {
            Log.e(TAG, "Error broadcasting mini player visibility: " + e.getMessage(), e);
        }
    }

    public void updateMiniPlayerState(boolean playing) {
        if (isActivityDestroyed) return;

        try {
            isPlaying = playing;
            updateMiniPlayerPlayButton();
        } catch (Exception e) {
            Log.e(TAG, "Error updating mini player state: " + e.getMessage(), e);
        }
    }

    private void updateMiniPlayerPlayButton() {
        if (isActivityDestroyed || miniPlayPauseButton == null) return;

        try {
            int iconRes = isPlaying ? R.drawable.ic_baseline_pause_24 : R.drawable.ic_baseline_play_arrow_24;
            miniPlayPauseButton.setIconResource(iconRes);
        } catch (Exception e) {
            Log.e(TAG, "Error updating play button: " + e.getMessage(), e);
        }
    }

    private void openNowPlayingActivity() {
        if (isActivityDestroyed || currentPlayingItem == null) return;

        try {
            Intent intent = new Intent(this, NowPlayingActivity.class);
            intent.putExtra("music_item", currentPlayingItem);
            startActivity(intent);
            overridePendingTransition(R.anim.slide_in_bottom, R.anim.slide_out_top);
        } catch (Exception e) {
            Log.e(TAG, "Error opening now playing activity: " + e.getMessage(), e);
        }
    }

    public void startMusicService(MusicItem musicItem) {
        if (isActivityDestroyed || musicItem == null) return;

        try {
            Intent serviceIntent = new Intent(this, MusicService.class);
            serviceIntent.setAction(MusicService.ACTION_PLAY);
            serviceIntent.putExtra("music_item", musicItem);
            startService(serviceIntent);
        } catch (Exception e) {
            Log.e(TAG, "Error starting music service: " + e.getMessage(), e);
        }
    }

    private void setupCollapsingToolbarTitleAnimation(AppBarLayout appBarLayout) {
        if (appBarLayout == null) return;

        try {
            appBarLayout.addOnOffsetChangedListener(new AppBarLayout.OnOffsetChangedListener() {
                private boolean isCollapsed = false;

                @Override
                public void onOffsetChanged(AppBarLayout appBar, int verticalOffset) {
                    if (isActivityDestroyed || toolbarTitle == null || collapsingToolbar == null) {
                        return;
                    }

                    try {
                        int totalScrollRange = appBar.getTotalScrollRange();
                        float percentage = Math.abs(verticalOffset) / (float) totalScrollRange;

                        boolean shouldCollapse = percentage > TOOLBAR_FADE_THRESHOLD;

                        if (shouldCollapse != isCollapsed) {
                            isCollapsed = shouldCollapse;

                            if (isCollapsed) {
                                toolbarTitle.setVisibility(View.VISIBLE);
                                toolbarTitle.setAlpha(1f);
                                collapsingToolbar.setTitle("");
                            } else {
                                toolbarTitle.setVisibility(View.INVISIBLE);
                                toolbarTitle.setAlpha(0f);
                                collapsingToolbar.setTitle(currentTitle);
                            }
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "Error in toolbar animation: " + e.getMessage(), e);
                    }
                }
            });
        } catch (Exception e) {
            Log.e(TAG, "Error setting up toolbar animation: " + e.getMessage(), e);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();

        try {
            sendMusicServiceAction(MusicService.ACTION_REQUEST_STATE);
        } catch (Exception e) {
            Log.e(TAG, "Error in onResume: " + e.getMessage(), e);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        try {
            if (miniPlayerContainer != null) {
                miniPlayerContainer.clearAnimation();
            }
        } catch (Exception e) {
            Log.e(TAG, "Error in onPause: " + e.getMessage(), e);
        }
    }

    @Override
    protected void onDestroy() {
        isActivityDestroyed = true;

        mainHandler.removeCallbacksAndMessages(null);

        if (isReceiverRegistered) {
            try {
                unregisterReceiver(musicUpdateReceiver);
                isReceiverRegistered = false;
            } catch (IllegalArgumentException e) {
                Log.w(TAG, "Receiver was not registered or already unregistered");
            } catch (Exception e) {
                Log.e(TAG, "Error unregistering receiver: " + e.getMessage(), e);
            }
        }

        try {
            currentPlayingItem = null;

            if (isFinishing() && miniAlbumArt != null) {
                try {
                    Glide.with(getApplicationContext()).clear(miniAlbumArt);
                } catch (Exception e) {
                    Log.e(TAG, "Error clearing Glide: " + e.getMessage());
                }
            }

            binding = null;
        } catch (Exception e) {
            Log.e(TAG, "Error clearing references: " + e.getMessage(), e);
        }

        super.onDestroy();
    }
}
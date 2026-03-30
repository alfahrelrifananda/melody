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
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.LinearInterpolator;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import com.alfahrel.melody.ui.pages.settings.SettingsActivity;
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
    private static final int NAV_ANIMATION_DURATION = 250;
    private static final float TOOLBAR_FADE_THRESHOLD = 0.7f;
    private static final float SWIPE_THRESHOLD = 150f;
    private static final float SWIPE_VERTICAL_THRESHOLD   = 150f;
    private static final float SWIPE_HORIZONTAL_THRESHOLD = 200f;
    private static final float SWIPE_ANGLE_LIMIT          = 35f;

    public static final String ACTION_NAV_SCROLL = "NAV_SCROLL_DIRECTION";

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
    private boolean isNavVisible = true;
    private boolean isReceiverRegistered = false;
    private boolean isScrollReceiverRegistered = false;
    private boolean isActivityDestroyed = false;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    // -------------------------------------------------------------------------
    // Broadcast receiver: music service updates
    // -------------------------------------------------------------------------
    private final BroadcastReceiver musicUpdateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (isActivityDestroyed || isFinishing() || isDestroyed()) return;

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

    // -------------------------------------------------------------------------
    // Broadcast receiver: scroll direction from fragments
    // -------------------------------------------------------------------------
    private final BroadcastReceiver scrollDirectionReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (isActivityDestroyed || isFinishing() || isDestroyed()) return;

            try {
                boolean hide = intent.getBooleanExtra("hide", false);
                if (hide && isNavVisible) {
                    hideNavBar();
                } else if (!hide && !isNavVisible) {
                    showNavBar();
                }
            } catch (Exception e) {
                Log.e(TAG, "Error handling scroll broadcast: " + e.getMessage(), e);
            }
        }
    };

    // =========================================================================
    // Lifecycle
    // =========================================================================

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
            registerMusicUpdateReceiver();
            registerScrollDirectionReceiver();

        } catch (Exception e) {
            Log.e(TAG, "Error in onCreate: " + e.getMessage(), e);
            finish();
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
//        try {
//            if (miniPlayerContainer != null) miniPlayerContainer.clearAnimation();
//        } catch (Exception e) {
//            Log.e(TAG, "Error in onPause: " + e.getMessage(), e);
//        }
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
                Log.w(TAG, "musicUpdateReceiver was not registered or already unregistered");
            } catch (Exception e) {
                Log.e(TAG, "Error unregistering musicUpdateReceiver: " + e.getMessage(), e);
            }
        }

        if (isScrollReceiverRegistered) {
            try {
                unregisterReceiver(scrollDirectionReceiver);
                isScrollReceiverRegistered = false;
            } catch (IllegalArgumentException e) {
                Log.w(TAG, "scrollDirectionReceiver was not registered or already unregistered");
            } catch (Exception e) {
                Log.e(TAG, "Error unregistering scrollDirectionReceiver: " + e.getMessage(), e);
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

    // =========================================================================
    // Toolbar
    // =========================================================================

    private boolean initializeToolbarComponents() {
        try {
            MaterialToolbar toolbar = findViewById(R.id.toolbar);
            toolbarTitle = findViewById(R.id.toolbar_title_main);

            if (toolbar == null || toolbarTitle == null) {
                Log.e(TAG, "One or more toolbar components are null");
                return false;
            }

            setSupportActionBar(toolbar);
            if (getSupportActionBar() != null) {
                getSupportActionBar().setDisplayHomeAsUpEnabled(false);
            }

            toolbarTitle.setText(getGreeting());

            return true;
        } catch (Exception e) {
            Log.e(TAG, "Error initializing toolbar: " + e.getMessage(), e);
            return false;
        }
    }

    private void setupToolbarActions() {
        try {
            setupSettingsButton();
        } catch (Exception e) {
            Log.e(TAG, "Error setting up toolbar actions: " + e.getMessage(), e);
        }
    }

    private void setupSettingsButton() {
        MaterialButton settingsButton = findViewById(R.id.settings_button);
        if (settingsButton != null) {
            settingsButton.setOnClickListener(v -> {
                Intent intent = new Intent(this, SettingsActivity.class);
                startActivity(intent);
            });
        }
    }

    private void hideNavBar() {
        if (isActivityDestroyed || navView == null) return;
        if (!isNavVisible) return;
        isNavVisible = false;

        float navSlide = navView.getHeight() + 75f;
        navView.animate()
                .translationY(navSlide)
                .setDuration(NAV_ANIMATION_DURATION)
                .start();

        if (isMiniPlayerVisible && miniPlayerContainer != null) {
            // Move mini-player down by the same amount so they stay adjacent.
            float current = miniPlayerContainer.getTranslationY();
            miniPlayerContainer.animate()
                    .translationY(current + navSlide)
                    .setDuration(NAV_ANIMATION_DURATION)
                    .start();
        }
    }

    private void showNavBar() {
        if (isActivityDestroyed || navView == null) return;
        if (isNavVisible) return;
        isNavVisible = true;

        navView.animate()
                .translationY(0f)
                .setDuration(NAV_ANIMATION_DURATION)
                .start();

        if (isMiniPlayerVisible && miniPlayerContainer != null) {
            miniPlayerContainer.animate()
                    .translationY(0f)
                    .setDuration(NAV_ANIMATION_DURATION)
                    .start();
        }
    }


    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private void registerScrollDirectionReceiver() {
        try {
            if (!isScrollReceiverRegistered) {
                IntentFilter filter = new IntentFilter(ACTION_NAV_SCROLL);

                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                    registerReceiver(scrollDirectionReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
                } else {
                    registerReceiver(scrollDirectionReceiver, filter);
                }

                isScrollReceiverRegistered = true;
            }
        } catch (Exception e) {
            Log.e(TAG, "Error registering scroll receiver: " + e.getMessage(), e);
        }
    }

    // =========================================================================
    // ViewPager + navigation
    // =========================================================================

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
            viewPager.setUserInputEnabled(false);

            viewPager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
                @Override
                public void onPageSelected(int position) {
                    super.onPageSelected(position);
                    if (!isActivityDestroyed) {
                        updateNavigationSelection(position);
                        updateTitleForPosition(position);
                        // Always restore nav bar when switching tabs
                        showNavBar();
                    }
                }
            });

            navView.setOnItemSelectedListener(item -> {
                int position = getPositionForMenuId(item.getItemId());
                if (position != -1) {
                    viewPager.setCurrentItem(position, true);
                    return true;
                }
                return false;
            });

        } catch (Exception e) {
            Log.e(TAG, "Error setting up ViewPager: " + e.getMessage(), e);
        }
    }

    private int getPositionForMenuId(int menuId) {
        if (menuId == R.id.navigation_home)      return 0;
        if (menuId == R.id.navigation_search)      return 1;
        if (menuId == R.id.navigation_collection) return 2;
        return -1;
    }

    private void updateNavigationSelection(int position) {
        switch (position) {
            case 0: navView.setSelectedItemId(R.id.navigation_home);      break;
            case 1: navView.setSelectedItemId(R.id.navigation_search);      break;
            case 2: navView.setSelectedItemId(R.id.navigation_collection); break;
        }
    }

    private void updateTitleForPosition(int position) {
        switch (position) {
            case 0: currentTitle = getGreeting();    break;
            case 1: currentTitle = "Search";         break;
            case 2: currentTitle = "Collection";     break;
            default: currentTitle = "melody";        break;
        }
        if (toolbarTitle != null) toolbarTitle.setText(currentTitle);
    }

    private String getGreeting() {
        int hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY);
        if (hour >= 5 && hour < 12)  return "Good Morning!";
        if (hour >= 12 && hour < 17) return "Good Afternoon!";
        if (hour >= 17 && hour < 21) return "Good Evening!";
        return "Good Evening!";
    }

    // =========================================================================
    // Edge-to-edge / insets
    // =========================================================================

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

    // =========================================================================
    // Music update receiver registration
    // =========================================================================

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

    // =========================================================================
    // Mini player
    // =========================================================================

    private boolean initializeMiniPlayer() {
        try {
            miniPlayerContainer = findViewById(R.id.miniPlayerContainer);
            miniAlbumArt        = findViewById(R.id.miniAlbumArt);
            miniSongTitle       = findViewById(R.id.miniSongTitle);
            miniArtistName      = findViewById(R.id.miniArtistName);
            miniPlayPauseButton = findViewById(R.id.miniPlayPauseButton);
            miniNextButton      = findViewById(R.id.miniNextButton);
            miniCloseButton     = findViewById(R.id.miniCloseButton);

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
            if (!isActivityDestroyed) openNowPlayingActivity();
        });

        miniPlayPauseButton.setOnClickListener(v -> {
            if (!isActivityDestroyed) sendMusicServiceAction(MusicService.ACTION_TOGGLE_PLAY_PAUSE);
        });

        miniNextButton.setOnClickListener(v -> {
            if (!isActivityDestroyed) sendMusicServiceAction(MusicService.ACTION_NEXT);
        });

        miniCloseButton.setOnClickListener(v -> {
            if (!isActivityDestroyed) {
                sendMusicServiceAction(MusicService.ACTION_STOP);
                hideMiniPlayer();
            }
        });

        setupMiniPlayerSwipeToDismiss();
    }

    @SuppressLint("ClickableViewAccessibility")
    private void setupMiniPlayerSwipeToDismiss() {
        final float[] startX     = {0f};
        final float[] startY     = {0f};
        final boolean[] dragging = {false};
        // Track which axis the gesture was locked onto.
        // null = undecided, "H" = horizontal, "V" = vertical
        final String[] axis      = {null};

        miniPlayerContainer.setOnTouchListener((v, event) -> {
            switch (event.getAction()) {

                case MotionEvent.ACTION_DOWN:
                    startX[0]   = event.getRawX();
                    startY[0]   = event.getRawY();
                    dragging[0] = false;
                    axis[0]     = null;
                    // Cancel any running animator so translationY is stable.
                    miniPlayerContainer.animate().cancel();
                    // Return TRUE here so we own the gesture from the start.
                    // This prevents the click listener firing at the end of drags.
                    return true;

                case MotionEvent.ACTION_MOVE: {
                    float dx = event.getRawX() - startX[0];
                    float dy = event.getRawY() - startY[0];
                    float dist = (float) Math.sqrt(dx * dx + dy * dy);

                    // Lock axis once the finger has moved far enough.
                    if (axis[0] == null && dist > 12f) {
                        float angleDeg = (float) Math.toDegrees(Math.abs(Math.atan2(dy, dx)));
                        // angleDeg: 0° = pure right, 90° = pure down
                        if (angleDeg <= SWIPE_ANGLE_LIMIT || angleDeg >= (180f - SWIPE_ANGLE_LIMIT)) {
                            axis[0] = "H";
                        } else if (angleDeg >= (90f - SWIPE_ANGLE_LIMIT) && angleDeg <= (90f + SWIPE_ANGLE_LIMIT)) {
                            axis[0] = "V";
                        } else {
                            // Diagonal – treat as vertical for safety
                            axis[0] = "V";
                        }
                        dragging[0] = true;
                    }

                    if (!dragging[0]) return true;

                    if ("V".equals(axis[0])) {
                        // Only allow downward swipe (dismissal direction)
                        float clampedDy = Math.max(0f, dy);
                        miniPlayerContainer.setTranslationY(clampedDy);
                        float progress = Math.min(clampedDy / SWIPE_VERTICAL_THRESHOLD, 1f);
                        miniPlayerContainer.setAlpha(1f - progress * 0.5f);

                    } else { // "H"
                        miniPlayerContainer.setTranslationX(dx);
                        float progress = Math.min(Math.abs(dx) / SWIPE_HORIZONTAL_THRESHOLD, 1f);
                        miniPlayerContainer.setAlpha(1f - progress * 0.6f);
                    }
                    return true;
                }

                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL: {
                    float dx = event.getRawX() - startX[0];
                    float dy = event.getRawY() - startY[0];

                    if (!dragging[0]) {
                        // Short tap with no real movement → open NowPlaying.
                        miniPlayerContainer.setTranslationX(0f);
                        miniPlayerContainer.setTranslationY(0f);
                        miniPlayerContainer.setAlpha(1f);
                        if (event.getAction() == MotionEvent.ACTION_UP) {
                            openNowPlayingActivity();
                        }
                        return true;
                    }

                    boolean dismiss = false;

                    if ("V".equals(axis[0]) && dy > SWIPE_VERTICAL_THRESHOLD) {
                        dismiss = true;
                        float targetY = miniPlayerContainer.getHeight() + 50f;
                        miniPlayerContainer.animate()
                                .translationY(targetY)
                                .translationX(0f)
                                .alpha(0f)
                                .setDuration(MINI_PLAYER_ANIMATION_DURATION)
                                .withEndAction(dismissRunnable)
                                .start();

                    } else if ("H".equals(axis[0]) && Math.abs(dx) > SWIPE_HORIZONTAL_THRESHOLD) {
                        dismiss = true;
                        float targetX = dx > 0
                                ? miniPlayerContainer.getWidth() + 50f
                                : -(miniPlayerContainer.getWidth() + 50f);
                        miniPlayerContainer.animate()
                                .translationX(targetX)
                                .translationY(0f)
                                .alpha(0f)
                                .setDuration(MINI_PLAYER_ANIMATION_DURATION)
                                .withEndAction(dismissRunnable)
                                .start();
                    }

                    if (!dismiss) {
                        // Snap back to resting position
                        miniPlayerContainer.animate()
                                .translationX(0f)
                                .translationY(0f)
                                .alpha(1f)
                                .setDuration(200)
                                .start();
                    }

                    dragging[0] = false;
                    axis[0]     = null;
                    return true;
                }
            }
            return false;
        });
    }

    private final Runnable dismissRunnable = () -> {
        if (isActivityDestroyed || miniPlayerContainer == null) return;
        miniPlayerContainer.setVisibility(View.GONE);
        miniPlayerContainer.setTranslationX(0f);
        miniPlayerContainer.setTranslationY(0f);
        miniPlayerContainer.setAlpha(1f);
        isMiniPlayerVisible = false;
        sendMusicServiceAction(MusicService.ACTION_STOP);
        broadcastMiniPlayerVisibility(false);
    };


    public void showMiniPlayer(MusicItem musicItem) {
        if (isActivityDestroyed || isFinishing() || isDestroyed() || musicItem == null) return;

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

            if (!isMiniPlayerVisible) animateMiniPlayerIn();

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
        miniPlayerContainer.setAlpha(0f);
        miniPlayerContainer.setTranslationX(0f);
        miniPlayerContainer.setTranslationY(miniPlayerContainer.getHeight());
        miniPlayerContainer.setVisibility(View.VISIBLE);
        miniPlayerContainer.animate()
                .translationY(0f)
                .alpha(1f)
                .setDuration(MINI_PLAYER_ANIMATION_DURATION)
                .withEndAction(() -> {
                    if (!isActivityDestroyed) broadcastMiniPlayerVisibility(true);
                })
                .start();
    }


    public void hideMiniPlayer() {
        if (isActivityDestroyed || isFinishing() || isDestroyed()) return;
        if (!isMiniPlayerVisible || miniPlayerContainer == null) return;

        isMiniPlayerVisible = false;
        miniPlayerContainer.animate()
                .translationY(miniPlayerContainer.getHeight())
                .translationX(0f)
                .alpha(0f)
                .setDuration(MINI_PLAYER_ANIMATION_DURATION)
                .withEndAction(() -> {
                    if (isActivityDestroyed || miniPlayerContainer == null) return;
                    miniPlayerContainer.setVisibility(View.GONE);
                    miniPlayerContainer.setAlpha(1f);
                    miniPlayerContainer.setTranslationX(0f);
                    miniPlayerContainer.setTranslationY(0f);
                    broadcastMiniPlayerVisibility(false);
                })
                .start();
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
            int iconRes = isPlaying
                    ? R.drawable.ic_baseline_pause_24
                    : R.drawable.ic_baseline_play_arrow_24;
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

    private void sendMusicServiceAction(String action) {
        try {
            Intent serviceIntent = new Intent(this, MusicService.class);
            serviceIntent.setAction(action);
            startService(serviceIntent);
        } catch (Exception e) {
            Log.e(TAG, "Error sending service action " + action + ": " + e.getMessage(), e);
        }
    }
}
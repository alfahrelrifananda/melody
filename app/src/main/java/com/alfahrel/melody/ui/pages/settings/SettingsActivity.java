package com.alfahrel.melody.ui.pages.settings;

import android.os.Bundle;
import android.view.View;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.WindowCompat;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.slider.Slider;

import com.alfahrel.melody.R;

public class SettingsActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Edge-to-edge – matches MainActivity
        getWindow().setNavigationBarColor(android.graphics.Color.TRANSPARENT);
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            getWindow().setNavigationBarContrastEnforced(false);
        }
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);

        setContentView(R.layout.activity_settings);

        setupToolbar();
        setupPlaceholderClicks();
    }

    private void setupToolbar() {
        MaterialToolbar toolbar = findViewById(R.id.settings_toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setDisplayShowHomeEnabled(true);
        }
    }

    @Override
    public boolean onSupportNavigateUp() {
        finish();
        return true;

    }

    private void setupPlaceholderClicks() {
        int[] clickableIds = {
                R.id.setting_theme,
                R.id.setting_sort_order,
                R.id.setting_scan_folders,
                R.id.setting_changelog,
                R.id.setting_licenses,
                R.id.setting_github
        };
        for (int id : clickableIds) {
            View v = findViewById(id);
            if (v != null) v.setOnClickListener(view -> { /* TODO */ });
        }
    }
}
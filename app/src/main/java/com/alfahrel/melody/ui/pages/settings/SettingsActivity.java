package com.alfahrel.melody.ui.pages.settings;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.WindowCompat;

import com.google.android.material.appbar.MaterialToolbar;

import com.alfahrel.melody.R;

import java.util.LinkedHashMap;
import java.util.Map;

public class SettingsActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        getWindow().setNavigationBarColor(android.graphics.Color.TRANSPARENT);
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            getWindow().setNavigationBarContrastEnforced(false);
        }
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);

        setContentView(R.layout.activity_settings);

        setupToolbar();
        setupClicks();
    }

    private void setupToolbar() {
        MaterialToolbar toolbar = findViewById(R.id.settings_toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }
    }

    @Override
    public boolean onSupportNavigateUp() {
        finish();
        return true;
    }

    private void setupClicks() {
        Map<Integer, String> links = new LinkedHashMap<>();
        links.put(R.id.setting_changelog, "https://github.com/alfahrelrifananda/melody/releases");
        links.put(R.id.setting_licenses, "https://github.com/alfahrelrifananda/melody/blob/main/LICENSES.md");
        links.put(R.id.setting_github,   "https://github.com/alfahrelrifananda/melody");

        for (Map.Entry<Integer, String> entry : links.entrySet()) {
            View v = findViewById(entry.getKey());
            if (v != null) {
                v.setOnClickListener(view -> {
                    Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(entry.getValue()));
                    startActivity(intent);
                });
            }
        }
    }
}
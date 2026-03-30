package com.alfahrel.melody.ui.pages.welcome;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.widget.Button;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.alfahrel.melody.MainActivity;
import com.alfahrel.melody.R;

public class WelcomeActivity extends AppCompatActivity {

    private ActivityResultLauncher<String> permissionLauncher;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_welcome);

        Button grantPermissionButton = findViewById(R.id.grantPermissionButton);

        // Setup permission launcher
        permissionLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestPermission(),
                granted -> {
                    if (granted) {
                        // Permission granted, proceed to main activity
                        startActivity(new Intent(WelcomeActivity.this, MainActivity.class));
                        finish();
                    } else {
                        // Permission denied, show message
                        Toast.makeText(this, "Permission is required to use the app.", Toast.LENGTH_LONG).show();
                    }
                });

        grantPermissionButton.setOnClickListener(v -> {
            requestPermission();
        });

        // Check if permission is already granted
        if (hasPermission()) {
            startActivity(new Intent(WelcomeActivity.this, MainActivity.class));
            finish();
        }
    }

    private boolean hasPermission() {
        String permission = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                ? Manifest.permission.READ_MEDIA_AUDIO
                : Manifest.permission.READ_EXTERNAL_STORAGE;
        return ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED;
    }

    private void requestPermission() {
        String permission = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                ? Manifest.permission.READ_MEDIA_AUDIO
                : Manifest.permission.READ_EXTERNAL_STORAGE;
        permissionLauncher.launch(permission);
    }
}
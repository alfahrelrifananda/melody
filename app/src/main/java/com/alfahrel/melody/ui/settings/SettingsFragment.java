package com.alfahrel.melody.ui.settings;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;

import com.alfahrel.melody.R;
import com.alfahrel.melody.databinding.FragmentSettingsBinding;
import com.google.android.material.bottomsheet.BottomSheetDialog;

public class SettingsFragment extends Fragment {
    private FragmentSettingsBinding binding;

    public View onCreateView(@NonNull LayoutInflater inflater,
                             ViewGroup container, Bundle savedInstanceState) {
        SettingsViewModel settingsViewModel =
                new ViewModelProvider(this).get(SettingsViewModel.class);

        binding = FragmentSettingsBinding.inflate(inflater, container, false);
        View root = binding.getRoot();

        setupCardListeners();
        return root;
    }

    private void setupCardListeners() {
        binding.scanFoldersCard.setOnClickListener(v -> showScanFoldersBottomSheet());
        binding.feedbackCard.setOnClickListener(v -> openEmailFeedback());
        binding.aboutCard.setOnClickListener(v -> showAboutBottomSheet());
        binding.privacyCard.setOnClickListener(v -> showPrivacyBottomSheet());
    }

    private void showScanFoldersBottomSheet() {
        BottomSheetDialog bottomSheet = new BottomSheetDialog(requireContext());
        View sheetView = getLayoutInflater().inflate(R.layout.bottom_sheet_scan_folders, null);

        View scanNowButton = sheetView.findViewById(R.id.scan_now_button);
        View autoScanSwitch = sheetView.findViewById(R.id.auto_scan_switch);
        View selectFoldersButton = sheetView.findViewById(R.id.select_folders_button);

        scanNowButton.setOnClickListener(v -> {
            Toast.makeText(getContext(), "Scanning music folders...", Toast.LENGTH_SHORT).show();
            bottomSheet.dismiss();
        });

        selectFoldersButton.setOnClickListener(v -> {
            Toast.makeText(getContext(), "Opening folder selection...", Toast.LENGTH_SHORT).show();
            bottomSheet.dismiss();
        });

        bottomSheet.setContentView(sheetView);
        bottomSheet.show();
    }

    private void openEmailFeedback() {
        Intent emailIntent = new Intent(Intent.ACTION_SENDTO);
        emailIntent.setData(Uri.parse("mailto:"));
        emailIntent.putExtra(Intent.EXTRA_EMAIL, new String[]{"pahrel1234@gmail.com"});
        emailIntent.putExtra(Intent.EXTRA_SUBJECT, "melody App Feedback");
        emailIntent.putExtra(Intent.EXTRA_TEXT, "Hi melody Team,\n\nI'd like to share my feedback:\n\n");

        try {
            startActivity(Intent.createChooser(emailIntent, "Send feedback via..."));
        } catch (android.content.ActivityNotFoundException ex) {
            Toast.makeText(getContext(), "No email app found", Toast.LENGTH_SHORT).show();
        }
    }

    private void showAboutBottomSheet() {
        BottomSheetDialog bottomSheet = new BottomSheetDialog(requireContext());
        View sheetView = getLayoutInflater().inflate(R.layout.bottom_sheet_about, null);
        bottomSheet.setContentView(sheetView);
        bottomSheet.show();
    }

    private void showPrivacyBottomSheet() {
        BottomSheetDialog bottomSheet = new BottomSheetDialog(requireContext());
        View sheetView = getLayoutInflater().inflate(R.layout.bottom_sheet_privacy, null);

        bottomSheet.setContentView(sheetView);
        bottomSheet.show();
    }

    private void applyTheme(String theme) {
        getContext().getSharedPreferences("app_preferences", 0)
                .edit()
                .putString("selected_theme", theme)
                .apply();

        Toast.makeText(getContext(), "Theme changed to " + theme, Toast.LENGTH_SHORT).show();

        if (getActivity() != null) {
            getActivity().recreate();
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }
}
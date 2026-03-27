package com.alfahrel.melody.ui.collection;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.content.res.TypedArray;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.alfahrel.melody.R;
import com.alfahrel.melody.databinding.FragmentCollectionBinding;
import com.bumptech.glide.Glide;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import android.widget.ImageButton;
import com.google.android.material.bottomsheet.BottomSheetDialog;

public class CollectionFragment extends Fragment {

    private FragmentCollectionBinding binding;
    private ExecutorService executorService;
    private CollectionAdapter adapter;
    private SharedPreferences preferences;
    private static final String PREFS_NAME = "CollectionsPrefs";
    private static final String KEY_COLLECTIONS = "collections";
    private Gson gson;

    public static final String ACTION_COLLECTION_CHANGED           = "com.alfahrel.melody.COLLECTION_CHANGED";
    public static final String ACTION_COLLECTION_CREATED           = "com.alfahrel.melody.COLLECTION_CREATED";
    public static final String ACTION_SONG_ADDED_TO_COLLECTION     = "com.alfahrel.melody.SONG_ADDED_TO_COLLECTION";
    public static final String ACTION_SONG_REMOVED_FROM_COLLECTION = "com.alfahrel.melody.SONG_REMOVED_FROM_COLLECTION";

    private boolean isReceiverRegistered = false;

    // ── Edit-dialog state (held while the image picker is open) ──────────────
    private Collection pendingEditCollection;       // collection being edited
    private String     pendingCoverImageUri;         // new URI chosen so far (null = keep existing, "" = remove)
    private ImageView  pendingCoverPreview;          // reference into the open dialog
    private MaterialButton pendingRemoveCoverBtn;

    // ── Image picker launcher ────────────────────────────────────────────────
    private ActivityResultLauncher<String> imagePickerLauncher;

    // ── BroadcastReceiver ────────────────────────────────────────────────────
    private final BroadcastReceiver collectionUpdateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null || intent.getAction() == null) return;
            String action = intent.getAction();
            if (ACTION_COLLECTION_CHANGED.equals(action) ||
                    ACTION_COLLECTION_CREATED.equals(action) ||
                    ACTION_SONG_ADDED_TO_COLLECTION.equals(action) ||
                    ACTION_SONG_REMOVED_FROM_COLLECTION.equals(action)) {
                refreshCollections();
            }
        }
    };

    // ────────────────────────────────────────────────────────────────────────
    // Lifecycle
    // ────────────────────────────────────────────────────────────────────────

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Register image picker BEFORE the fragment reaches STARTED state
        imagePickerLauncher = registerForActivityResult(
                new ActivityResultContracts.GetContent(),
                uri -> {
                    if (uri == null) return;

                    // Persist read permission across reboots
                    try {
                        requireContext().getContentResolver().takePersistableUriPermission(
                                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    } catch (Exception ignored) { /* not all providers grant persistable perms */ }

                    pendingCoverImageUri = uri.toString();

                    // Update the preview inside the open dialog
                    if (pendingCoverPreview != null) {
                        pendingCoverPreview.setVisibility(View.VISIBLE);
                        Glide.with(this)
                                .load(uri)
                                .centerCrop()
                                .into(pendingCoverPreview);

                        // Show placeholder
                        View placeholder = pendingCoverPreview.getRootView()
                                .findViewById(R.id.coverPlaceholder);
                        if (placeholder != null) placeholder.setVisibility(View.GONE);
                    }

                    if (pendingRemoveCoverBtn != null) {
                        pendingRemoveCoverBtn.setVisibility(View.VISIBLE);
                    }
                });
    }

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             ViewGroup container, Bundle savedInstanceState) {
        new ViewModelProvider(this).get(CollectionViewModel.class);

        binding = FragmentCollectionBinding.inflate(inflater, container, false);
        View root = binding.getRoot();

        executorService = Executors.newSingleThreadExecutor();
        preferences     = requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        gson            = new Gson();

        setupRecyclerView();
        setupAddButtons();
        loadCollectionData();
        registerCollectionUpdateReceiver();

        return root;
    }

    @Override
    public void onResume() {
        super.onResume();
        refreshCollections();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        unregisterCollectionUpdateReceiver();
        if (executorService != null && !executorService.isShutdown()) executorService.shutdown();
        binding = null;
    }

    // ────────────────────────────────────────────────────────────────────────
    // Setup
    // ────────────────────────────────────────────────────────────────────────

    private void setupRecyclerView() {
        RecyclerView recyclerView = binding.collectionRecyclerView;
        recyclerView.setHasFixedSize(true);
        recyclerView.setLayoutManager(new GridLayoutManager(getContext(), 1));

        adapter = new CollectionAdapter(new ArrayList<>(), this::onCollectionClick, this::onCollectionLongClick);
        adapter.setOnAddCollectionClickListener(this::showAddCollectionDialog);
        recyclerView.setAdapter(adapter);
    }

    private void setupAddButtons() {
        binding.btnCreateFirstCollection.setOnClickListener(v -> showAddCollectionDialog());
    }

    // ────────────────────────────────────────────────────────────────────────
    // Add collection dialog (unchanged)
    // ────────────────────────────────────────────────────────────────────────

    private void showAddCollectionDialog() {
        View dialogView = LayoutInflater.from(requireContext())
                .inflate(R.layout.dialog_add_collection, null);

        TextInputEditText editTextName  = dialogView.findViewById(R.id.editTextCollectionName);
        TextInputLayout   textInputLayout = dialogView.findViewById(R.id.textInputCollectionName);

        applyInputBoxStrokeColor(textInputLayout);

        new MaterialAlertDialogBuilder(requireContext())
                .setTitle("New Collection")
                .setView(dialogView)
                .setPositiveButton("Create", (dialog, which) -> {
                    String name = editTextName.getText() != null
                            ? editTextName.getText().toString().trim() : "";
                    if (!name.isEmpty()) {
                        createCollection(name);
                    } else {
                        Toast.makeText(requireContext(), "Collection name cannot be empty",
                                Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    // ────────────────────────────────────────────────────────────────────────
    // Edit collection dialog (NEW: rename + cover image)
    // ────────────────────────────────────────────────────────────────────────

    private void showEditCollectionDialog(Collection collection) {
        View dialogView = LayoutInflater.from(requireContext())
                .inflate(R.layout.dialog_edit_collection, null);

        TextInputEditText editTextName    = dialogView.findViewById(R.id.editTextCollectionName);
        TextInputLayout   textInputLayout = dialogView.findViewById(R.id.textInputCollectionName);
        ImageView         coverPreview    = dialogView.findViewById(R.id.imageCoverPreview);
        View              placeholder     = dialogView.findViewById(R.id.coverPlaceholder);
        MaterialButton    btnPickCover    = dialogView.findViewById(R.id.btnPickCoverImage);
        MaterialButton    btnRemoveCover  = dialogView.findViewById(R.id.btnRemoveCover);

        applyInputBoxStrokeColor(textInputLayout);

        // Pre-fill current name
        editTextName.setText(collection.getName());
        editTextName.setSelection(editTextName.length());

        // Pre-fill current cover image
        String existingUri = collection.getCoverImageUri();
        if (existingUri != null && !existingUri.isEmpty()) {
            coverPreview.setVisibility(View.VISIBLE);
            if (placeholder != null) placeholder.setVisibility(View.GONE);
            btnRemoveCover.setVisibility(View.VISIBLE);
            Glide.with(this).load(Uri.parse(existingUri)).centerCrop().into(coverPreview);
        }

        // Store refs so the image picker callback can update them
        pendingEditCollection = collection;
        pendingCoverImageUri  = null;          // null = "not changed yet"
        pendingCoverPreview   = coverPreview;
        pendingRemoveCoverBtn = btnRemoveCover;

        btnPickCover.setOnClickListener(v -> imagePickerLauncher.launch("image/*"));

        btnRemoveCover.setOnClickListener(v -> {
            pendingCoverImageUri = "";          // "" = explicitly removed
            coverPreview.setVisibility(View.GONE);
            if (placeholder != null) placeholder.setVisibility(View.VISIBLE);
            btnRemoveCover.setVisibility(View.GONE);
        });

        new MaterialAlertDialogBuilder(requireContext())
                .setTitle("Edit Collection")
                .setView(dialogView)
                .setPositiveButton("Save", (dialog, which) -> {
                    String newName = editTextName.getText() != null
                            ? editTextName.getText().toString().trim() : "";
                    if (newName.isEmpty()) {
                        Toast.makeText(requireContext(), "Name cannot be empty",
                                Toast.LENGTH_SHORT).show();
                        return;
                    }
                    saveCollectionEdits(collection, newName, pendingCoverImageUri);
                })
                .setNegativeButton("Cancel", (dialog, which) -> {
                    // Clean up pending state
                    pendingEditCollection = null;
                    pendingCoverImageUri  = null;
                    pendingCoverPreview   = null;
                    pendingRemoveCoverBtn = null;
                })
                .show();
    }

    private void saveCollectionEdits(Collection original, String newName,
                                     @Nullable String newCoverUri) {
        executorService.execute(() -> {
            List<Collection> collections = loadCollections();

            // Check duplicate name (excluding itself)
            for (Collection c : collections) {
                if (c.getId() != original.getId() && c.getName().equalsIgnoreCase(newName)) {
                    requireActivity().runOnUiThread(() ->
                            Toast.makeText(requireContext(), "A collection with that name already exists",
                                    Toast.LENGTH_SHORT).show());
                    return;
                }
            }

            for (Collection c : collections) {
                if (c.getId() == original.getId()) {
                    c.setName(newName);
                    if (newCoverUri != null) {
                        // null  → unchanged; "" → removed; "content://..." → new image
                        c.setCoverImageUri(newCoverUri.isEmpty() ? null : newCoverUri);
                    }
                    break;
                }
            }

            saveCollections(collections);

            requireActivity().runOnUiThread(() -> {
                adapter.updateCollections(collections);
                updateEmptyState(collections.isEmpty());
                Toast.makeText(requireContext(), "Collection updated", Toast.LENGTH_SHORT).show();
                broadcastCollectionChange(ACTION_COLLECTION_CHANGED);

                // Clean up pending state
                pendingEditCollection = null;
                pendingCoverImageUri  = null;
                pendingCoverPreview   = null;
                pendingRemoveCoverBtn = null;
            });
        });
    }

    // ────────────────────────────────────────────────────────────────────────
    // Long-click → show options (edit OR delete)
    // ────────────────────────────────────────────────────────────────────────

    private void onCollectionClick(Collection collection) {
        Intent intent = new Intent(requireContext(), CollectionDetailActivity.class);
        intent.putExtra("collection", collection);
        startActivity(intent);
    }

    private void onCollectionLongClick(Collection collection) {
        showCollectionOptionsSheet(collection);
    }

    private void showCollectionOptionsSheet(Collection collection) {
        View sheetView = LayoutInflater.from(requireContext())
                .inflate(R.layout.bottom_sheet_collection_options, null);

        // Create sheet FIRST so lambdas can reference it
        BottomSheetDialog sheet = new BottomSheetDialog(requireContext());
        sheet.setContentView(sheetView);

        // Bind header
        ImageView coverImg  = sheetView.findViewById(R.id.optionsCollectionCover);
        View      coverPH   = sheetView.findViewById(R.id.optionsCoverPlaceholder);
        TextView  nameView  = sheetView.findViewById(R.id.optionsCollectionName);
        TextView  countView = sheetView.findViewById(R.id.optionsSongCount);
        TextView  optionPin = sheetView.findViewById(R.id.optionPinLabel);

        nameView.setText(collection.getName());
        int count = collection.getMusicIds() != null ? collection.getMusicIds().size() : 0;
        countView.setText(count + (count == 1 ? " song" : " songs"));

        optionPin.setText(collection.isPinned() ? "Unpin from Home" : "Pin to Home");

        String uri = collection.getCoverImageUri();
        if (uri != null && !uri.isEmpty()) {
            coverImg.setVisibility(View.VISIBLE);
            if (coverPH != null) coverPH.setVisibility(View.GONE);
            Glide.with(this).load(Uri.parse(uri)).centerCrop().into(coverImg);
        } else {
            coverImg.setVisibility(View.GONE);
            if (coverPH != null) coverPH.setVisibility(View.VISIBLE);
        }

        sheetView.findViewById(R.id.optionPinCollection).setOnClickListener(v -> {
            sheet.dismiss();
            togglePin(collection);
        });

        sheetView.findViewById(R.id.optionEditCollection).setOnClickListener(v -> {
            sheet.dismiss();
            showEditCollectionDialog(collection);
        });

        sheetView.findViewById(R.id.optionDeleteCollection).setOnClickListener(v -> {
            sheet.dismiss();
            confirmDelete(collection);
        });

        sheet.show();
    }

    private void togglePin(Collection collection) {
        executorService.execute(() -> {
            List<Collection> collections = loadCollections();
            boolean nowPinned = false;
            for (Collection c : collections) {
                if (c.getId() == collection.getId()) {
                    nowPinned = !c.isPinned();
                    c.setPinned(nowPinned);
                    break;
                }
            }
            saveCollections(collections);
            final boolean pinned = nowPinned;
            requireActivity().runOnUiThread(() -> {
                adapter.updateCollections(collections);
                broadcastCollectionChange(ACTION_COLLECTION_CHANGED);
                Toast.makeText(requireContext(),
                        pinned ? "Pinned to Home" : "Unpinned from Home",
                        Toast.LENGTH_SHORT).show();
            });
        });
    }

    private void confirmDelete(Collection collection) {
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle("Delete Collection")
                .setMessage("Are you sure you want to delete \"" + collection.getName() + "\"?")
                .setPositiveButton("Delete", (dialog, which) -> deleteCollection(collection))
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void deleteCollection(Collection collection) {
        executorService.execute(() -> {
            List<Collection> collections = loadCollections();
            collections.removeIf(c -> c.getId() == collection.getId());
            saveCollections(collections);

            requireActivity().runOnUiThread(() -> {
                adapter.updateCollections(collections);
                updateEmptyState(collections.isEmpty());
                Toast.makeText(requireContext(), "Collection deleted", Toast.LENGTH_SHORT).show();
                broadcastCollectionChange(ACTION_COLLECTION_CHANGED);
            });
        });
    }

    // ────────────────────────────────────────────────────────────────────────
    // Helpers
    // ────────────────────────────────────────────────────────────────────────

    private void createCollection(String name) {
        executorService.execute(() -> {
            List<Collection> collections = loadCollections();

            for (Collection c : collections) {
                if (c.getName().equalsIgnoreCase(name)) {
                    requireActivity().runOnUiThread(() ->
                            Toast.makeText(requireContext(), "Collection already exists",
                                    Toast.LENGTH_SHORT).show());
                    return;
                }
            }

            collections.add(new Collection(
                    System.currentTimeMillis(), name, new ArrayList<>(), System.currentTimeMillis()));
            saveCollections(collections);

            requireActivity().runOnUiThread(() -> {
                adapter.updateCollections(collections);
                updateEmptyState(collections.isEmpty());
                Toast.makeText(requireContext(), "Collection created", Toast.LENGTH_SHORT).show();
                broadcastCollectionChange(ACTION_COLLECTION_CREATED);
            });
        });
    }

    private void loadCollectionData() {
        showLoading(true);
        executorService.execute(() -> {
            List<Collection> collections = loadCollections();
            if (getActivity() != null) {
                requireActivity().runOnUiThread(() -> {
                    showLoading(false);
                    if (adapter != null) adapter.updateCollections(collections);
                    updateEmptyState(collections.isEmpty());
                });
            }
        });
    }

    private void refreshCollections() {
        if (binding == null || executorService == null || executorService.isShutdown()) return;
        executorService.execute(() -> {
            List<Collection> collections = loadCollections();
            if (getActivity() != null) {
                requireActivity().runOnUiThread(() -> {
                    if (binding != null && adapter != null) {
                        adapter.updateCollections(collections);
                        updateEmptyState(collections.isEmpty());
                    }
                });
            }
        });
    }

    private List<Collection> loadCollections() {
        String json = preferences.getString(KEY_COLLECTIONS, null);
        if (json != null) {
            Type type = new TypeToken<List<Collection>>(){}.getType();
            return gson.fromJson(json, type);
        }
        return new ArrayList<>();
    }

    private void saveCollections(List<Collection> collections) {
        preferences.edit().putString(KEY_COLLECTIONS, gson.toJson(collections)).apply();
    }

    private void broadcastCollectionChange(String action) {
        if (getContext() == null) return;
        try {
            Intent intent = new Intent(action);
            intent.setPackage(getContext().getPackageName());
            getContext().sendBroadcast(intent);
        } catch (Exception e) { e.printStackTrace(); }
    }

    private void updateEmptyState(boolean isEmpty) {
        if (binding == null) return;
        binding.collectionRecyclerView.setVisibility(isEmpty ? View.GONE  : View.VISIBLE);
        binding.emptyState.setVisibility(isEmpty ? View.VISIBLE : View.GONE);
    }

    private void showLoading(boolean show) {
        if (binding == null) return;
        binding.loadingLayout.setVisibility(show ? View.VISIBLE : View.GONE);
        if (show) {
            binding.collectionRecyclerView.setVisibility(View.GONE);
            binding.emptyState.setVisibility(View.GONE);
        }
    }

    /** Applies theme-aware stroke color to a TextInputLayout. */
    private void applyInputBoxStrokeColor(TextInputLayout til) {
        if (til == null) return;
        int[] attrs = {
                com.google.android.material.R.attr.colorOnSurface,
                com.google.android.material.R.attr.colorOnSurfaceVariant
        };
        TypedArray ta = requireContext().obtainStyledAttributes(attrs);
        int focused  = ta.getColor(0, Color.BLACK);
        int defaultC = ta.getColor(1, Color.GRAY);
        ta.recycle();

        til.setBoxStrokeColorStateList(new ColorStateList(
                new int[][] { new int[]{ android.R.attr.state_focused }, new int[]{} },
                new int[]  { focused, defaultC }
        ));
    }

    // ── BroadcastReceiver registration ───────────────────────────────────────

    private void registerCollectionUpdateReceiver() {
        if (!isReceiverRegistered && getActivity() != null) {
            try {
                IntentFilter filter = new IntentFilter();
                filter.addAction(ACTION_COLLECTION_CHANGED);
                filter.addAction(ACTION_COLLECTION_CREATED);
                filter.addAction(ACTION_SONG_ADDED_TO_COLLECTION);
                filter.addAction(ACTION_SONG_REMOVED_FROM_COLLECTION);

                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                    getActivity().registerReceiver(collectionUpdateReceiver, filter,
                            Context.RECEIVER_NOT_EXPORTED);
                } else {
                    getActivity().registerReceiver(collectionUpdateReceiver, filter);
                }
                isReceiverRegistered = true;
            } catch (Exception e) { e.printStackTrace(); }
        }
    }

    private void unregisterCollectionUpdateReceiver() {
        if (isReceiverRegistered && getActivity() != null) {
            try {
                getActivity().unregisterReceiver(collectionUpdateReceiver);
                isReceiverRegistered = false;
            } catch (Exception e) { e.printStackTrace(); }
        }
    }
}
package com.alfahrel.melody.ui.collection;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.alfahrel.melody.R;
import com.alfahrel.melody.databinding.FragmentCollectionBinding;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class CollectionFragment extends Fragment {

    private FragmentCollectionBinding binding;
    private ExecutorService executorService;
    private CollectionAdapter adapter;
    private SharedPreferences preferences;
    private static final String PREFS_NAME = "CollectionsPrefs";
    private static final String KEY_COLLECTIONS = "collections";
    private Gson gson;

    public static final String ACTION_COLLECTION_CHANGED = "com.alfahrel.melody.COLLECTION_CHANGED";
    public static final String ACTION_COLLECTION_CREATED = "com.alfahrel.melody.COLLECTION_CREATED";
    public static final String ACTION_SONG_ADDED_TO_COLLECTION = "com.alfahrel.melody.SONG_ADDED_TO_COLLECTION";
    public static final String ACTION_SONG_REMOVED_FROM_COLLECTION = "com.alfahrel.melody.SONG_REMOVED_FROM_COLLECTION";

    private boolean isReceiverRegistered = false;

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

    public View onCreateView(@NonNull LayoutInflater inflater,
                             ViewGroup container, Bundle savedInstanceState) {
        CollectionViewModel collectionViewModel =
                new ViewModelProvider(this).get(CollectionViewModel.class);

        binding = FragmentCollectionBinding.inflate(inflater, container, false);
        View root = binding.getRoot();

        executorService = Executors.newSingleThreadExecutor();
        preferences = requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        gson = new Gson();

        setupRecyclerView();
        setupAddButtons();
        loadCollectionData();

        registerCollectionUpdateReceiver();

        return root;
    }

    private void registerCollectionUpdateReceiver() {
        if (!isReceiverRegistered && getActivity() != null) {
            try {
                IntentFilter filter = new IntentFilter();
                filter.addAction(ACTION_COLLECTION_CHANGED);
                filter.addAction(ACTION_COLLECTION_CREATED);
                filter.addAction(ACTION_SONG_ADDED_TO_COLLECTION);
                filter.addAction(ACTION_SONG_REMOVED_FROM_COLLECTION);

                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                    getActivity().registerReceiver(collectionUpdateReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
                } else {
                    getActivity().registerReceiver(collectionUpdateReceiver, filter);
                }

                isReceiverRegistered = true;
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    private void unregisterCollectionUpdateReceiver() {
        if (isReceiverRegistered && getActivity() != null) {
            try {
                getActivity().unregisterReceiver(collectionUpdateReceiver);
                isReceiverRegistered = false;
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    private void refreshCollections() {
        if (binding == null || executorService == null || executorService.isShutdown()) {
            return;
        }

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

    private void setupRecyclerView() {
        RecyclerView recyclerView = binding.collectionRecyclerView;
        recyclerView.setHasFixedSize(true);

        GridLayoutManager gridLayoutManager = new GridLayoutManager(getContext(), 1);
//        gridLayoutManager.setSpanSizeLookup(new GridLayoutManager.SpanSizeLookup() {
//            @Override
//            public int getSpanSize(int position) {
//                if (adapter != null && position == adapter.getItemCount() - 1
//                        && adapter.getItemCount() > 0) {
//                    return 2;
//                }
//                return 1;
//            }
//        });
        recyclerView.setLayoutManager(gridLayoutManager);

        adapter = new CollectionAdapter(new ArrayList<>(), this::onCollectionClick, this::onCollectionLongClick);
        adapter.setOnAddCollectionClickListener(this::showAddCollectionDialog);
        recyclerView.setAdapter(adapter);
    }

    private void setupAddButtons() {
        binding.btnCreateFirstCollection.setOnClickListener(v -> showAddCollectionDialog());
    }

    private void showAddCollectionDialog() {
        View dialogView = LayoutInflater.from(requireContext())
                .inflate(R.layout.dialog_add_collection, null);

        EditText editTextName = dialogView.findViewById(R.id.editTextCollectionName);

        new MaterialAlertDialogBuilder(requireContext())
                .setTitle("New Collection")
                .setView(dialogView)
                .setPositiveButton("Create", (dialog, which) -> {
                    String collectionName = editTextName.getText().toString().trim();
                    if (!collectionName.isEmpty()) {
                        createCollection(collectionName);
                    } else {
                        Toast.makeText(requireContext(), "Collection name cannot be empty",
                                Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void createCollection(String name) {
        executorService.execute(() -> {
            List<Collection> collections = loadCollections();

            for (Collection collection : collections) {
                if (collection.getName().equalsIgnoreCase(name)) {
                    requireActivity().runOnUiThread(() ->
                            Toast.makeText(requireContext(), "Collection already exists",
                                    Toast.LENGTH_SHORT).show());
                    return;
                }
            }

            Collection newCollection = new Collection(
                    System.currentTimeMillis(),
                    name,
                    new ArrayList<>(),
                    System.currentTimeMillis()
            );

            collections.add(newCollection);
            saveCollections(collections);

            requireActivity().runOnUiThread(() -> {
                adapter.updateCollections(collections);
                updateEmptyState(collections.isEmpty());
                Toast.makeText(requireContext(), "Collection created", Toast.LENGTH_SHORT).show();

                broadcastCollectionChange(ACTION_COLLECTION_CREATED);
            });
        });
    }

    private void onCollectionClick(Collection collection) {
        Intent intent = new Intent(requireContext(), CollectionDetailActivity.class);
        intent.putExtra("collection", collection);
        startActivity(intent);
    }

    private void onCollectionLongClick(Collection collection) {
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

    private void loadCollectionData() {
        showLoading(true);

        executorService.execute(() -> {
            List<Collection> collections = loadCollections();

            if (getActivity() != null) {
                requireActivity().runOnUiThread(() -> {
                    showLoading(false);
                    if (adapter != null) {
                        adapter.updateCollections(collections);
                    }
                    updateEmptyState(collections.isEmpty());
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
        String json = gson.toJson(collections);
        preferences.edit().putString(KEY_COLLECTIONS, json).apply();
    }

    private void broadcastCollectionChange(String action) {
        if (getContext() == null) return;

        try {
            Intent intent = new Intent(action);
            intent.setPackage(getContext().getPackageName());
            getContext().sendBroadcast(intent);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void updateEmptyState(boolean isEmpty) {
        if (binding == null) return;

        if (isEmpty) {
            binding.collectionRecyclerView.setVisibility(View.GONE);
            binding.emptyState.setVisibility(View.VISIBLE);
        } else {
            binding.collectionRecyclerView.setVisibility(View.VISIBLE);
            binding.emptyState.setVisibility(View.GONE);
        }
    }

    private void showLoading(boolean show) {
        if (binding == null) return;

        if (show) {
            binding.loadingLayout.setVisibility(View.VISIBLE);
            binding.collectionRecyclerView.setVisibility(View.GONE);
            binding.emptyState.setVisibility(View.GONE);
        } else {
            binding.loadingLayout.setVisibility(View.GONE);
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        refreshCollections();
    }

    @Override
    public void onPause() {
        super.onPause();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();

        unregisterCollectionUpdateReceiver();

        if (executorService != null && !executorService.isShutdown()) {
            executorService.shutdown();
        }
        binding = null;
    }
}
package com.alfahrel.melody.ui.collection;

import android.content.Context;
import android.content.SharedPreferences;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;

/**
 * Utility class to manage collections and add songs to them
 */
public class CollectionManager {

    private static final String PREFS_NAME = "CollectionsPrefs";
    private static final String KEY_COLLECTIONS = "collections";

    private SharedPreferences preferences;
    private Gson gson;

    public CollectionManager(Context context) {
        preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        gson = new Gson();
    }

    /**
     * Get all collections
     */
    public List<Collection> getAllCollections() {
        String json = preferences.getString(KEY_COLLECTIONS, null);
        if (json != null) {
            Type type = new TypeToken<List<Collection>>(){}.getType();
            return gson.fromJson(json, type);
        }
        return new ArrayList<>();
    }

    /**
     * Add a song to a collection
     */
    public boolean addSongToCollection(long collectionId, long musicId) {
        List<Collection> collections = getAllCollections();

        for (Collection collection : collections) {
            if (collection.getId() == collectionId) {
                if (!collection.getMusicIds().contains(musicId)) {
                    collection.getMusicIds().add(musicId);
                    saveCollections(collections);
                    return true;
                }
                return false;
            }
        }
        return false;
    }

    /**
     * Remove a song from a collection
     */
    public boolean removeSongFromCollection(long collectionId, long musicId) {
        List<Collection> collections = getAllCollections();

        for (Collection collection : collections) {
            if (collection.getId() == collectionId) {
                boolean removed = collection.getMusicIds().remove(Long.valueOf(musicId));
                if (removed) {
                    saveCollections(collections);
                }
                return removed;
            }
        }
        return false;
    }

    /**
     * Get a specific collection by ID
     */
    public Collection getCollection(long collectionId) {
        List<Collection> collections = getAllCollections();

        for (Collection collection : collections) {
            if (collection.getId() == collectionId) {
                return collection;
            }
        }
        return null;
    }

    /**
     * Update an existing collection (including music IDs order)
     * Used when reordering songs in a collection via drag-and-drop
     * @param collection The collection to update with new order
     * @return true if successful, false otherwise
     */
    public boolean updateCollection(Collection collection) {
        if (collection == null) {
            return false;
        }

        List<Collection> collections = getAllCollections();

        for (int i = 0; i < collections.size(); i++) {
            if (collections.get(i).getId() == collection.getId()) {
                collections.set(i, collection);
                saveCollections(collections);
                return true;
            }
        }

        return false;
    }

    /**
     * Check if a song is in a collection
     */
    public boolean isSongInCollection(long collectionId, long musicId) {
        Collection collection = getCollection(collectionId);
        return collection != null && collection.getMusicIds().contains(musicId);
    }

    /**
     * Get all collections that contain a specific song
     */
    public List<Collection> getCollectionsContainingSong(long musicId) {
        List<Collection> allCollections = getAllCollections();
        List<Collection> result = new ArrayList<>();

        for (Collection collection : allCollections) {
            if (collection.getMusicIds().contains(musicId)) {
                result.add(collection);
            }
        }

        return result;
    }

    /**
     * Create a new collection
     */
    public Collection createCollection(String name) {
        List<Collection> collections = getAllCollections();

        Collection newCollection = new Collection(
                System.currentTimeMillis(),
                name,
                new ArrayList<>(),
                System.currentTimeMillis()
        );

        collections.add(newCollection);
        saveCollections(collections);

        return newCollection;
    }

    /**
     * Delete a collection
     */
    public boolean deleteCollection(long collectionId) {
        List<Collection> collections = getAllCollections();
        boolean removed = collections.removeIf(c -> c.getId() == collectionId);

        if (removed) {
            saveCollections(collections);
        }

        return removed;
    }

    /**
     * Update collection name
     */
    public boolean updateCollectionName(long collectionId, String newName) {
        List<Collection> collections = getAllCollections();

        for (Collection collection : collections) {
            if (collection.getId() == collectionId) {
                collection.setName(newName);
                saveCollections(collections);
                return true;
            }
        }
        return false;
    }

    /**
     * Save collections to SharedPreferences
     */
    private void saveCollections(List<Collection> collections) {
        String json = gson.toJson(collections);
        preferences.edit().putString(KEY_COLLECTIONS, json).apply();
    }
}
package com.alfahrel.melody.utils;

import android.content.Context;
import android.content.SharedPreferences;

public class PlayCountManager {

    private static final String PREFS_NAME = "play_counts";
    private final SharedPreferences prefs;

    public PlayCountManager(Context context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    public void increment(long songId) {
        int current = prefs.getInt(String.valueOf(songId), 0);
        prefs.edit().putInt(String.valueOf(songId), current + 1).apply();
    }

    public int getCount(long songId) {
        return prefs.getInt(String.valueOf(songId), 0);
    }
}
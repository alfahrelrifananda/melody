package com.alfahrel.melody.utils;

import android.net.Uri;

public class PinnedItem {

    public enum Type { COLLECTION, ALBUM, ARTIST, SONG }

    private final Type   type;
    private final String id;
    private final String name;
    private final String subtitle;
    private final Uri    coverUri;
    private final Object original;

    public PinnedItem(Type type, String id, String name, String subtitle, Uri coverUri, Object original) {
        this.type     = type;
        this.id       = id;
        this.name     = name;
        this.subtitle = subtitle;
        this.coverUri = coverUri;
        this.original = original;
    }

    public Type   getType()     { return type; }
    public String getId()       { return id; }
    public String getName()     { return name; }
    public String getSubtitle() { return subtitle; }
    public Uri    getCoverUri() { return coverUri; }
    public Object getOriginal() { return original; }
}
package com.alfahrel.melody.widget;

/**
 * Simple data holder representing a selectable source (Collection or Album)
 * shown in the widget configuration screen.
 */
public class WidgetSource {

    /** One of {@link MelodyWidget#TYPE_COLLECTION} or {@link MelodyWidget#TYPE_ALBUM}. */
    public final String type;

    /** Collection ID or MediaStore Album ID. */
    public final long id;

    /** Display name (collection name or album title). */
    public final String name;

    /** Short subtitle shown below the name (e.g. "12 songs" or "Artist • 5 songs"). */
    public final String subtitle;

    /** Content URI string for the cover art, or {@code null} if none. */
    public final String coverUri;

    public WidgetSource(String type, long id, String name, String subtitle, String coverUri) {
        this.type     = type;
        this.id       = id;
        this.name     = name;
        this.subtitle = subtitle;
        this.coverUri = coverUri;
    }
}
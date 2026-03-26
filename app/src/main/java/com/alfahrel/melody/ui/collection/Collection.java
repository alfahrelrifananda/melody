package com.alfahrel.melody.ui.collection;

import android.os.Parcel;
import android.os.Parcelable;

import java.util.ArrayList;
import java.util.List;

public class Collection implements Parcelable {
    private long id;
    private String name;
    private List<Long> musicIds;
    private long createdAt;
    private String coverImageUri; // NEW: stores URI string of the cover image

    public Collection(long id, String name, List<Long> musicIds, long createdAt) {
        this.id = id;
        this.name = name;
        this.musicIds = musicIds != null ? musicIds : new ArrayList<>();
        this.createdAt = createdAt;
        this.coverImageUri = null;
    }

    protected Collection(Parcel in) {
        id = in.readLong();
        name = in.readString();
        musicIds = new ArrayList<>();
        in.readList(musicIds, Long.class.getClassLoader());
        createdAt = in.readLong();
        coverImageUri = in.readString(); // NEW
    }

    public static final Creator<Collection> CREATOR = new Creator<Collection>() {
        @Override
        public Collection createFromParcel(Parcel in) {
            return new Collection(in);
        }

        @Override
        public Collection[] newArray(int size) {
            return new Collection[size];
        }
    };

    public long getId() { return id; }
    public void setId(long id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public List<Long> getMusicIds() { return musicIds; }
    public void setMusicIds(List<Long> musicIds) { this.musicIds = musicIds; }

    public long getCreatedAt() { return createdAt; }
    public void setCreatedAt(long createdAt) { this.createdAt = createdAt; }

    // NEW getter/setter for cover image
    public String getCoverImageUri() { return coverImageUri; }
    public void setCoverImageUri(String coverImageUri) { this.coverImageUri = coverImageUri; }

    public int getSongCount() {
        return musicIds != null ? musicIds.size() : 0;
    }

    @Override
    public int describeContents() { return 0; }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeLong(id);
        dest.writeString(name);
        dest.writeList(musicIds);
        dest.writeLong(createdAt);
        dest.writeString(coverImageUri); // NEW
    }
}
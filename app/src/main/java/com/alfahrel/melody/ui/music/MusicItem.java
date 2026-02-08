package com.alfahrel.melody.ui.music;

import android.net.Uri;
import android.os.Parcel;
import android.os.Parcelable;

public class MusicItem implements Parcelable {
    private long id;
    private String title;
    private String artist;
    private String album;
    private long duration;
    private String path;
    private Uri albumArtUri;

    public MusicItem(long id, String title, String artist, String album,
                     long duration, String path, Uri albumArtUri) {
        this.id = id;
        this.title = title;
        this.artist = artist;
        this.album = album;
        this.duration = duration;
        this.path = path;
        this.albumArtUri = albumArtUri;
    }

    protected MusicItem(Parcel in) {
        id = in.readLong();
        title = in.readString();
        artist = in.readString();
        album = in.readString();
        duration = in.readLong();
        path = in.readString();
        albumArtUri = in.readParcelable(Uri.class.getClassLoader());
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeLong(id);
        dest.writeString(title);
        dest.writeString(artist);
        dest.writeString(album);
        dest.writeLong(duration);
        dest.writeString(path);
        dest.writeParcelable(albumArtUri, flags);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    public static final Creator<MusicItem> CREATOR = new Creator<MusicItem>() {
        @Override
        public MusicItem createFromParcel(Parcel in) {
            return new MusicItem(in);
        }

        @Override
        public MusicItem[] newArray(int size) {
            return new MusicItem[size];
        }
    };

    public long getId() { return id; }
    public String getTitle() { return title; }
    public String getArtist() { return artist; }
    public String getAlbum() { return album; }
    public long getDuration() { return duration; }
    public String getPath() { return path; }
    public Uri getAlbumArtUri() { return albumArtUri; }

    public void setId(long id) { this.id = id; }
    public void setTitle(String title) { this.title = title; }
    public void setArtist(String artist) { this.artist = artist; }
    public void setAlbum(String album) { this.album = album; }
    public void setDuration(long duration) { this.duration = duration; }
    public void setPath(String path) { this.path = path; }
    public void setAlbumArtUri(Uri albumArtUri) { this.albumArtUri = albumArtUri; }
}
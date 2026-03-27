package com.alfahrel.melody.ui.album;

import android.net.Uri;
import android.os.Parcel;
import android.os.Parcelable;

public class AlbumItem implements Parcelable {
    private long albumId;
    private String albumName;
    private String artistName;
    private Uri albumArtUri;
    private int songCount;
    private boolean pinned;

    public AlbumItem(long albumId, String albumName, String artistName, Uri albumArtUri, int songCount) {
        this.albumId = albumId;
        this.albumName = albumName != null ? albumName : "Unknown Album";
        this.artistName = artistName != null ? artistName : "Unknown Artist";
        this.albumArtUri = albumArtUri;
        this.songCount = songCount;
        this.pinned = false;
    }

    protected AlbumItem(Parcel in) {
        albumId = in.readLong();
        albumName = in.readString();
        artistName = in.readString();
        albumArtUri = in.readParcelable(Uri.class.getClassLoader());
        songCount = in.readInt();
        pinned = in.readByte() != 0;
    }

    public static final Creator<AlbumItem> CREATOR = new Creator<AlbumItem>() {
        @Override
        public AlbumItem createFromParcel(Parcel in) {
            return new AlbumItem(in);
        }

        @Override
        public AlbumItem[] newArray(int size) {
            return new AlbumItem[size];
        }
    };

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeLong(albumId);
        dest.writeString(albumName);
        dest.writeString(artistName);
        dest.writeParcelable(albumArtUri, flags);
        dest.writeInt(songCount);
        dest.writeByte((byte) (pinned ? 1 : 0));
    }

    public long getAlbumId() { return albumId; }
    public String getAlbumName() { return albumName; }
    public String getArtistName() { return artistName; }
    public Uri getAlbumArtUri() { return albumArtUri; }
    public int getSongCount() { return songCount; }
    public boolean isPinned() { return pinned; }

    public void setAlbumId(long albumId) { this.albumId = albumId; }
    public void setAlbumName(String albumName) { this.albumName = albumName; }
    public void setArtistName(String artistName) { this.artistName = artistName; }
    public void setAlbumArtUri(Uri albumArtUri) { this.albumArtUri = albumArtUri; }
    public void setSongCount(int songCount) { this.songCount = songCount; }
    public void setPinned(boolean pinned) { this.pinned = pinned; }

    public String getFormattedSongCount() {
        return songCount == 1 ? songCount + " song" : songCount + " songs";
    }
}
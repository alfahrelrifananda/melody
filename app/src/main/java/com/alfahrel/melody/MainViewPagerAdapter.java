package com.alfahrel.melody;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.viewpager2.adapter.FragmentStateAdapter;

import com.alfahrel.melody.ui.album.AlbumFragment;
import com.alfahrel.melody.ui.artist.ArtistFragment;
import com.alfahrel.melody.ui.collection.CollectionFragment;
import com.alfahrel.melody.ui.music.MusicFragment;

public class MainViewPagerAdapter extends FragmentStateAdapter {

    public MainViewPagerAdapter(@NonNull FragmentActivity fragmentActivity) {
        super(fragmentActivity);
    }

    @NonNull
    @Override
    public Fragment createFragment(int position) {
        switch (position) {
            case 0:
                return new MusicFragment();
            case 1:
                return new AlbumFragment();
            case 2:
                return new ArtistFragment();
            case 3:
                return new CollectionFragment();
            default:
                return new MusicFragment();
        }
    }

    @Override
    public int getItemCount() {
        return 4;
    }
}
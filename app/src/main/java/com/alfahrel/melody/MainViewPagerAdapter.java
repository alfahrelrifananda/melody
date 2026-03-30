package com.alfahrel.melody;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.viewpager2.adapter.FragmentStateAdapter;

import com.alfahrel.melody.ui.collection.CollectionFragment;
import com.alfahrel.melody.ui.home.HomeFragment;
import com.alfahrel.melody.ui.search.SearchFragment;

public class MainViewPagerAdapter extends FragmentStateAdapter {

    public MainViewPagerAdapter(@NonNull FragmentActivity fragmentActivity) {
        super(fragmentActivity);
    }

    @NonNull
    @Override
    public Fragment createFragment(int position) {
        switch (position) {
            case 0: return new HomeFragment();
            case 1: return new SearchFragment();
            case 2: return new CollectionFragment();
            default:
                return new HomeFragment();
        }
    }

    @Override
    public int getItemCount() {
        return 3;
    }
}
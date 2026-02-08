package com.alfahrel.melody.ui.artist;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;

public class ArtistViewModel extends ViewModel {

    private final MutableLiveData<String> mText;

    public ArtistViewModel() {
        mText = new MutableLiveData<>();
        mText.setValue("This is artist fragment");
    }

    public LiveData<String> getText() {
        return mText;
    }
}
package com.alfahrel.melody.ui.collection;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;

public class CollectionViewModel extends ViewModel {

    private final MutableLiveData<String> mText;

    public CollectionViewModel() {
        mText = new MutableLiveData<>();
        mText.setValue("This is artist fragment");
    }

    public LiveData<String> getText() {
        return mText;
    }
}
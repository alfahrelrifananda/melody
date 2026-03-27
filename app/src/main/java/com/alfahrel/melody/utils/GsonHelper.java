package com.alfahrel.melody.utils;

import android.net.Uri;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.TypeAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;
import java.io.IOException;

public class GsonHelper {
    private static final Gson INSTANCE = new GsonBuilder()
            .registerTypeHierarchyAdapter(Uri.class, new TypeAdapter<Uri>() {
                @Override
                public void write(JsonWriter out, Uri value) throws IOException {
                    if (value == null) out.nullValue();
                    else out.value(value.toString());
                }
                @Override
                public Uri read(JsonReader in) throws IOException {
                    if (in.peek() == JsonToken.NULL) { in.nextNull(); return null; }
                    return Uri.parse(in.nextString());
                }
            })
            .create();

    public static Gson get() { return INSTANCE; }
}
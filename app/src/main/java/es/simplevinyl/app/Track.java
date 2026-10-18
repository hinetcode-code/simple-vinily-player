package es.simplevinyl.app;

import android.content.ContentUris;
import android.net.Uri;
import android.provider.MediaStore;

public class Track {
    public long id;
    public String title = "";
    public String artist = "";
    public String albumKey = "";
    public int number;
    public long durationMs;

    public Uri uri() {
        return ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id);
    }
}

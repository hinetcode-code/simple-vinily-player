package es.simplevinyl.app;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Lee la música del dispositivo (MediaStore) y la agrupa por álbum. */
public final class Library {
    private Library() {}

    public static Uri artUri(long albumId) {
        return Uri.parse("content://media/external/audio/albumart/" + albumId);
    }

    public static List<Album> scan(Context ctx, JSONObject meta) {
        ContentResolver cr = ctx.getContentResolver();
        Map<Long, Album> map = new LinkedHashMap<>();
        Map<Long, Map<String, Integer>> artists = new HashMap<>();
        String[] proj = {
                MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.ARTIST,
                MediaStore.Audio.Media.ALBUM, MediaStore.Audio.Media.ALBUM_ID, MediaStore.Audio.Media.TRACK,
                MediaStore.Audio.Media.YEAR, MediaStore.Audio.Media.DURATION, MediaStore.Audio.Media.DATE_ADDED};
        Cursor c = null;
        try {
            c = cr.query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, proj,
                    MediaStore.Audio.Media.IS_MUSIC + " != 0", null,
                    MediaStore.Audio.Media.ALBUM + " COLLATE NOCASE ASC");
            while (c != null && c.moveToNext()) {
                long albumId = c.getLong(4);
                Album a = map.get(albumId);
                if (a == null) {
                    a = new Album();
                    a.albumId = albumId;
                    a.key = String.valueOf(albumId);
                    String t = c.getString(3);
                    a.title = (t == null || t.equals("<unknown>")) ? "Álbum" : t;
                    map.put(albumId, a);
                    artists.put(albumId, new HashMap<String, Integer>());
                }
                Track tr = new Track();
                tr.id = c.getLong(0);
                tr.title = c.getString(1) == null ? "" : c.getString(1);
                String ar = c.getString(2);
                tr.artist = (ar == null || ar.equals("<unknown>")) ? "Desconocido" : ar;
                tr.albumKey = a.key;
                tr.number = c.getInt(5) % 1000;
                tr.durationMs = c.getLong(7);
                a.tracks.add(tr);
                a.dateAdded = Math.max(a.dateAdded, c.getLong(8));
                int y = c.getInt(6);
                if (a.year.isEmpty() && y > 1000) a.year = String.valueOf(y);
                Map<String, Integer> m = artists.get(albumId);
                m.put(tr.artist, (m.containsKey(tr.artist) ? m.get(tr.artist) : 0) + 1);
            }
        } catch (Exception ignored) {
        } finally {
            if (c != null) c.close();
        }
        List<Album> out = new ArrayList<>(map.values());
        for (Album a : out) {
            String best = "Desconocido";
            int bn = -1;
            for (Map.Entry<String, Integer> e : artists.get(a.albumId).entrySet()) {
                if (e.getValue() > bn) {
                    bn = e.getValue();
                    best = e.getKey();
                }
            }
            a.artist = best;
            Collections.sort(a.tracks, new Comparator<Track>() {
                @Override
                public int compare(Track x, Track y) {
                    int nx = x.number == 0 ? 9999 : x.number, ny = y.number == 0 ? 9999 : y.number;
                    if (nx != ny) return nx < ny ? -1 : 1;
                    return x.title.toLowerCase(Locale.ROOT).compareTo(y.title.toLowerCase(Locale.ROOT));
                }
            });
            // carátula incrustada / de la carpeta, que Android ya indexa
            try {
                ParcelFileDescriptor pfd = cr.openFileDescriptor(artUri(a.albumId), "r");
                if (pfd != null) {
                    pfd.close();
                    a.hasCover = true;
                    a.cover = artUri(a.albumId).toString();
                }
            } catch (Exception ignored) {
            }
            Meta.apply(a, meta);
        }
        return out;
    }
}

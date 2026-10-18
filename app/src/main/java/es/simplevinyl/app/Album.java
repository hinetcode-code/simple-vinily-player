package es.simplevinyl.app;

import java.util.ArrayList;
import java.util.List;

public class Album {
    public String key = "";        // id del álbum en MediaStore
    public long albumId;
    public String title = "";
    public String artist = "";
    public String year = "";
    public String genre = "";
    public String cover;           // ruta de archivo o content:// (null si no hay)
    public boolean hasCover;
    public long dateAdded;
    public final List<Track> tracks = new ArrayList<>();

    /** Clave estable para guardar datos obtenidos de internet. */
    public String metaKey() {
        return Meta.norm(artist) + "|" + Meta.norm(title);
    }
}

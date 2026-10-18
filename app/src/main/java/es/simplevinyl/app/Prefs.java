package es.simplevinyl.app;

import android.content.Context;
import android.content.SharedPreferences;

public final class Prefs {
    private final SharedPreferences sp;

    public Prefs(Context c) {
        sp = c.getApplicationContext().getSharedPreferences("svp", Context.MODE_PRIVATE);
    }

    public String sort() { return sp.getString("sort", "artist"); }
    public void setSort(String v) { sp.edit().putString("sort", v).apply(); }

    public boolean spin() { return sp.getBoolean("spin", true); }
    public boolean arm() { return sp.getBoolean("arm", true); }
    public boolean pinPlayer() { return sp.getBoolean("pin_player", false); }
    public boolean autoMeta() { return sp.getBoolean("auto_meta", true); }
    public int accent() { return sp.getInt("accent", 0); }

    public void setSettings(boolean spin, boolean arm, boolean pin, boolean auto, int accent) {
        sp.edit().putBoolean("spin", spin).putBoolean("arm", arm).putBoolean("pin_player", pin)
                .putBoolean("auto_meta", auto).putInt("accent", accent).apply();
    }

    public String lastAlbum() { return sp.getString("last_album", ""); }
    public int lastTrack() { return sp.getInt("last_track", 0); }

    public void setLast(String album, int track) {
        sp.edit().putString("last_album", album).putInt("last_track", track).apply();
    }
}

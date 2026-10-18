package es.simplevinyl.app;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.security.MessageDigest;
import java.text.Normalizer;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Carátulas y metadatos automáticos: iTunes y, si no aparece, MusicBrainz + Cover Art Archive.
 * Lo encontrado se guarda aparte (no toca los archivos de audio).
 */
public final class Meta {
    public static class NetException extends Exception {
        public NetException(String m) { super(m); }
    }

    public static class Result {
        public String title = "", artist = "", year = "", genre = "", coverUrl = "";
    }

    private static final Object LOCK = new Object();

    private Meta() {}

    // ---- almacenamiento ----
    private static SharedPreferences sp(Context c) {
        return c.getApplicationContext().getSharedPreferences("svp_meta", Context.MODE_PRIVATE);
    }

    public static JSONObject load(Context c) {
        synchronized (LOCK) {
            try {
                return new JSONObject(sp(c).getString("data", "{}"));
            } catch (JSONException e) {
                return new JSONObject();
            }
        }
    }

    public static void update(Context c, String key, JSONObject patch) {
        synchronized (LOCK) {
            try {
                JSONObject all = new JSONObject(sp(c).getString("data", "{}"));
                JSONObject cur = all.optJSONObject(key);
                if (cur == null) cur = new JSONObject();
                JSONArray names = patch.names();
                if (names != null) {
                    for (int i = 0; i < names.length(); i++) {
                        String k = names.getString(i);
                        cur.put(k, patch.get(k));
                    }
                }
                all.put(key, cur);
                sp(c).edit().putString("data", all.toString()).apply();
            } catch (JSONException ignored) {
            }
        }
    }

    public static void apply(Album a, JSONObject all) {
        JSONObject m = all == null ? null : all.optJSONObject(a.metaKey());
        if (m == null) return;
        if (!m.optString("year").isEmpty()) a.year = m.optString("year");
        if (!m.optString("genre").isEmpty()) a.genre = m.optString("genre");
        if (isUnknown(a.artist) && !m.optString("artist").isEmpty()) a.artist = m.optString("artist");
        String cover = m.optString("cover");
        if (!cover.isEmpty() && new File(cover).exists()) {
            a.cover = cover;
            a.hasCover = true;
        }
    }

    public static boolean isUnknown(String artist) {
        return artist == null || artist.isEmpty() || artist.equals("Desconocido");
    }

    public static boolean needs(Album a, JSONObject all) {
        JSONObject m = all.optJSONObject(a.metaKey());
        long tried = m == null ? 0 : m.optLong("tried", 0);
        boolean missing = !a.hasCover || a.year.isEmpty() || a.genre.isEmpty();
        return missing && System.currentTimeMillis() - tried > 30L * 24 * 3600 * 1000;
    }

    // ---- coincidencia de nombres ----
    public static String norm(String t) {
        if (t == null) return "";
        String n = Normalizer.normalize(t, Normalizer.Form.NFD).replaceAll("\\p{M}+", "").toLowerCase(Locale.ROOT);
        return n.replaceAll("[^a-z0-9]+", " ").trim();
    }

    static String clean(String t) {
        return (t == null ? "" : t).replaceAll("[\\(\\[][^\\)\\]]*[\\)\\]]", " ").replaceAll("\\s+", " ").trim();
    }

    /** Similitud 0..1 (coeficiente de Dice sobre pares de letras). */
    static double sim(String a, String b) {
        String x = norm(a), y = norm(b);
        if (x.equals(y)) return x.isEmpty() ? 0 : 1;
        if (x.length() < 2 || y.length() < 2) return 0;
        Map<String, Integer> bg = new HashMap<>();
        for (int i = 0; i < x.length() - 1; i++) {
            String g = x.substring(i, i + 2);
            Integer cur = bg.get(g);
            bg.put(g, cur == null ? 1 : cur + 1);
        }
        int inter = 0;
        for (int i = 0; i < y.length() - 1; i++) {
            String g = y.substring(i, i + 2);
            Integer n = bg.get(g);
            if (n != null && n > 0) {
                inter++;
                bg.put(g, n - 1);
            }
        }
        return 2.0 * inter / ((x.length() - 1) + (y.length() - 1));
    }

    private static boolean pick(String ct, String ca, String title, String artist, double[] best) {
        boolean known = !isUnknown(artist);
        double sc = sim(clean(title), clean(ct));
        if (known) sc = 0.65 * sc + 0.35 * sim(artist, ca);
        if (sc > best[0]) {
            best[0] = sc;
            return true;
        }
        return false;
    }

    private static boolean ok(double score, String artist) {
        return score >= (isUnknown(artist) ? 0.85 : 0.72);
    }

    // ---- red ----
    private static byte[] http(String url, int timeoutMs) throws NetException {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(timeoutMs);
            c.setReadTimeout(timeoutMs);
            c.setRequestProperty("User-Agent", "SimpleVinylPlayer/1.0 (reproductor local)");
            c.setInstanceFollowRedirects(true);
            int code = c.getResponseCode();
            if (code == 403 || code == 429 || code == 503) throw new NetException("HTTP " + code);
            if (code < 200 || code >= 300) return null;
            InputStream in = c.getInputStream();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            in.close();
            return out.toByteArray();
        } catch (IOException e) {
            throw new NetException(String.valueOf(e.getMessage()));
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private static JSONObject httpJson(String url) throws NetException {
        byte[] b = http(url, 15000);
        if (b == null) return null;
        try {
            return new JSONObject(new String(b, "UTF-8"));
        } catch (Exception e) {
            return null;
        }
    }

    private static String enc(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8");
        } catch (Exception e) {
            return s;
        }
    }

    public static Result lookup(String title, String artist) throws NetException {
        boolean known = !isUnknown(artist);
        String t = clean(title).replace("\"", "");
        String q = known ? artist + " " + t : t;
        JSONObject data = httpJson("https://itunes.apple.com/search?term=" + enc(q)
                + "&entity=album&media=music&limit=10");
        if (data != null) {
            JSONArray arr = data.optJSONArray("results");
            double[] best = {0};
            JSONObject bestObj = null;
            for (int i = 0; arr != null && i < arr.length(); i++) {
                JSONObject r = arr.optJSONObject(i);
                if (r != null && pick(r.optString("collectionName"), r.optString("artistName"), title, artist, best)) {
                    bestObj = r;
                }
            }
            if (bestObj != null && ok(best[0], artist)) {
                Result res = new Result();
                res.title = bestObj.optString("collectionName");
                res.artist = bestObj.optString("artistName");
                String rd = bestObj.optString("releaseDate");
                res.year = rd.length() >= 4 ? rd.substring(0, 4) : "";
                res.genre = bestObj.optString("primaryGenreName");
                res.coverUrl = bestObj.optString("artworkUrl100").replace("100x100bb", "1000x1000bb");
                return res;
            }
        }
        try {
            Thread.sleep(1100);
        } catch (InterruptedException ignored) {
        }
        String query = "release:\"" + t + "\"" + (known ? " AND artist:\"" + artist.replace("\"", "") + "\"" : "");
        data = httpJson("https://musicbrainz.org/ws/2/release/?query=" + enc(query) + "&fmt=json&limit=8");
        if (data != null) {
            JSONArray arr = data.optJSONArray("releases");
            double[] best = {0};
            JSONObject bestObj = null;
            for (int i = 0; arr != null && i < arr.length(); i++) {
                JSONObject r = arr.optJSONObject(i);
                if (r == null) continue;
                String an = "";
                JSONArray ac = r.optJSONArray("artist-credit");
                if (ac != null && ac.length() > 0 && ac.optJSONObject(0) != null) {
                    an = ac.optJSONObject(0).optString("name");
                }
                if (pick(r.optString("title"), an, title, artist, best)) bestObj = r;
            }
            if (bestObj != null && ok(best[0], artist)) {
                Result res = new Result();
                res.title = bestObj.optString("title");
                JSONArray ac = bestObj.optJSONArray("artist-credit");
                if (ac != null && ac.length() > 0 && ac.optJSONObject(0) != null) {
                    res.artist = ac.optJSONObject(0).optString("name");
                }
                String d = bestObj.optString("date");
                res.year = d.length() >= 4 ? d.substring(0, 4) : "";
                res.coverUrl = "https://coverartarchive.org/release/" + bestObj.optString("id") + "/front-500";
                return res;
            }
        }
        return null;
    }

    private static String md5(String s) {
        try {
            byte[] d = MessageDigest.getInstance("MD5").digest(s.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format(Locale.US, "%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return String.valueOf(s.hashCode());
        }
    }

    public static File coverFile(Context c, Album a) {
        File dir = new File(c.getFilesDir(), "covers");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        return new File(dir, md5(a.metaKey()) + ".jpg");
    }

    /** Qué datos del resultado se guardan: solo lo que falta (o todo, si se pide a mano). */
    public static JSONObject fromResult(Context c, Result r, Album a, boolean force) throws NetException {
        JSONObject out = new JSONObject();
        try {
            if (!r.coverUrl.isEmpty() && (force || !a.hasCover)) {
                byte[] raw = http(r.coverUrl, 25000);
                if (raw != null && raw.length > 2000) {
                    File f = coverFile(c, a);
                    FileOutputStream fo = new FileOutputStream(f);
                    fo.write(raw);
                    fo.close();
                    out.put("cover", f.getAbsolutePath());
                }
            }
            if (r.year.matches("^(19|20)\\d{2}$") && (force || a.year.isEmpty())) out.put("year", r.year);
            if (!r.genre.isEmpty() && (force || a.genre.isEmpty())) out.put("genre", r.genre);
            if (!r.artist.isEmpty() && isUnknown(a.artist)) out.put("artist", r.artist);
        } catch (IOException | JSONException ignored) {
        }
        return out;
    }

    public static Set<String> keys(JSONObject o) {
        Set<String> s = new HashSet<>();
        JSONArray n = o.names();
        if (n != null) for (int i = 0; i < n.length(); i++) s.add(n.optString(i));
        return s;
    }
}

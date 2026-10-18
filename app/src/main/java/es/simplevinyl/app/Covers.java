package es.simplevinyl.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;

import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Carga carátulas en segundo plano con caché en memoria. */
public final class Covers {
    public interface Callback {
        void done(Bitmap bmp);
    }

    private static final ExecutorService EX = Executors.newFixedThreadPool(2);
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final LruCache<String, Bitmap> CACHE =
            new LruCache<String, Bitmap>((int) (Runtime.getRuntime().maxMemory() / 8)) {
                @Override
                protected int sizeOf(String k, Bitmap b) {
                    return b.getByteCount();
                }
            };

    private Covers() {}

    public static void load(final Context ctx, final String src, final int px, final Callback cb) {
        if (src == null) {
            cb.done(null);
            return;
        }
        final String key = src + "@" + px;
        Bitmap hit = CACHE.get(key);
        if (hit != null) {
            cb.done(hit);
            return;
        }
        final Context app = ctx.getApplicationContext();
        EX.execute(new Runnable() {
            @Override
            public void run() {
                final Bitmap b = decode(app, src, px);
                if (b != null) CACHE.put(key, b);
                MAIN.post(new Runnable() {
                    @Override
                    public void run() {
                        cb.done(b);
                    }
                });
            }
        });
    }

    public static void forget(String src) {
        CACHE.evictAll();
    }

    private static byte[] read(Context c, String src) throws Exception {
        InputStream in = src.startsWith("/") ? new FileInputStream(src)
                : c.getContentResolver().openInputStream(Uri.parse(src));
        if (in == null) return null;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        in.close();
        return out.toByteArray();
    }

    private static Bitmap decode(Context c, String src, int px) {
        try {
            byte[] data = read(c, src);
            if (data == null) return null;
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(data, 0, data.length, o);
            int s = 1;
            while (o.outWidth / (s * 2) >= px && o.outHeight / (s * 2) >= px) s *= 2;
            o = new BitmapFactory.Options();
            o.inSampleSize = s;
            return BitmapFactory.decodeByteArray(data, 0, data.length, o);
        } catch (Throwable t) {
            return null;
        }
    }
}

package es.simplevinyl.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.os.SystemClock;
import android.view.View;

import java.util.Random;

/**
 * Disco de vinilo con la portada como vinilo, etiqueta central de papel impreso (gira con el disco)
 * y el brazo del tocadiscos, que avanza por el álbum.
 */
public class DiscView extends View {
    private static final float LABEL = 0.30f;     // radio de la etiqueta respecto al disco
    private static final float ARM_REST = -0.55f;
    private static final float RPM = 33.3f;

    private Bitmap cover;
    private Bitmap vinyl, label;
    private int vinylKey, labelKey;
    private boolean vinylDirty = true, labelDirty = true;
    private boolean spinning, armOn = true;
    private float angle;
    private long lastNs;
    private float armCur = ARM_REST, armTarget = ARM_REST;
    private int accent = 0;
    private String labelArtist = "", labelTitle = "Simple Vinyl Player";
    private Bitmap grain;

    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
    private final Path path = new Path();

    public DiscView(Context c) {
        super(c);
    }

    // ---- estado ----
    public void setCover(Bitmap b) {
        if (b != cover) {
            cover = b;
            vinylDirty = true;
            invalidate();
        }
    }

    public void setAccent(int idx) {
        if (idx != accent) {
            accent = idx;
            vinylDirty = labelDirty = true;
            invalidate();
        }
    }

    public void setArmEnabled(boolean on) { armOn = on; invalidate(); }

    /** 0..1 a lo largo del álbum; null = brazo en reposo. */
    public void setArmTarget(Float t) {
        armTarget = t == null ? ARM_REST : Math.max(0f, Math.min(1f, t));
        invalidate();
    }

    public void setLabelText(String artist, String title) {
        if (!artist.equals(labelArtist) || !title.equals(labelTitle)) {
            labelArtist = artist;
            labelTitle = title;
            labelDirty = true;
            invalidate();
        }
    }

    public void setSpinning(boolean on) {
        if (on && !spinning) lastNs = SystemClock.elapsedRealtimeNanos();
        spinning = on;
        invalidate();
    }

    private float radius(int w, int h) {
        float R = Math.max(U.dp(getContext(), 40), Math.min(w, h) * 0.42f);
        if (armOn) {
            R = Math.max(U.dp(getContext(), 40), Math.min(R,
                    Math.min((w / 2f - U.dp(getContext(), 14)) / 1.30f, (h / 2f - U.dp(getContext(), 10)) / 1.12f)));
        }
        return R;
    }

    // ---- dibujo ----
    @Override
    protected void onDraw(Canvas c) {
        int w = getWidth(), h = getHeight();
        if (w < 8 || h < 8) return;
        float R = radius(w, h), cx = w / 2f, cy = h / 2f, lr = R * LABEL;
        int d = Math.round(R) * 2;

        long now = SystemClock.elapsedRealtimeNanos();
        boolean animate = false;
        if (spinning) {
            if (lastNs != 0) angle = (angle + (now - lastNs) / 1e9f * 360f * RPM / 60f) % 360f;
            lastNs = now;
            animate = true;
        }
        if (Math.abs(armTarget - armCur) > 0.002f) {
            armCur += (armTarget - armCur) * 0.07f;
            animate = true;
        } else {
            armCur = armTarget;
        }

        if (vinyl == null || vinylDirty || vinylKey != d) {
            vinyl = renderVinyl(d);
            vinylKey = d;
            vinylDirty = false;
        }
        int lk = Math.round(lr);
        if (label == null || labelDirty || labelKey != lk) {
            label = renderLabel(lk);
            labelKey = lk;
            labelDirty = false;
        }

        c.save();
        c.rotate(angle, cx, cy);
        p.setAlpha(255);
        c.drawBitmap(vinyl, cx - d / 2f, cy - d / 2f, p);
        c.drawBitmap(label, cx - label.getWidth() / 2f, cy - label.getHeight() / 2f, p);
        c.restore();

        // reflejos fijos (no giran con el disco)
        p.setStyle(Paint.Style.FILL);
        p.setColor(Color.argb(10, 255, 255, 255));
        RectF oval = new RectF(cx - R + 3, cy - R + 3, cx + R - 3, cy + R - 3);
        c.drawArc(oval, 198f, 18f, true, p);
        c.drawArc(oval, 309f, 18f, true, p);

        if (armOn) drawArm(c, cx, cy, R);
        if (animate) postInvalidateOnAnimation();
    }

    // ---- vinilo ----
    private Bitmap renderVinyl(int d) {
        Bitmap bm = Bitmap.createBitmap(d, d, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bm);
        float r = d / 2f;
        Paint q = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        q.setShader(new RadialGradient(r, r, r, new int[]{0xFF1A1A20, 0xFF08080A}, null, Shader.TileMode.CLAMP));
        c.drawCircle(r, r, r, q);
        q.setShader(null);
        if (cover != null) {
            c.save();
            path.reset();
            path.addCircle(r, r, r - 1, Path.Direction.CW);
            c.clipPath(path);
            int s = Math.min(cover.getWidth(), cover.getHeight());
            Rect src = new Rect((cover.getWidth() - s) / 2, (cover.getHeight() - s) / 2,
                    (cover.getWidth() + s) / 2, (cover.getHeight() + s) / 2);
            q.setAlpha(210);
            c.drawBitmap(cover, src, new Rect(0, 0, d, d), q);
            q.setAlpha(255);
            c.restore();
        }
        // sombreado hacia el borde y surcos
        q.setShader(new RadialGradient(r, r, r, new int[]{0x00000000, 0x00000000, 0x66000000},
                new float[]{0f, 0.7f, 1f}, Shader.TileMode.CLAMP));
        c.drawCircle(r, r, r, q);
        q.setShader(null);
        q.setStyle(Paint.Style.STROKE);
        q.setStrokeWidth(1f);
        float step = Math.max(2f, r * 0.012f);
        int i = 0;
        for (float rr = r * 0.34f; rr < r * 0.98f; rr += step, i++) {
            q.setColor(i % 2 == 0 ? Color.argb(16, 255, 255, 255) : Color.argb(26, 0, 0, 0));
            c.drawCircle(r, r, rr, q);
        }
        q.setStrokeWidth(Math.max(2f, r * 0.012f));
        q.setColor(Color.argb(46, 255, 255, 255));
        c.drawCircle(r, r, r - q.getStrokeWidth() / 2f, q);
        return bm;
    }

    // ---- etiqueta de papel ----
    private Bitmap grainTile() {
        if (grain == null) {
            int n = 96;
            int[] px = new int[n * n];
            Random rnd = new Random(7);
            for (int i = 0; i < px.length; i++) {
                int v = rnd.nextInt(256);
                int a = 6 + (v >> 3) % 20;
                px[i] = (v & 1) == 1 ? Color.argb(a, 255, 255, 255) : Color.argb(a, 0, 0, 0);
            }
            grain = Bitmap.createBitmap(px, n, n, Bitmap.Config.ARGB_8888);
        }
        return grain;
    }

    private Bitmap renderLabel(int lr) {
        int pad = 6, n = lr * 2 + pad * 2;
        Bitmap bm = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bm);
        c.translate(n / 2f, n / 2f);
        int a1 = Accent.a(accent);
        float[] hsv = new float[3];
        Color.colorToHSV(a1, hsv);
        int base = Color.rgb(Math.round(Color.red(a1) * 0.50f) + 8, Math.round(Color.green(a1) * 0.50f) + 8,
                Math.round(Color.blue(a1) * 0.50f) + 8);
        int light = Color.rgb(Math.min(255, Math.round(Color.red(base) * 1.45f) + 10),
                Math.min(255, Math.round(Color.green(base) * 1.45f) + 10),
                Math.min(255, Math.round(Color.blue(base) * 1.45f) + 10));
        int dark = Color.rgb(Math.round(Color.red(base) * 0.78f), Math.round(Color.green(base) * 0.78f),
                Math.round(Color.blue(base) * 0.78f));
        int ink = Color.rgb(245, 235, 214);
        Paint q = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);

        path.reset();
        path.addCircle(0, 0, lr, Path.Direction.CW);
        c.save();
        c.clipPath(path);
        q.setShader(new RadialGradient(-lr * 0.25f, -lr * 0.3f, lr * 1.25f, new int[]{light, base, dark},
                new float[]{0f, 0.7f, 1f}, Shader.TileMode.CLAMP));
        c.drawCircle(0, 0, lr, q);
        q.setShader(new BitmapShader(grainTile(), Shader.TileMode.REPEAT, Shader.TileMode.REPEAT));
        q.setAlpha(140);
        c.drawRect(-lr, -lr, lr, lr, q);
        q.setShader(null);
        q.setAlpha(255);

        // anillos impresos
        q.setStyle(Paint.Style.STROKE);
        q.setStrokeWidth(Math.max(1f, lr * 0.008f));
        float[][] rings = {{0.93f, 0.34f}, {0.60f, 0.26f}, {0.47f, 0.16f}};
        for (float[] rg : rings) {
            q.setColor(U.alpha(ink, rg[1]));
            c.drawCircle(0, 0, lr * rg[0], q);
        }

        // texto impreso
        q.setStyle(Paint.Style.FILL);
        q.setTypeface(Typeface.DEFAULT_BOLD);
        q.setColor(U.alpha(ink, 0.88f));
        q.setLetterSpacing(0.08f);
        String artist = (labelArtist.isEmpty() ? "SIMPLE VINYL PLAYER" : labelArtist).toUpperCase();
        String title = labelTitle.toUpperCase();
        arcText(c, artist, lr * 0.70f, false, lr * 0.092f, q);
        if (!title.isEmpty() && !title.equals(artist)) arcText(c, title, lr * 0.80f, true, lr * 0.092f, q);
        q.setTypeface(Typeface.DEFAULT);
        q.setColor(U.alpha(ink, 0.62f));
        q.setLetterSpacing(0.04f);
        q.setTextSize(lr * 0.062f);
        q.setTextAlign(Paint.Align.CENTER);
        c.drawText("SIDE A", -lr * 0.36f, lr * 0.022f, q);
        c.drawText("33 1/3 RPM", lr * 0.36f, lr * 0.022f, q);
        q.setLetterSpacing(0.15f);
        q.setTextAlign(Paint.Align.LEFT);
        arcText(c, "STEREO", lr * 0.47f + lr * 0.03f, false, lr * 0.05f, q);
        arcText(c, "HIGH FIDELITY", lr * 0.60f - lr * 0.02f, true, lr * 0.05f, q);
        q.setLetterSpacing(0f);

        // profundidad: sombra interior hacia el borde
        q.setShader(new RadialGradient(0, 0, lr, new int[]{0x00000000, 0x00000000, 0x61000000},
                new float[]{0f, 0.86f, 1f}, Shader.TileMode.CLAMP));
        c.drawCircle(0, 0, lr, q);
        q.setShader(null);
        c.restore();

        // canto de la etiqueta sobre el vinilo
        q.setStyle(Paint.Style.STROKE);
        q.setStrokeWidth(Math.max(1.4f, lr * 0.016f));
        q.setColor(Color.argb(190, 0, 0, 0));
        c.drawCircle(0, 0, lr + Math.max(0.8f, lr * 0.008f), q);
        q.setStrokeWidth(Math.max(0.7f, lr * 0.007f));
        q.setColor(Color.argb(34, 255, 255, 255));
        c.drawCircle(0, 0, lr - Math.max(0.6f, lr * 0.004f), q);

        // agujero central con bisel de papel y sombra interior
        float sh = Math.max(3f, lr * 0.082f);
        q.setStyle(Paint.Style.FILL);
        q.setShader(new RadialGradient(0, 0, sh * 2.1f, new int[]{0x8C000000, 0x8C000000, 0x00000000},
                new float[]{0f, sh / (sh * 2.1f), 1f}, Shader.TileMode.CLAMP));
        c.drawCircle(0, 0, sh * 2.1f, q);
        q.setShader(null);
        q.setStyle(Paint.Style.STROKE);
        q.setStrokeWidth(Math.max(1f, sh * 0.22f));
        q.setShader(new LinearGradient(-sh, -sh, sh, sh,
                new int[]{Color.argb(51, 255, 255, 255), Color.argb(5, 255, 255, 255), Color.argb(115, 0, 0, 0)},
                new float[]{0f, 0.5f, 1f}, Shader.TileMode.CLAMP));
        c.drawCircle(0, 0, sh * 1.18f, q);
        q.setShader(null);
        q.setStyle(Paint.Style.FILL);
        q.setColor(U.BG);
        c.drawCircle(0, 0, sh, q);
        q.setShader(new LinearGradient(-sh, -sh, sh, sh, 0xD9000000, 0x00000000, Shader.TileMode.CLAMP));
        c.drawCircle(0, 0, sh, q);
        q.setShader(null);
        q.setStyle(Paint.Style.STROKE);
        q.setStrokeWidth(Math.max(0.6f, sh * 0.08f));
        q.setColor(Color.argb(41, 255, 255, 255));
        c.drawCircle(0, 0, sh, q);
        return bm;
    }

    /** Texto siguiendo una circunferencia (arriba hacia fuera; abajo legible, con las letras hacia el centro). */
    private void arcText(Canvas c, String text, float r, boolean bottom, float size, Paint q) {
        q.setTextSize(size);
        float w = q.measureText(text);
        while (text.length() > 3 && Math.toDegrees(w / r) > 150) {
            text = text.substring(0, text.length() - 2).trim() + "…";
            w = q.measureText(text);
        }
        float span = (float) Math.toDegrees(w / r);
        Path pa = new Path();
        RectF oval = new RectF(-r, -r, r, r);
        if (!bottom) pa.addArc(oval, 270f - span / 2f, span);
        else pa.addArc(oval, 90f + span / 2f, -span);
        c.drawTextOnPath(text, pa, 0, 0, q);
    }

    // ---- brazo del tocadiscos ----
    private static class Geom {
        float[] P = new float[2], S = new float[2], H = new float[2], c1 = new float[2], c2 = new float[2],
                t = new float[2], n = new float[2], u = new float[2];
    }

    private Geom armGeom(float cx, float cy, float R, float a) {
        float rOut = R * 0.95f, rIn = R * 0.40f;
        float r = rOut - (rOut - rIn) * a;
        Geom g = new Geom();
        g.P[0] = cx + R * 1.12f;
        g.P[1] = cy - R * 0.95f;
        float L = R * 1.25f;
        float dx = g.P[0] - cx, dy = g.P[1] - cy;
        float D = (float) Math.hypot(dx, dy);
        float cc = (D * D + r * r - L * L) / (2f * D * r);
        double ang = Math.atan2(dy, dx) + Math.acos(Math.max(-1f, Math.min(1f, cc)));
        g.S[0] = cx + r * (float) Math.cos(ang);
        g.S[1] = cy + r * (float) Math.sin(ang);
        float hs = R * 0.15f;
        g.H[0] = g.S[0] - (g.S[0] - g.P[0]) / L * hs;
        g.H[1] = g.S[1] - (g.S[1] - g.P[1]) / L * hs;
        float ux = 0, uy = 0;
        for (int i = 0; i < 3; i++) {
            ux = g.H[0] - g.P[0];
            uy = g.H[1] - g.P[1];
            float ln = (float) Math.hypot(ux, uy);
            if (ln == 0) ln = 1f;
            ux /= ln;
            uy /= ln;
            float nx = -uy, ny = ux;
            g.c1[0] = g.P[0] + ux * ln * 0.42f;
            g.c1[1] = g.P[1] + uy * ln * 0.42f;
            g.c2[0] = g.H[0] - ux * ln * 0.30f + nx * R * 0.085f;
            g.c2[1] = g.H[1] - uy * ln * 0.30f + ny * R * 0.085f;
            float tx = g.H[0] - g.c2[0], ty = g.H[1] - g.c2[1];
            float tl = (float) Math.hypot(tx, ty);
            if (tl == 0) tl = 1f;
            g.t[0] = tx / tl;
            g.t[1] = ty / tl;
            g.H[0] = g.S[0] - g.t[0] * hs;
            g.H[1] = g.S[1] - g.t[1] * hs;
        }
        g.n[0] = -g.t[1];
        g.n[1] = g.t[0];
        g.u[0] = ux;
        g.u[1] = uy;
        return g;
    }

    private static final float[] CH_W = {1.0f, 0.80f, 0.55f, 0.28f, 0.10f};
    private static final int[] CHROME = {0xFF383B42, 0xFF808590, 0xFFC2C7D1, 0xFFEDF0F5, 0xFFFFFFFF};
    private static final int[] DARK = {0xFF08080A, 0xFF17171C, 0xFF2B2E36, 0xFF4D5059, 0xFF73767F};
    private static final int[] BRASS = {0xFF332B1F, 0xFF736142, 0xFFB29E75, 0xFFE0D1A8, 0xFFFFFAE6};

    private void strokeMetal(Canvas c, Paint q, Path pth, float width, int[] pal, Paint.Cap cap, float offX, float offY) {
        q.setStyle(Paint.Style.STROKE);
        q.setStrokeCap(cap);
        for (int i = 0; i < CH_W.length; i++) {
            float k = width * 0.13f * (1 - CH_W[i]);
            q.setStrokeWidth(width * CH_W[i]);
            q.setColor(pal[i]);
            c.save();
            c.translate(-k + offX, -k + offY);
            c.drawPath(pth, q);
            c.restore();
        }
    }

    private Path seg(float[] a, float[] b) {
        Path s = new Path();
        s.moveTo(a[0], a[1]);
        s.lineTo(b[0], b[1]);
        return s;
    }

    private void drawArm(Canvas c, float cx, float cy, float R) {
        Geom g = armGeom(cx, cy, R, armCur);
        Paint q = new Paint(Paint.ANTI_ALIAS_FLAG);
        float lift = Math.max(0f, Math.min(1f, (ARM_REST == armCur ? 1f : Math.max(0f, -armCur / 0.55f))));
        Path tube = new Path();
        tube.moveTo(g.P[0], g.P[1]);
        tube.cubicTo(g.c1[0], g.c1[1], g.c2[0], g.c2[1], g.H[0], g.H[1]);
        float wTube = R * 0.030f, wCw = R * 0.135f;
        float[] back = {g.P[0] - g.u[0] * R * 0.46f, g.P[1] - g.u[1] * R * 0.46f};
        float[] cwA = {g.P[0] - g.u[0] * R * 0.20f, g.P[1] - g.u[1] * R * 0.20f};
        float[] tipBack = {g.S[0] - g.t[0] * R * 0.075f, g.S[1] - g.t[1] * R * 0.075f};

        // sombras sobre el disco
        float ox = R * (0.02f + 0.05f * lift), oy = R * (0.03f + 0.07f * lift);
        q.setStyle(Paint.Style.STROKE);
        q.setStrokeCap(Paint.Cap.ROUND);
        float[][] sh = {{2.6f, 9}, {1.9f, 13}, {1.3f, 18}, {1.0f, 23}};
        for (float[] s : sh) {
            q.setStrokeWidth(wTube * s[0]);
            q.setColor(Color.argb((int) s[1], 0, 0, 0));
            c.save();
            c.translate(ox, oy);
            c.drawPath(tube, q);
            c.drawPath(seg(cwA, back), q);
            c.drawPath(seg(g.H, g.S), q);
            c.restore();
        }

        // base del pivote
        q.setStyle(Paint.Style.FILL);
        q.setShader(new RadialGradient(g.P[0] - R * 0.05f, g.P[1] - R * 0.05f, R * 0.17f,
                new int[]{0xFFF7F7FC, 0xFF8C8F9E, 0xFF292B33}, new float[]{0f, 0.55f, 1f}, Shader.TileMode.CLAMP));
        c.drawCircle(g.P[0], g.P[1], R * 0.15f, q);
        q.setShader(null);
        q.setColor(0xFF0A0A0D);
        c.drawCircle(g.P[0], g.P[1], R * 0.105f, q);
        q.setShader(new RadialGradient(g.P[0] - R * 0.03f, g.P[1] - R * 0.03f, R * 0.1f,
                new int[]{0xFF4D4F59, 0xFF0F0F14}, null, Shader.TileMode.CLAMP));
        c.drawCircle(g.P[0], g.P[1], R * 0.095f, q);
        q.setShader(null);
        q.setStyle(Paint.Style.STROKE);
        q.setStrokeWidth(R * 0.008f);
        q.setColor(U.alpha(Accent.a(accent), 0.95f));
        c.drawCircle(g.P[0], g.P[1], R * 0.082f, q);

        // contrapeso, varilla trasera, tubo
        strokeMetal(c, q, seg(g.P, cwA), R * 0.032f, CHROME, Paint.Cap.ROUND, 0, 0);
        strokeMetal(c, q, seg(cwA, back), wCw, CHROME, Paint.Cap.BUTT, 0, 0);
        q.setStyle(Paint.Style.STROKE);
        q.setStrokeCap(Paint.Cap.BUTT);
        q.setStrokeWidth(R * 0.006f);
        q.setColor(Color.argb(115, 0, 0, 0));
        float[] ks = {0.34f, 0.46f, 0.58f, 0.70f};
        for (float k : ks) {
            float mx = cwA[0] + (back[0] - cwA[0]) * k, my = cwA[1] + (back[1] - cwA[1]) * k;
            c.drawLine(mx - g.n[0] * wCw * 0.5f, my - g.n[1] * wCw * 0.5f,
                    mx + g.n[0] * wCw * 0.5f, my + g.n[1] * wCw * 0.5f, q);
        }
        strokeMetal(c, q, tube, wTube, CHROME, Paint.Cap.ROUND, 0, 0);
        float[] ra = {g.P[0] + g.u[0] * R * 0.17f, g.P[1] + g.u[1] * R * 0.17f};
        float[] rb = {g.P[0] + g.u[0] * R * 0.205f, g.P[1] + g.u[1] * R * 0.205f};
        strokeMetal(c, q, seg(ra, rb), R * 0.055f, DARK, Paint.Cap.BUTT, 0, 0);

        // cabezal, cápsula y púa
        strokeMetal(c, q, seg(g.H, g.S), R * 0.048f, DARK, Paint.Cap.ROUND, 0, 0);
        float[] fa = {g.H[0] + g.n[0] * R * 0.01f, g.H[1] + g.n[1] * R * 0.01f};
        float[] fb = {g.H[0] + g.n[0] * R * 0.095f + g.t[0] * R * 0.03f, g.H[1] + g.n[1] * R * 0.095f + g.t[1] * R * 0.03f};
        strokeMetal(c, q, seg(fa, fb), R * 0.014f, CHROME, Paint.Cap.ROUND, 0, 0);
        strokeMetal(c, q, seg(tipBack, g.S), R * 0.062f, BRASS, Paint.Cap.BUTT, 0, 0);
        q.setStyle(Paint.Style.FILL);
        q.setColor(0xE6FFFFFF);
        c.drawCircle(g.S[0], g.S[1], R * 0.009f, q);
    }
}

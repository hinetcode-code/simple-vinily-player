package es.simplevinyl.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.View;

/** Botón dibujado a mano: toda su área responde al toque. */
public class IconButton extends View {
    private String kind;
    private float level = 0.28f;          // opacidad base del icono
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();

    public IconButton(Context c, String kind, float level) {
        super(c);
        this.kind = kind;
        this.level = level;
        setClickable(true);
        setFocusable(false);
    }

    public void setKind(String k) {
        if (!k.equals(kind)) {
            kind = k;
            invalidate();
        }
    }

    @Override
    protected void drawableStateChanged() {
        super.drawableStateChanged();
        invalidate();
    }

    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight(), cx = w / 2f, cy = h / 2f, r = Math.min(w, h) / 2f;
        boolean on = isEnabled();
        if (on && isPressed()) {
            p.setStyle(Paint.Style.FILL);
            p.setColor(0x12FFFFFF);
            c.drawCircle(cx, cy, r, p);
        }
        float a = on ? (isPressed() ? 0.70f : level) : 0.10f;
        p.setColor(U.alpha(0xFFFFFFFF, a));
        p.setStyle(Paint.Style.FILL);
        float g = r * 0.46f;
        path.reset();
        switch (kind) {
            case "play":
                path.moveTo(cx - g * 0.62f, cy - g);
                path.lineTo(cx + g * 0.95f, cy);
                path.lineTo(cx - g * 0.62f, cy + g);
                path.close();
                c.drawPath(path, p);
                break;
            case "pause": {
                float bw = g * 0.55f;
                c.drawRect(cx - g * 0.8f, cy - g * 0.95f, cx - g * 0.8f + bw, cy + g * 0.95f, p);
                c.drawRect(cx + g * 0.8f - bw, cy - g * 0.95f, cx + g * 0.8f, cy + g * 0.95f, p);
                break;
            }
            case "prev":
            case "next": {
                float d = kind.equals("prev") ? -1f : 1f;
                path.moveTo(cx - d * g * 0.55f, cy - g * 0.8f);
                path.lineTo(cx + d * g * 0.55f, cy);
                path.lineTo(cx - d * g * 0.55f, cy + g * 0.8f);
                path.close();
                c.drawPath(path, p);
                float bx = cx + d * g * 0.55f;
                c.drawRect(d > 0 ? bx : bx - g * 0.2f, cy - g * 0.8f, d > 0 ? bx + g * 0.2f : bx, cy + g * 0.8f, p);
                break;
            }
            case "refresh": {
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(r * 0.13f);
                p.setStrokeCap(Paint.Cap.ROUND);
                RectF oval = new RectF(cx - g, cy - g, cx + g, cy + g);
                c.drawArc(oval, -60, 280, false, p);
                p.setStyle(Paint.Style.FILL);
                float ax = cx + g * 0.5f, ay = cy - g * 0.87f;
                path.moveTo(ax + g * 0.55f, ay - g * 0.05f);
                path.lineTo(ax - g * 0.15f, ay - g * 0.55f);
                path.lineTo(ax - g * 0.35f, ay + g * 0.35f);
                path.close();
                c.drawPath(path, p);
                break;
            }
            case "gear": {
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(r * 0.13f);
                p.setStrokeCap(Paint.Cap.ROUND);
                c.drawCircle(cx, cy, g * 0.55f, p);
                for (int i = 0; i < 8; i++) {
                    double an = Math.PI * 2 * i / 8;
                    float x1 = cx + (float) Math.cos(an) * g * 0.80f, y1 = cy + (float) Math.sin(an) * g * 0.80f;
                    float x2 = cx + (float) Math.cos(an) * g * 1.12f, y2 = cy + (float) Math.sin(an) * g * 1.12f;
                    c.drawLine(x1, y1, x2, y2, p);
                }
                break;
            }
            default:
                break;
        }
    }
}

package es.simplevinyl.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.MotionEvent;
import android.view.View;

/** Línea de progreso muy fina; al tocarla o arrastrar salta a ese punto de la pista. */
public class SeekLine extends View {
    public interface OnSeek {
        void seek(float frac, boolean fin);
    }

    private float frac;
    private boolean drag;
    private int accent = 0xFFFF3D5A;
    private OnSeek cb;
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

    public SeekLine(Context c) {
        super(c);
    }

    public void setOnSeek(OnSeek c) { cb = c; }

    public void setAccent(int a) { accent = a; invalidate(); }

    public void setFrac(float f) {
        if (drag) return;
        f = Math.max(0f, Math.min(1f, f));
        if (Math.abs(f - frac) > 0.001f) {
            frac = f;
            invalidate();
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        float w = Math.max(1, getWidth());
        float f = Math.max(0f, Math.min(1f, e.getX() / w));
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                getParent().requestDisallowInterceptTouchEvent(true);
                drag = true;
                frac = f;
                invalidate();
                return true;
            case MotionEvent.ACTION_MOVE:
                frac = f;
                invalidate();
                return true;
            case MotionEvent.ACTION_UP:
                drag = false;
                frac = f;
                invalidate();
                if (cb != null) cb.seek(f, true);
                return true;
            case MotionEvent.ACTION_CANCEL:
                drag = false;
                return true;
            default:
                return true;
        }
    }

    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth(), y = getHeight() / 2f, t = U.dp(getContext(), 2f);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeWidth(t);
        p.setColor(0x12FFFFFF);
        c.drawLine(t, y, w - t, y, p);
        if (frac > 0.001f) {
            float x = t + (w - 2 * t) * frac;
            p.setColor(U.alpha(accent, 0.55f));
            c.drawLine(t, y, x, y, p);
            if (drag) {
                p.setColor(0x8CFFFFFF);
                c.drawCircle(x, y, t * 1.7f, p);
            }
        }
    }
}

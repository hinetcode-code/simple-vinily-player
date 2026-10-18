package es.simplevinyl.app;

import android.graphics.Color;

/** Colores de resalte (los mismos que la versión de escritorio). */
public final class Accent {
    public static final String[] NAMES = {"Coral", "Naranja", "Oro", "Lima", "Turquesa", "Azul", "Violeta", "Rosa"};
    public static final int[] A = {
            Color.parseColor("#ff3d5a"), Color.parseColor("#ff7a1a"), Color.parseColor("#f5a300"),
            Color.parseColor("#1fbf6a"), Color.parseColor("#0aa5c4"), Color.parseColor("#3d6bff"),
            Color.parseColor("#8a4dff"), Color.parseColor("#f0388f")};
    public static final int[] B = {
            Color.parseColor("#ff7a45"), Color.parseColor("#ffb02e"), Color.parseColor("#ffd23f"),
            Color.parseColor("#8ee05a"), Color.parseColor("#1fe0b0"), Color.parseColor("#2fc0ff"),
            Color.parseColor("#d05cff"), Color.parseColor("#ff7a8a")};

    private Accent() {}

    public static int a(int i) { return A[Math.max(0, Math.min(A.length - 1, i))]; }

    public static int b(int i) { return B[Math.max(0, Math.min(B.length - 1, i))]; }

    /** Versión más clara del color (para textos resaltados). */
    public static int light(int i) {
        int c = a(i);
        return Color.rgb(Math.min(255, Color.red(c) + 70), Math.min(255, Color.green(c) + 70),
                Math.min(255, Color.blue(c) + 70));
    }
}

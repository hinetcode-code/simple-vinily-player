package es.simplevinyl.app;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private static final int REQ_PERM = 1, REQ_COVER = 2;
    private static final int BAR_H_DP = 120;

    private Prefs prefs;
    private PlayerService svc;
    private final Handler h = new Handler();
    private final ExecutorService scanEx = Executors.newSingleThreadExecutor();
    private final ExecutorService metaEx = Executors.newSingleThreadExecutor();
    private boolean metaBusy;

    private List<Album> albums = new ArrayList<>();
    private String artistSel;
    private final List<Row> rows = new ArrayList<>();
    private Album pendingCoverAlbum;
    private String shownCover = "\u0000";
    private int lastIndexSaved = -1;

    // vistas
    private FrameLayout root;
    private DiscView disc;
    private LinearLayout bar;
    private TextView tTitle, tArtist, tPos, tDur;
    private IconButton bPrev, bPlay, bNext;
    private SeekLine seek;
    private FrameLayout handleZone;
    private LinearLayout panel;
    private boolean panelOpen, barWanted = true;
    private int panelW;
    private EditText search;
    private TextView count, backLbl;
    private LinearLayout backRow, emptyBox;
    private TextView emptyMsg;
    private final TextView[] sortBtns = new TextView[3];
    private static final String[] SORT_IDS = {"artist", "name", "date"};
    private ListView list;
    private RowAdapter adapter;

    private float downX, downY;
    private boolean edgeSwipe, panelSwipe;

    static class Row {
        Album album;               // fila de álbum
        String artist;             // fila de artista
        List<Album> albums;
    }

    private final ServiceConnection conn = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            svc = ((PlayerService.LocalBinder) binder).get();
            svc.onChange = new Runnable() {
                @Override
                public void run() {
                    refreshUi();
                }
            };
            tryRestore();
            refreshUi();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            svc = null;
        }
    };

    // ------------------------------------------------------------------ ciclo de vida
    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = new Prefs(this);
        buildUi();
        setContentView(root);
        applySettings();
        bindService(new Intent(this, PlayerService.class), conn, Context.BIND_AUTO_CREATE);
        if (hasPerm()) {
            reload(false);
        } else {
            requestPerms();
        }
        h.postDelayed(tick, 250);
        showBar();
    }

    @Override
    protected void onDestroy() {
        h.removeCallbacksAndMessages(null);
        if (svc != null) svc.onChange = null;
        try { unbindService(conn); } catch (Exception ignored) { }
        super.onDestroy();
    }

    @Override
    public void onConfigurationChanged(Configuration c) {
        super.onConfigurationChanged(c);
        applyLayout();
    }

    @Override
    public void onBackPressed() {
        if (panelOpen) {
            if (artistSel != null) {
                artistSel = null;
                rebuildRows();
            } else {
                closePanel();
            }
        } else {
            moveTaskToBack(true);   // la música sigue sonando
        }
    }

    // ------------------------------------------------------------------ permisos
    private String perm() {
        return Build.VERSION.SDK_INT >= 33 ? "android.permission.READ_MEDIA_AUDIO"
                : Manifest.permission.READ_EXTERNAL_STORAGE;
    }

    private boolean hasPerm() {
        return Build.VERSION.SDK_INT < 23 || checkSelfPermission(perm()) == PackageManager.PERMISSION_GRANTED;
    }

    private void requestPerms() {
        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(new String[]{perm(), "android.permission.POST_NOTIFICATIONS"}, REQ_PERM);
        } else if (Build.VERSION.SDK_INT >= 23) {
            requestPermissions(new String[]{perm()}, REQ_PERM);
        }
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
        super.onRequestPermissionsResult(code, perms, res);
        if (hasPerm()) {
            reload(false);
        } else {
            rebuildRows();
        }
    }

    // ------------------------------------------------------------------ interfaz
    private void buildUi() {
        getWindow().setStatusBarColor(U.BG);
        getWindow().setNavigationBarColor(U.BG);
        root = new FrameLayout(this);
        root.setBackgroundColor(U.BG);

        disc = new DiscView(this);
        FrameLayout.LayoutParams dlp = new FrameLayout.LayoutParams(-1, -1);
        dlp.bottomMargin = U.dp(this, BAR_H_DP);
        root.addView(disc, dlp);
        disc.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (panelOpen) closePanel(); else showBar();
            }
        });

        buildBar();
        buildHandle();
        buildPanel();
        applyLayout();
    }

    private void buildBar() {
        bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.VERTICAL);
        bar.setGravity(Gravity.CENTER_HORIZONTAL);
        tTitle = U.text(this, "Simple Vinyl Player", 13, U.alpha(Color.WHITE, 0.42f), true);
        tArtist = U.text(this, "", 11, U.alpha(Color.WHITE, 0.22f), false);
        tTitle.setGravity(Gravity.CENTER);
        tArtist.setGravity(Gravity.CENTER);
        bar.addView(tTitle, new LinearLayout.LayoutParams(-1, -2));
        bar.addView(tArtist, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER);
        bPrev = new IconButton(this, "prev", 0.28f);
        bPlay = new IconButton(this, "play", 0.42f);
        bNext = new IconButton(this, "next", 0.28f);
        row.addView(bPrev, new LinearLayout.LayoutParams(U.dp(this, 46), U.dp(this, 46)));
        row.addView(bPlay, new LinearLayout.LayoutParams(U.dp(this, 54), U.dp(this, 54)));
        row.addView(bNext, new LinearLayout.LayoutParams(U.dp(this, 46), U.dp(this, 46)));
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(-2, -2);
        rlp.topMargin = U.dp(this, 2);
        bar.addView(row, rlp);

        seek = new SeekLine(this);
        seek.setOnSeek(new SeekLine.OnSeek() {
            @Override
            public void seek(float frac, boolean fin) {
                if (svc != null && (svc.state.equals("playing") || svc.state.equals("paused"))) svc.seekFrac(frac);
            }
        });
        bar.addView(seek, new LinearLayout.LayoutParams(-1, U.dp(this, 22)));

        LinearLayout times = new LinearLayout(this);
        tPos = U.text(this, "", 10, U.alpha(Color.WHITE, 0.20f), false);
        tDur = U.text(this, "", 10, U.alpha(Color.WHITE, 0.20f), false);
        tPos.setTypeface(Typeface.MONOSPACE);
        tDur.setTypeface(Typeface.MONOSPACE);
        tPos.setGravity(Gravity.START);
        tDur.setGravity(Gravity.END);
        times.addView(tPos, new LinearLayout.LayoutParams(0, -2, 1f));
        times.addView(tDur, new LinearLayout.LayoutParams(0, -2, 1f));
        bar.addView(times, new LinearLayout.LayoutParams(-1, -2));

        bPrev.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { if (svc != null) svc.prev(); }
        });
        bPlay.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { if (svc != null) svc.toggle(); }
        });
        bNext.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { if (svc != null) svc.next(); }
        });
        FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(U.dp(this, 340), -2,
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        blp.bottomMargin = U.dp(this, 10);
        root.addView(bar, blp);
    }

    private void buildHandle() {
        handleZone = new FrameLayout(this);
        View pill = new View(this);
        pill.setBackground(U.round(U.alpha(Color.WHITE, 0.10f), U.dp(this, 3)));
        FrameLayout.LayoutParams plp = new FrameLayout.LayoutParams(U.dp(this, 4), U.dp(this, 64), Gravity.END | Gravity.CENTER_VERTICAL);
        plp.rightMargin = U.dp(this, 4);
        handleZone.addView(pill, plp);
        handleZone.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { openPanel(); }
        });
        root.addView(handleZone, new FrameLayout.LayoutParams(U.dp(this, 28), U.dp(this, 140),
                Gravity.END | Gravity.CENTER_VERTICAL));
    }

    private void buildPanel() {
        panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackgroundColor(0xF50D0D12);
        panel.setPadding(U.dp(this, 18), U.dp(this, 22), U.dp(this, 18), U.dp(this, 12));
        panel.setClickable(true);

        TextView head = U.text(this, "Mi colección", 22, Color.WHITE, true);
        panel.addView(head, new LinearLayout.LayoutParams(-1, -2));
        count = U.text(this, "", 12, 0xFF9B9BAB, false);
        panel.addView(count, new LinearLayout.LayoutParams(-1, -2));

        search = new EditText(this);
        search.setHint("Buscar álbum o artista…");
        search.setHintTextColor(0xFF777789);
        search.setTextColor(Color.WHITE);
        search.setTextSize(14);
        search.setSingleLine(true);
        search.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        search.setBackground(U.roundStroke(0xFF1D1D26, 0xFF343442, U.dp(this, 14), 1));
        search.setPadding(U.dp(this, 14), U.dp(this, 9), U.dp(this, 14), U.dp(this, 9));
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(Editable e) { rebuildRows(); }
        });
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-1, -2);
        slp.topMargin = U.dp(this, 12);
        panel.addView(search, slp);

        LinearLayout seg = new LinearLayout(this);
        seg.setBackground(U.roundStroke(0xFF1A1A22, 0xFF272732, U.dp(this, 20), 1));
        seg.setPadding(U.dp(this, 2), U.dp(this, 2), U.dp(this, 2), U.dp(this, 2));
        String[] names = {"Artista", "Álbum", "Recientes"};
        for (int i = 0; i < 3; i++) {
            final String id = SORT_IDS[i];
            TextView t = U.text(this, names[i], 13, 0xFFA4A4B6, true);
            t.setGravity(Gravity.CENTER);
            t.setPadding(0, U.dp(this, 8), 0, U.dp(this, 8));
            t.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    prefs.setSort(id);
                    artistSel = null;
                    rebuildRows();
                }
            });
            sortBtns[i] = t;
            seg.addView(t, new LinearLayout.LayoutParams(0, -2, 1f));
        }
        LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(-1, -2);
        glp.topMargin = U.dp(this, 10);
        panel.addView(seg, glp);

        backRow = new LinearLayout(this);
        backRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView back = U.text(this, "‹ Artistas", 13, Color.WHITE, true);
        back.setBackground(U.roundStroke(0x08FFFFFF, 0xFF3A3A48, U.dp(this, 20), 1));
        back.setPadding(U.dp(this, 14), U.dp(this, 7), U.dp(this, 14), U.dp(this, 7));
        back.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                artistSel = null;
                rebuildRows();
            }
        });
        backLbl = U.text(this, "", 14, Color.WHITE, true);
        backRow.addView(back, new LinearLayout.LayoutParams(-2, -2));
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(0, -2, 1f);
        bl.leftMargin = U.dp(this, 10);
        backRow.addView(backLbl, bl);
        backRow.setVisibility(View.GONE);
        LinearLayout.LayoutParams brl = new LinearLayout.LayoutParams(-1, -2);
        brl.topMargin = U.dp(this, 10);
        panel.addView(backRow, brl);

        FrameLayout listBox = new FrameLayout(this);
        list = new ListView(this);
        list.setDivider(null);
        list.setSelector(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
        list.setVerticalScrollBarEnabled(false);
        adapter = new RowAdapter();
        list.setAdapter(adapter);
        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> p, View v, int pos, long id) { openRow(rows.get(pos)); }
        });
        list.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            @Override
            public boolean onItemLongClick(AdapterView<?> p, View v, int pos, long id) {
                rowMenu(rows.get(pos));
                return true;
            }
        });
        listBox.addView(list, new FrameLayout.LayoutParams(-1, -1));

        emptyBox = new LinearLayout(this);
        emptyBox.setOrientation(LinearLayout.VERTICAL);
        emptyBox.setGravity(Gravity.CENTER);
        TextView et = U.text(this, "No hay álbumes", 15, 0xFFD6D6E2, true);
        et.setGravity(Gravity.CENTER);
        emptyBox.addView(et, new LinearLayout.LayoutParams(-1, -2));
        emptyMsg = new TextView(this);
        emptyMsg.setTextColor(0xFF9B9BAB);
        emptyMsg.setTextSize(12);
        emptyMsg.setGravity(Gravity.CENTER);
        emptyBox.addView(emptyMsg, new LinearLayout.LayoutParams(-1, -2));
        Button pick = new Button(this);
        pick.setText("Dar permiso / releer");
        pick.setTextColor(Color.WHITE);
        pick.setBackground(U.round(Accent.a(prefs.accent()), U.dp(this, 24)));
        pick.setAllCaps(false);
        pick.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (hasPerm()) reload(true); else requestPerms();
            }
        });
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(-2, U.dp(this, 44));
        plp.topMargin = U.dp(this, 10);
        emptyBox.addView(pick, plp);
        emptyBox.setVisibility(View.GONE);
        listBox.addView(emptyBox, new FrameLayout.LayoutParams(-1, -1));
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(-1, 0, 1f);
        llp.topMargin = U.dp(this, 6);
        panel.addView(listBox, llp);

        LinearLayout foot = new LinearLayout(this);
        foot.setGravity(Gravity.CENTER_VERTICAL);
        IconButton refresh = new IconButton(this, "refresh", 0.55f);
        refresh.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { reload(true); }
        });
        TextView folder = U.text(this, "Música del dispositivo", 11, 0xFF8F8FA2, false);
        folder.setGravity(Gravity.CENTER);
        IconButton gear = new IconButton(this, "gear", 0.55f);
        gear.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { openSettings(); }
        });
        foot.addView(refresh, new LinearLayout.LayoutParams(U.dp(this, 44), U.dp(this, 44)));
        foot.addView(folder, new LinearLayout.LayoutParams(0, -2, 1f));
        foot.addView(gear, new LinearLayout.LayoutParams(U.dp(this, 44), U.dp(this, 44)));
        panel.addView(foot, new LinearLayout.LayoutParams(-1, -2));

        root.addView(panel, new FrameLayout.LayoutParams(U.dp(this, 360), -1, Gravity.END));
    }

    private void applyLayout() {
        int sw = getResources().getDisplayMetrics().widthPixels;
        panelW = Math.min(U.dp(this, 360), Math.round(sw * 0.88f));
        FrameLayout.LayoutParams plp = (FrameLayout.LayoutParams) panel.getLayoutParams();
        plp.width = panelW;
        panel.setLayoutParams(plp);
        panel.setTranslationX(panelOpen ? 0 : panelW + U.dp(this, 4));
        FrameLayout.LayoutParams blp = (FrameLayout.LayoutParams) bar.getLayoutParams();
        blp.width = Math.min(U.dp(this, 340), sw - U.dp(this, 32));
        bar.setLayoutParams(blp);
        handleZone.setVisibility(panelOpen ? View.GONE : View.VISIBLE);
    }

    private void openPanel() {
        panelOpen = true;
        handleZone.setVisibility(View.GONE);
        panel.animate().translationX(0).setDuration(220).start();
        h.removeCallbacks(hideBar);
    }

    private void closePanel() {
        panelOpen = false;
        handleZone.setVisibility(View.VISIBLE);
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(search.getWindowToken(), 0);
        search.clearFocus();
        panel.animate().translationX(panelW + U.dp(this, 4)).setDuration(200).start();
        showBar();
    }

    // ------------------------------------------------------------------ reproductor que se oculta
    private final Runnable hideBar = new Runnable() {
        @Override
        public void run() {
            if (prefs.pinPlayer() || panelOpen) return;
            barWanted = false;
            bar.animate().alpha(0f).setDuration(250).withEndAction(new Runnable() {
                @Override
                public void run() {
                    if (!barWanted) bar.setVisibility(View.GONE);
                }
            }).start();
        }
    };

    private void showBar() {
        barWanted = true;
        h.removeCallbacks(hideBar);
        bar.setVisibility(View.VISIBLE);
        bar.animate().cancel();
        bar.animate().alpha(1f).setDuration(180).start();
        if (!prefs.pinPlayer() && !panelOpen) h.postDelayed(hideBar, 3500);
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = ev.getRawX();
                downY = ev.getRawY();
                int sw = getResources().getDisplayMetrics().widthPixels;
                edgeSwipe = !panelOpen && downX > sw - U.dp(this, 28);
                panelSwipe = panelOpen && downX > sw - panelW;
                if (!panelOpen) showBar();
                break;
            case MotionEvent.ACTION_MOVE:
                if (edgeSwipe && downX - ev.getRawX() > U.dp(this, 48)) {
                    edgeSwipe = false;
                    openPanel();
                } else if (panelSwipe && ev.getRawX() - downX > U.dp(this, 96)
                        && Math.abs(ev.getRawY() - downY) < U.dp(this, 60)) {
                    panelSwipe = false;
                    closePanel();
                }
                break;
            default:
                break;
        }
        return super.dispatchTouchEvent(ev);
    }

    // ------------------------------------------------------------------ biblioteca
    private void reload(final boolean announce) {
        if (!hasPerm()) return;
        scanEx.execute(new Runnable() {
            @Override
            public void run() {
                final List<Album> res = Library.scan(MainActivity.this, Meta.load(MainActivity.this));
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        albums = res;
                        rebuildRows();
                        tryRestore();
                        refreshUi();
                        if (announce) toast("Biblioteca actualizada · " + res.size() + " álbum"
                                + (res.size() == 1 ? "" : "es"));
                        if (res.isEmpty()) openPanel();
                        if (prefs.autoMeta()) autoMeta();
                    }
                });
            }
        });
    }

    private Album albumByKey(String key) {
        for (Album a : albums) if (a.key.equals(key)) return a;
        return null;
    }

    private void tryRestore() {
        if (svc == null || albums.isEmpty() || !svc.queue.isEmpty()) return;
        Album a = albumByKey(prefs.lastAlbum());
        if (a != null && !a.tracks.isEmpty()) svc.prepareQueue(a.tracks, prefs.lastTrack());
    }

    private void rebuildRows() {
        String sort = prefs.sort();
        if (!sort.equals("artist")) artistSel = null;
        String q = search.getText().toString().trim().toLowerCase(Locale.ROOT);
        rows.clear();
        int nAlbums = 0;
        if (sort.equals("artist") && artistSel == null) {
            Map<String, List<Album>> groups = new LinkedHashMap<>();
            for (Album a : albums) {
                List<Album> l = groups.get(a.artist);
                if (l == null) {
                    l = new ArrayList<>();
                    groups.put(a.artist, l);
                }
                l.add(a);
            }
            List<String> names = new ArrayList<>(groups.keySet());
            Collections.sort(names, new Comparator<String>() {
                @Override public int compare(String x, String y) { return x.compareToIgnoreCase(y); }
            });
            for (String n : names) {
                List<Album> l = groups.get(n);
                boolean match = q.isEmpty() || n.toLowerCase(Locale.ROOT).contains(q);
                for (Album a : l) if (a.title.toLowerCase(Locale.ROOT).contains(q)) match = true;
                if (!match) continue;
                Row r = new Row();
                r.artist = n;
                r.albums = l;
                rows.add(r);
            }
            nAlbums = rows.size();
            count.setText(albums.isEmpty() ? "Vacía" : groups.size() + " artista" + (groups.size() == 1 ? "" : "s")
                    + " · " + albums.size() + " álbum" + (albums.size() == 1 ? "" : "es"));
        } else {
            List<Album> shown = new ArrayList<>();
            for (Album a : albums) {
                if (artistSel != null && !a.artist.equals(artistSel)) continue;
                if (!q.isEmpty() && !a.title.toLowerCase(Locale.ROOT).contains(q)
                        && !a.artist.toLowerCase(Locale.ROOT).contains(q)) continue;
                shown.add(a);
            }
            final String mode = sort;
            Collections.sort(shown, new Comparator<Album>() {
                @Override
                public int compare(Album x, Album y) {
                    if (mode.equals("date")) {
                        int c = Long.compare(y.dateAdded, x.dateAdded);
                        if (c != 0) return c;
                    } else if (mode.equals("artist")) {
                        int c = x.year.compareTo(y.year);
                        if (c != 0) return c;
                    }
                    int c = x.title.compareToIgnoreCase(y.title);
                    return c != 0 ? c : x.artist.compareToIgnoreCase(y.artist);
                }
            });
            for (Album a : shown) {
                Row r = new Row();
                r.album = a;
                rows.add(r);
            }
            nAlbums = shown.size();
            count.setText(shown.isEmpty() ? "Vacía" : shown.size() + " álbum" + (shown.size() == 1 ? "" : "es"));
        }
        for (int i = 0; i < 3; i++) {
            boolean sel = SORT_IDS[i].equals(sort);
            sortBtns[i].setTextColor(sel ? Color.WHITE : 0xFFA4A4B6);
            sortBtns[i].setBackground(sel ? U.round(0xFF3A3A4A, U.dp(this, 18)) : null);
        }
        backRow.setVisibility(artistSel != null ? View.VISIBLE : View.GONE);
        backLbl.setText(artistSel == null ? "" : artistSel);
        emptyBox.setVisibility(albums.isEmpty() ? View.VISIBLE : View.GONE);
        emptyMsg.setText(hasPerm() ? "No se encontró música en el dispositivo"
                : "Hace falta permiso para leer tu música");
        adapter.notifyDataSetChanged();
    }

    private void openRow(Row r) {
        if (r.album == null) {
            artistSel = r.artist;
            rebuildRows();
            list.setSelection(0);
        } else {
            playAlbum(r.album);
        }
    }

    private void playAlbum(Album a) {
        if (svc == null || a.tracks.isEmpty()) return;
        prefs.setLast(a.key, 0);
        lastIndexSaved = 0;
        svc.playQueue(a.tracks, 0);
        closePanel();
    }

    private void rowMenu(final Row r) {
        final List<Album> target = r.album != null ? Collections.singletonList(r.album) : r.albums;
        String[] items = r.album != null
                ? new String[]{"Reproducir", "Buscar carátula y datos automáticamente", "Elegir carátula desde un archivo…"}
                : new String[]{"Buscar carátulas y datos de sus álbumes"};
        new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle(r.album != null ? r.album.title : r.artist)
                .setItems(items, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        if (r.album != null && which == 0) playAlbum(r.album);
                        else if (r.album != null && which == 2) pickCover(r.album);
                        else fetchMeta(new ArrayList<>(target), true, false);
                    }
                }).show();
    }

    // ------------------------------------------------------------------ carátula manual
    private void pickCover(Album a) {
        pendingCoverAlbum = a;
        Intent i = new Intent(Intent.ACTION_GET_CONTENT).setType("image/*");
        try {
            startActivityForResult(Intent.createChooser(i, "Carátula del álbum"), REQ_COVER);
        } catch (Exception e) {
            toast("No hay ninguna app para elegir imágenes");
        }
    }

    @Override
    protected void onActivityResult(int req, int res, final Intent data) {
        super.onActivityResult(req, res, data);
        if (req != REQ_COVER || res != RESULT_OK || data == null || data.getData() == null || pendingCoverAlbum == null) {
            return;
        }
        final Album a = pendingCoverAlbum;
        final android.net.Uri uri = data.getData();
        scanEx.execute(new Runnable() {
            @Override
            public void run() {
                boolean ok = false;
                try {
                    InputStream in = getContentResolver().openInputStream(uri);
                    ByteArrayOutputStream out = new ByteArrayOutputStream();
                    byte[] buf = new byte[16384];
                    int n;
                    while (in != null && (n = in.read(buf)) > 0) out.write(buf, 0, n);
                    if (in != null) in.close();
                    byte[] raw = out.toByteArray();
                    BitmapFactory.Options o = new BitmapFactory.Options();
                    o.inJustDecodeBounds = true;
                    BitmapFactory.decodeByteArray(raw, 0, raw.length, o);
                    if (o.outWidth > 0) {
                        java.io.File f = Meta.coverFile(MainActivity.this, a);
                        FileOutputStream fo = new FileOutputStream(f);
                        fo.write(raw);
                        fo.close();
                        JSONObject patch = new JSONObject();
                        patch.put("cover", f.getAbsolutePath());
                        Meta.update(MainActivity.this, a.metaKey(), patch);
                        ok = true;
                    }
                } catch (Exception ignored) {
                }
                final boolean done = ok;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (done) {
                            reapplyMeta();
                            toast("Carátula actualizada");
                        } else {
                            toast("No se pudo usar esa imagen");
                        }
                    }
                });
            }
        });
    }

    // ------------------------------------------------------------------ carátulas y datos automáticos
    private void autoMeta() {
        JSONObject meta = Meta.load(this);
        List<Album> todo = new ArrayList<>();
        for (Album a : albums) if (Meta.needs(a, meta)) todo.add(a);
        fetchMeta(todo, false, true);
    }

    private void fetchMeta(final List<Album> entries, final boolean force, final boolean quiet) {
        if (entries.isEmpty()) {
            if (!quiet) toast("Todos los álbumes ya tienen carátula y datos");
            return;
        }
        if (metaBusy) {
            if (!quiet) toast("Ya se están buscando carátulas…");
            return;
        }
        metaBusy = true;
        if (!quiet) toast("Buscando carátulas y datos de " + entries.size() + " álbum"
                + (entries.size() == 1 ? "" : "es") + "…");
        metaEx.execute(new Runnable() {
            @Override
            public void run() {
                int found = 0;
                String err = null;
                for (int i = 0; i < entries.size(); i++) {
                    Album a = entries.get(i);
                    try {
                        Meta.Result r = Meta.lookup(a.title, a.artist);
                        JSONObject data = r == null ? new JSONObject() : Meta.fromResult(MainActivity.this, r, a, force);
                        data.put("tried", System.currentTimeMillis());
                        Meta.update(MainActivity.this, a.metaKey(), data);
                        if (data.length() > 1) found++;
                    } catch (Meta.NetException e) {
                        err = e.getMessage();
                        break;
                    } catch (Exception ignored) {
                    }
                    if (i + 1 < entries.size()) {
                        try { Thread.sleep(2500); } catch (InterruptedException ignored) { }
                    }
                }
                final int f = found;
                final String e = err;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        metaBusy = false;
                        if (f > 0) {
                            reapplyMeta();
                            toast("Carátulas y datos añadidos a " + f + " álbum" + (f == 1 ? "" : "es"));
                        } else if (!quiet) {
                            toast(e != null ? "Sin conexión o servicio ocupado; inténtalo más tarde"
                                    : "No se encontraron datos nuevos");
                        }
                    }
                });
            }
        });
    }

    private void reapplyMeta() {
        JSONObject meta = Meta.load(this);
        for (Album a : albums) Meta.apply(a, meta);
        Covers.forget(null);
        shownCover = "\u0000";
        rebuildRows();
        refreshUi();
    }

    // ------------------------------------------------------------------ estado del reproductor → interfaz
    private void refreshUi() {
        if (svc == null) return;
        Track t = svc.current();
        boolean has = t != null;
        boolean playing = svc.state.equals("playing");
        bPlay.setKind(playing ? "pause" : "play");
        bPlay.setEnabled(has);
        bPrev.setEnabled(has);
        bNext.setEnabled(has && svc.index + 1 < svc.queue.size());
        if (has) {
            tTitle.setText(t.title);
            tArtist.setText(t.artist);
            disc.setLabelText(t.artist, t.title);
            if (lastIndexSaved != svc.index) {
                lastIndexSaved = svc.index;
                if (prefs.lastAlbum().equals(t.albumKey)) prefs.setLast(t.albumKey, svc.index);
            }
        } else {
            tTitle.setText("Simple Vinyl Player");
            tArtist.setText("Menú: borde derecho");
            tPos.setText("");
            tDur.setText("");
            disc.setLabelText("", "Simple Vinyl Player");
        }
        disc.setSpinning(prefs.spin() && playing);
        Album a = has ? albumByKey(t.albumKey) : null;
        final String src = a == null ? null : a.cover;
        if (!Objects.equals(src, shownCover)) {
            shownCover = src;
            Covers.load(this, src, 700, new Covers.Callback() {
                @Override
                public void done(Bitmap bmp) {
                    if (Objects.equals(src, shownCover)) disc.setCover(bmp);
                }
            });
        }
        if (!has || !(playing || svc.state.equals("paused") || svc.state.equals("loading"))) {
            disc.setArmTarget(null);
        }
        if (svc.state.equals("error") && !svc.errorMsg.isEmpty()) toast(svc.errorMsg);
        adapter.notifyDataSetChanged();
    }

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (svc != null && svc.current() != null
                    && (svc.state.equals("playing") || svc.state.equals("paused"))) {
                int pos = svc.position(), dur = svc.duration();
                float frac = dur > 0 ? pos / (float) dur : 0f;
                seek.setFrac(frac);
                tPos.setText(U.time(pos));
                tDur.setText(U.time(dur));
                int n = Math.max(1, svc.queue.size());
                disc.setArmTarget((Math.max(0, svc.index) + Math.min(1f, frac)) / n);
            } else if (svc != null && svc.state.equals("loading")) {
                disc.setArmTarget(0f);
            } else {
                seek.setFrac(0f);
            }
            h.postDelayed(this, 250);
        }
    };

    // ------------------------------------------------------------------ ajustes
    private void applySettings() {
        int ac = prefs.accent();
        disc.setAccent(ac);
        disc.setArmEnabled(prefs.arm());
        seek.setAccent(Accent.a(ac));
        if (prefs.pinPlayer()) showBar();
        if (adapter != null) adapter.notifyDataSetChanged();
        if (svc != null) refreshUi();
    }

    private void openSettings() {
        ScrollView sv = new ScrollView(this);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(U.dp(this, 22), U.dp(this, 8), U.dp(this, 22), U.dp(this, 8));
        sv.addView(box);

        box.addView(U.text(this, "Color resaltado", 14, Color.WHITE, true));
        final int[] chosen = {prefs.accent()};
        final View[] sw = new View[Accent.A.length];
        LinearLayout sr = new LinearLayout(this);
        for (int i = 0; i < Accent.A.length; i++) {
            final int idx = i;
            View v = new View(this);
            sw[i] = v;
            v.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View x) {
                    chosen[0] = idx;
                    for (int k = 0; k < sw.length; k++) paintSwatch(sw[k], k, chosen[0] == k);
                }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(U.dp(this, 30), U.dp(this, 30));
            lp.rightMargin = U.dp(this, 8);
            sr.addView(v, lp);
        }
        for (int k = 0; k < sw.length; k++) paintSwatch(sw[k], k, chosen[0] == k);
        LinearLayout.LayoutParams srl = new LinearLayout.LayoutParams(-2, -2);
        srl.topMargin = U.dp(this, 8);
        srl.bottomMargin = U.dp(this, 8);
        box.addView(sr, srl);

        final CheckBox spin = check("Hacer girar el vinilo mientras suena", prefs.spin());
        final CheckBox arm = check("Mostrar el brazo del tocadiscos", prefs.arm());
        final CheckBox pin = check("Fijar el reproductor (que no se oculte)", prefs.pinPlayer());
        box.addView(spin);
        box.addView(arm);
        box.addView(pin);
        box.addView(U.text(this, "Carátulas y datos de los álbumes", 14, Color.WHITE, true),
                new LinearLayout.LayoutParams(-2, U.dp(this, 40)));
        final CheckBox auto = check("Buscar carátulas, año y género automáticamente (usa internet)", prefs.autoMeta());
        box.addView(auto);
        Button now = new Button(this);
        now.setText("Buscar ahora en toda la biblioteca");
        now.setAllCaps(false);
        now.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                List<Album> todo = new ArrayList<>();
                for (Album a : albums) if (!a.hasCover || a.year.isEmpty() || a.genre.isEmpty()) todo.add(a);
                fetchMeta(todo, false, false);
            }
        });
        box.addView(now);
        TextView note = new TextView(this);
        note.setText("Fuentes: iTunes y MusicBrainz / Cover Art Archive. Solo se rellena lo que falta. "
                + "Mantén pulsado un álbum o artista para buscarlo a mano o elegir una carátula.");
        note.setTextSize(12);
        note.setTextColor(0xFF9B9BAB);
        box.addView(note);

        new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("Ajustes")
                .setView(sv)
                .setNegativeButton("Cancelar", null)
                .setPositiveButton("Guardar", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        boolean wasAuto = prefs.autoMeta();
                        prefs.setSettings(spin.isChecked(), arm.isChecked(), pin.isChecked(), auto.isChecked(), chosen[0]);
                        applySettings();
                        if (!pin.isChecked()) showBar();
                        if (auto.isChecked() && !wasAuto) autoMeta();
                        toast("Ajustes guardados");
                    }
                }).show();
    }

    private CheckBox check(String text, boolean on) {
        CheckBox c = new CheckBox(this);
        c.setText(text);
        c.setChecked(on);
        c.setTextColor(Color.WHITE);
        return c;
    }

    private void paintSwatch(View v, int idx, boolean sel) {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColors(new int[]{Accent.a(idx), Accent.b(idx)});
        g.setGradientType(GradientDrawable.LINEAR_GRADIENT);
        g.setOrientation(GradientDrawable.Orientation.TL_BR);
        g.setStroke(sel ? U.dp(this, 3) : 0, Color.WHITE);
        v.setBackground(g);
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    // ------------------------------------------------------------------ lista de la biblioteca
    private class RowAdapter extends BaseAdapter {
        class Holder {
            ImageView iv;
            TextView t1, t2, chev;
            String src;
        }

        @Override public int getCount() { return rows.size(); }
        @Override public Object getItem(int i) { return rows.get(i); }
        @Override public long getItemId(int i) { return i; }

        @Override
        public View getView(int i, View cv, ViewGroup parent) {
            final Holder hd;
            if (cv == null) {
                LinearLayout l = new LinearLayout(MainActivity.this);
                l.setGravity(Gravity.CENTER_VERTICAL);
                l.setPadding(U.dp(MainActivity.this, 8), U.dp(MainActivity.this, 7),
                        U.dp(MainActivity.this, 8), U.dp(MainActivity.this, 7));
                hd = new Holder();
                hd.iv = new ImageView(MainActivity.this);
                hd.iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
                hd.iv.setBackgroundColor(0x1AFFFFFF);
                l.addView(hd.iv, new LinearLayout.LayoutParams(U.dp(MainActivity.this, 50), U.dp(MainActivity.this, 50)));
                LinearLayout col = new LinearLayout(MainActivity.this);
                col.setOrientation(LinearLayout.VERTICAL);
                hd.t1 = U.text(MainActivity.this, "", 14, 0xFFF2F2F5, true);
                hd.t2 = U.text(MainActivity.this, "", 12, 0xFF9B9BAB, false);
                col.addView(hd.t1, new LinearLayout.LayoutParams(-1, -2));
                LinearLayout.LayoutParams l2 = new LinearLayout.LayoutParams(-1, -2);
                l2.topMargin = U.dp(MainActivity.this, 3);
                col.addView(hd.t2, l2);
                LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(0, -2, 1f);
                cl.leftMargin = U.dp(MainActivity.this, 12);
                l.addView(col, cl);
                hd.chev = U.text(MainActivity.this, "›", 20, 0xFF9B9BAB, false);
                l.addView(hd.chev, new LinearLayout.LayoutParams(-2, -2));
                l.setTag(hd);
                cv = l;
            } else {
                hd = (Holder) cv.getTag();
            }
            Row r = rows.get(i);
            Track cur = svc == null ? null : svc.current();
            boolean now = r.album != null && cur != null && cur.albumKey.equals(r.album.key);
            cv.setBackground(now ? U.round(U.alpha(Accent.a(prefs.accent()), 0.14f), U.dp(MainActivity.this, 14)) : null);
            final boolean circle = r.album == null;
            final float rad = U.dp(MainActivity.this, 8);
            hd.iv.setOutlineProvider(new ViewOutlineProvider() {
                @Override
                public void getOutline(View v, Outline o) {
                    if (circle) o.setOval(0, 0, v.getWidth(), v.getHeight());
                    else o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), rad);
                }
            });
            hd.iv.setClipToOutline(true);
            hd.chev.setVisibility(circle ? View.VISIBLE : View.GONE);
            String cover;
            if (r.album != null) {
                Album a = r.album;
                hd.t1.setText(a.title);
                hd.t1.setTextColor(now ? Accent.light(prefs.accent()) : 0xFFF2F2F5);
                StringBuilder sb = new StringBuilder(a.artist);
                if (!a.year.isEmpty()) sb.append(" · ").append(a.year);
                if (!a.genre.isEmpty()) sb.append(" · ").append(a.genre);
                sb.append(" · ").append(a.tracks.size()).append(" pistas");
                hd.t2.setText(sb.toString());
                cover = a.cover;
            } else {
                hd.t1.setText(r.artist);
                hd.t1.setTextColor(0xFFF2F2F5);
                int tracks = 0;
                String c = null;
                for (Album a : r.albums) {
                    tracks += a.tracks.size();
                    if (c == null && a.cover != null) c = a.cover;
                }
                hd.t2.setText(r.albums.size() + " álbum" + (r.albums.size() == 1 ? "" : "es") + " · " + tracks + " pistas");
                cover = c;
            }
            hd.src = cover;
            hd.iv.setImageBitmap(null);
            if (cover != null) {
                final Holder fh = hd;
                final String fc = cover;
                Covers.load(MainActivity.this, cover, U.dp(MainActivity.this, 100), new Covers.Callback() {
                    @Override
                    public void done(Bitmap bmp) {
                        if (fc.equals(fh.src) && bmp != null) fh.iv.setImageBitmap(bmp);
                    }
                });
            }
            return cv;
        }
    }
}

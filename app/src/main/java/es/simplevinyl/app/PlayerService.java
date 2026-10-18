package es.simplevinyl.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.graphics.drawable.Icon;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

import java.util.ArrayList;
import java.util.List;

/** Reproduce la cola de pistas, también con la pantalla apagada (servicio en primer plano). */
public class PlayerService extends Service {
    public static final String ACT_TOGGLE = "es.simplevinyl.app.TOGGLE";
    public static final String ACT_NEXT = "es.simplevinyl.app.NEXT";
    public static final String ACT_PREV = "es.simplevinyl.app.PREV";
    private static final String CHANNEL = "svp_playback";
    private static final int NOTIF_ID = 7;

    public class LocalBinder extends Binder {
        public PlayerService get() { return PlayerService.this; }
    }

    private final IBinder binder = new LocalBinder();

    public final List<Track> queue = new ArrayList<>();
    public int index = -1;
    /** idle | stopped | loading | playing | paused | error */
    public String state = "idle";
    public String errorMsg = "";
    public Runnable onChange;

    private MediaPlayer mp;
    private boolean prepared;
    private boolean fg;
    private boolean resumeOnFocus;
    private MediaSession session;
    private AudioManager am;
    private NotificationManager nm;

    private final AudioManager.OnAudioFocusChangeListener focusListener =
            new AudioManager.OnAudioFocusChangeListener() {
                @Override
                public void onAudioFocusChange(int change) {
                    switch (change) {
                        case AudioManager.AUDIOFOCUS_LOSS:
                            resumeOnFocus = false;
                            pause();
                            break;
                        case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT:
                            if (state.equals("playing")) {
                                resumeOnFocus = true;
                                pause();
                            }
                            break;
                        case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK:
                            if (mp != null && prepared) mp.setVolume(0.3f, 0.3f);
                            break;
                        case AudioManager.AUDIOFOCUS_GAIN:
                            if (mp != null && prepared) mp.setVolume(1f, 1f);
                            if (resumeOnFocus) {
                                resumeOnFocus = false;
                                resume();
                            }
                            break;
                        default:
                            break;
                    }
                }
            };

    private final BroadcastReceiver noisy = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            pause();
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(CHANNEL, "Reproducción", NotificationManager.IMPORTANCE_LOW);
            ch.setShowBadge(false);
            nm.createNotificationChannel(ch);
        }
        session = new MediaSession(this, "SimpleVinylPlayer");
        session.setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS | MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS);
        session.setCallback(new MediaSession.Callback() {
            @Override public void onPlay() { resume(); }
            @Override public void onPause() { pause(); }
            @Override public void onSkipToNext() { next(); }
            @Override public void onSkipToPrevious() { prev(); }
            @Override public void onSeekTo(long pos) { if (mp != null && prepared) mp.seekTo((int) pos); }
        });
        session.setActive(true);
        registerReceiver(noisy, new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY));
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String a = intent == null ? null : intent.getAction();
        if (ACT_TOGGLE.equals(a)) toggle();
        else if (ACT_NEXT.equals(a)) next();
        else if (ACT_PREV.equals(a)) prev();
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        releasePlayer();
        try { unregisterReceiver(noisy); } catch (Exception ignored) { }
        session.release();
        super.onDestroy();
    }

    // ---- cola ----
    /** Deja la cola lista sin reproducir (para retomar el último álbum). */
    public void prepareQueue(List<Track> items, int idx) {
        queue.clear();
        queue.addAll(items);
        index = Math.max(0, Math.min(items.size() - 1, idx));
        state = "stopped";
        changed();
    }

    public void playQueue(List<Track> items, int idx) {
        queue.clear();
        queue.addAll(items);
        playIndex(idx);
    }

    public Track current() {
        return index >= 0 && index < queue.size() ? queue.get(index) : null;
    }

    public void playIndex(int i) {
        if (i < 0 || i >= queue.size()) return;
        index = i;
        Track t = queue.get(i);
        releasePlayer();
        state = "loading";
        errorMsg = "";
        startFg();
        try {
            mp = new MediaPlayer();
            mp.setAudioStreamType(AudioManager.STREAM_MUSIC);
            mp.setWakeMode(this, PowerManager.PARTIAL_WAKE_LOCK);
            mp.setDataSource(this, t.uri());
            mp.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
                @Override
                public void onPrepared(MediaPlayer m) {
                    prepared = true;
                    if (requestFocus()) {
                        m.start();
                        state = "playing";
                    } else {
                        state = "paused";
                    }
                    changed();
                }
            });
            mp.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
                @Override
                public void onCompletion(MediaPlayer m) {
                    if (index + 1 < queue.size()) {
                        playIndex(index + 1);
                    } else {
                        state = "stopped";
                        stopFg();
                        changed();
                    }
                }
            });
            mp.setOnErrorListener(new MediaPlayer.OnErrorListener() {
                @Override
                public boolean onError(MediaPlayer m, int what, int extra) {
                    errorMsg = "No se puede reproducir esta pista";
                    state = "error";
                    if (index + 1 < queue.size()) {
                        playIndex(index + 1);
                    } else {
                        stopFg();
                        changed();
                    }
                    return true;
                }
            });
            mp.prepareAsync();
        } catch (Exception e) {
            errorMsg = "No se puede reproducir esta pista";
            state = "error";
            stopFg();
        }
        changed();
    }

    public void toggle() {
        if (state.equals("playing")) pause();
        else if (state.equals("paused")) resume();
        else if (index >= 0) playIndex(index);
    }

    public void pause() {
        if (mp != null && prepared && state.equals("playing")) {
            mp.pause();
            state = "paused";
            stopForeground(false);
            fg = false;
            changed();
        }
    }

    public void resume() {
        if (mp != null && prepared && state.equals("paused")) {
            if (!requestFocus()) return;
            mp.start();
            state = "playing";
            startFg();
            changed();
        }
    }

    public void next() {
        if (index + 1 < queue.size()) playIndex(index + 1);
    }

    public void prev() {
        if (mp != null && prepared && mp.getCurrentPosition() > 3000) {
            mp.seekTo(0);
            changed();
        } else if (index > 0) {
            playIndex(index - 1);
        }
    }

    public void seekFrac(float f) {
        if (mp != null && prepared) {
            int d = mp.getDuration();
            if (d > 0) mp.seekTo((int) (Math.max(0f, Math.min(1f, f)) * d));
        }
    }

    public int position() {
        try { return mp != null && prepared ? mp.getCurrentPosition() : 0; } catch (Exception e) { return 0; }
    }

    public int duration() {
        try { return mp != null && prepared ? Math.max(0, mp.getDuration()) : 0; } catch (Exception e) { return 0; }
    }

    // ---- interno ----
    private boolean requestFocus() {
        return am.requestAudioFocus(focusListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN)
                == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
    }

    private void releasePlayer() {
        prepared = false;
        if (mp != null) {
            try { mp.reset(); } catch (Exception ignored) { }
            try { mp.release(); } catch (Exception ignored) { }
            mp = null;
        }
    }

    private void startFg() {
        try {
            startService(new Intent(this, PlayerService.class));
            Notification n = buildNotification();
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
            } else {
                startForeground(NOTIF_ID, n);
            }
            fg = true;
        } catch (Exception ignored) {
        }
    }

    private void stopFg() {
        stopForeground(false);
        fg = false;
        nm.cancel(NOTIF_ID);
    }

    private void changed() {
        updateSession();
        if (fg || state.equals("paused")) {
            try { nm.notify(NOTIF_ID, buildNotification()); } catch (Exception ignored) { }
        }
        if (onChange != null) onChange.run();
    }

    private void updateSession() {
        int st;
        switch (state) {
            case "playing": st = PlaybackState.STATE_PLAYING; break;
            case "paused": st = PlaybackState.STATE_PAUSED; break;
            case "loading": st = PlaybackState.STATE_BUFFERING; break;
            case "error": st = PlaybackState.STATE_ERROR; break;
            default: st = PlaybackState.STATE_STOPPED; break;
        }
        long actions = PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE | PlaybackState.ACTION_PLAY_PAUSE
                | PlaybackState.ACTION_SKIP_TO_NEXT | PlaybackState.ACTION_SKIP_TO_PREVIOUS
                | PlaybackState.ACTION_SEEK_TO;
        session.setPlaybackState(new PlaybackState.Builder().setActions(actions)
                .setState(st, position(), 1f).build());
    }

    private PendingIntent act(String action, int code) {
        Intent i = new Intent(this, PlayerService.class).setAction(action);
        return PendingIntent.getService(this, code, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private Notification buildNotification() {
        Track t = current();
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL) : new Notification.Builder(this);
        Intent open = new Intent(this, MainActivity.class).setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        b.setSmallIcon(R.drawable.ic_stat)
                .setContentTitle(t == null ? "Simple Vinyl Player" : t.title)
                .setContentText(t == null ? "" : t.artist)
                .setContentIntent(PendingIntent.getActivity(this, 0, open,
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE))
                .setOngoing(state.equals("playing"))
                .setShowWhen(false)
                .setVisibility(Notification.VISIBILITY_PUBLIC);
        boolean playing = state.equals("playing") || state.equals("loading");
        b.addAction(new Notification.Action.Builder(Icon.createWithResource(this, R.drawable.ic_prev),
                "Anterior", act(ACT_PREV, 1)).build());
        b.addAction(new Notification.Action.Builder(Icon.createWithResource(this,
                playing ? R.drawable.ic_pause : R.drawable.ic_play),
                playing ? "Pausa" : "Reproducir", act(ACT_TOGGLE, 2)).build());
        b.addAction(new Notification.Action.Builder(Icon.createWithResource(this, R.drawable.ic_next),
                "Siguiente", act(ACT_NEXT, 3)).build());
        b.setStyle(new Notification.MediaStyle().setMediaSession(session.getSessionToken())
                .setShowActionsInCompactView(0, 1, 2));
        return b.build();
    }
}

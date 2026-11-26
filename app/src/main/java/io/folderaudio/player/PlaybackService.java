package io.folderaudio.player;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.AssetFileDescriptor;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Binder;
import android.os.Build;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class PlaybackService extends Service {
    static final String[] OUTPUT_POLICIES = {"Prefer exclusive", "Exclusive only", "Shared"};
    private static final int NOTIFICATION_ID = 1;
    private static final String CHANNEL = "playback";
    private static final String TOGGLE = "io.folderaudio.TOGGLE";
    private static final String NEXT = "io.folderaudio.NEXT";
    private static final String PREVIOUS = "io.folderaudio.PREVIOUS";
    private static final String STOP = "io.folderaudio.STOP";

    final class LocalBinder extends Binder {
        PlaybackService service() { return PlaybackService.this; }
    }

    private static final class Job {
        final Library.Track track;
        final int policy;
        final long start;
        final CancellationSignal cancellation = new CancellationSignal();
        volatile boolean cancelled;
        volatile boolean opening;

        Job(Library.Track track, int policy, long start) {
            this.track = track;
            this.policy = policy;
            this.start = start;
        }
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private final LocalBinder binder = new LocalBinder();
    private HandlerThread thread;
    private Handler worker;
    private long handle;
    private long lastProgress;
    private Job job;
    private AudioManager audio;
    private AudioFocusRequest focus;
    private boolean focused;
    private boolean resumeOnFocus;
    private boolean foreground;
    private boolean started;
    private long lastDisconnect = -10_000;
    private PowerManager.WakeLock wakeLock;
    private MediaSession media;
    private Runnable listener;
    private List<Library.Track> queue = Collections.emptyList();
    private int index = -1;

    long position;
    long duration;
    int rate;
    boolean exclusive;
    boolean preparing;
    boolean playing;
    String error;

    Library.Track current() { return index < 0 ? null : queue.get(index); }
    boolean active() { return preparing || playing; }
    boolean hasNext() { return index >= 0 && index + 1 < queue.size(); }

    @Override public void onCreate() {
        super.onCreate();
        thread = new HandlerThread("Folder Audio playback", android.os.Process.THREAD_PRIORITY_AUDIO);
        thread.start();
        worker = new Handler(thread.getLooper());
        audio = getSystemService(AudioManager.class);
        wakeLock = getSystemService(PowerManager.class).newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK, "FolderAudio:Playback");
        AudioAttributes attributes = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build();
        focus = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(attributes)
                .setWillPauseWhenDucked(true)
                .setOnAudioFocusChangeListener(this::focusChanged, main).build();
        NotificationChannel channel = new NotificationChannel(CHANNEL, "Playback", NotificationManager.IMPORTANCE_LOW);
        getSystemService(NotificationManager.class).createNotificationChannel(channel);
        media = new MediaSession(this, "Folder Audio");
        media.setCallback(new MediaSession.Callback() {
            @Override public void onPlay() { resume(); }
            @Override public void onPause() { pause(); }
            @Override public void onSkipToNext() { next(); }
            @Override public void onSkipToPrevious() { previous(); }
            @Override public void onSeekTo(long millis) { seek(millis); }
            @Override public void onStop() { stop(); }
        }, main);
        IntentFilter filter = new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(noisy, filter, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(noisy, filter);
    }

    private final BroadcastReceiver noisy = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (active() || resumeOnFocus) pause();
        }
    };

    @Override public IBinder onBind(Intent intent) { return binder; }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        started = true;
        if (intent != null && intent.getAction() != null) {
            switch (intent.getAction()) {
                case TOGGLE: toggle(); break;
                case PREVIOUS: previous(); break;
                case NEXT: next(); break;
                case STOP: stop(); break;
                default: break;
            }
        }
        return START_NOT_STICKY;
    }

    void listen(Runnable changed) {
        listener = changed;
        if (changed != null) changed.run();
    }

    void play(List<Library.Track> tracks, int selected) {
        queue = new ArrayList<>(tracks);
        select(selected);
    }

    private void select(int selected) {
        index = selected;
        duration = 0;
        begin(0);
    }

    private void resume() {
        if (active() || current() == null) return;
        begin(duration > 0 && position >= duration ? 0 : position);
    }

    void toggle() {
        if (active()) pause();
        else resume();
    }

    void next() { if (hasNext()) select(index + 1); }

    void previous() {
        if (current() == null) return;
        if (position > 3000 || index == 0) seek(0);
        else select(index - 1);
    }

    void seek(long millis) {
        if (current() == null) return;
        long target = Math.min(Math.max(0, duration - 1), Math.max(0, millis));
        if (active()) begin(target);
        else {
            position = target;
            publish();
        }
    }

    static int policy(Context context) {
        return context.getSharedPreferences("output", MODE_PRIVATE).getInt("policy", 0);
    }

    void changeOutput() {
        if (active()) begin(position);
    }

    void changeEq() {
        if (!active()) return;
        EqSettings settings = EqSettings.load(this);
        Job updating = job;
        worker.post(() -> {
            if (!updating.cancelled && handle != 0) {
                NativePlayer.setEq(handle, settings.enabled, settings.values);
            }
        });
    }

    void dropFolder(android.net.Uri tree) {
        if (queue.isEmpty()) return;
        Library.Track current = current();
        List<Library.Track> retained = new ArrayList<>();
        String root = android.provider.DocumentsContract.getTreeDocumentId(tree);
        for (Library.Track track : queue) {
            if (!tree.getAuthority().equals(track.uri.getAuthority()) ||
                    !root.equals(android.provider.DocumentsContract.getTreeDocumentId(track.uri))) {
                retained.add(track);
            }
        }
        index = retained.indexOf(current);
        queue = retained;
        if (index < 0) stop();
        else publish();
    }

    private void begin(long start) {
        cancelJob();
        position = start;
        rate = 0;
        exclusive = false;
        error = null;
        playing = false;
        preparing = true;
        resumeOnFocus = false;
        try {
            if (!foreground) {
                if (!started) {
                    startService(new Intent(this, PlaybackService.class));
                    started = true;
                }
                media.setActive(true);
                startForeground(NOTIFICATION_ID, notification());
                foreground = true;
            }
            if (!focused) {
                focused = audio.requestAudioFocus(focus) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
                if (!focused) {
                    fail("Audio focus is unavailable. Try playing again.");
                    return;
                }
            }
            if (!wakeLock.isHeld()) wakeLock.acquire();
        } catch (IllegalStateException | SecurityException failure) {
            fail("Android couldn't start background playback.");
            return;
        }
        EqSettings settings = EqSettings.load(this);
        Job next = new Job(current(), policy(this), start);
        job = next;
        publish();
        worker.post(() -> open(next, settings));
    }

    private void open(Job request, EqSettings settings) {
        request.opening = true;
        try {
            if (request.cancelled) return;
            openDocument(request, settings);
            if (request.cancelled) return;
            long[] info = NativePlayer.info(handle);
            lastProgress = 0;
            main.post(() -> {
                if (job != request) return;
                updateInfo(info);
                preparing = false;
                playing = true;
                publish();
            });
            worker.post(() -> pump(request));
        } catch (IOException | RuntimeException | LinkageError failure) {
            closeNative();
            main.post(() -> {
                if (job == request) fail(failure instanceof LinkageError
                        ? "The audio engine could not load on this device." : failure.getMessage());
            });
        } finally {
            request.opening = false;
        }
    }

    private void openDocument(Job request, EqSettings settings) throws IOException {
        try (AssetFileDescriptor asset = getContentResolver()
                .openAssetFileDescriptor(request.track.uri, "r", request.cancellation)) {
            if (asset == null) throw new IOException("The folder provider couldn't open this file.");
            if (request.cancelled) return;
            int fd = asset.getParcelFileDescriptor().getFd();
            long length = asset.getLength();
            if (length >= 0 && seekable(asset)) {
                handle = NativePlayer.open(fd, asset.getStartOffset(), length,
                        request.policy, request.start, settings.enabled, settings.values);
            } else {
                openCached(request, settings, asset);
            }
        }
    }

    private static boolean seekable(AssetFileDescriptor asset) {
        try {
            Os.lseek(asset.getFileDescriptor(), 0, OsConstants.SEEK_CUR);
            return true;
        } catch (ErrnoException notSeekable) {
            return false;
        }
    }

    private void openCached(Job request, EqSettings settings, AssetFileDescriptor asset) throws IOException {
        File file = File.createTempFile("playback-", ".audio", getCacheDir());
        try {
            try (InputStream input = asset.createInputStream();
                 FileOutputStream output = new FileOutputStream(file)) {
                byte[] buffer = new byte[64 * 1024];
                int count;
                while (!request.cancelled && (count = input.read(buffer)) != -1) {
                    output.write(buffer, 0, count);
                }
            }
            if (request.cancelled) return;
            try (android.os.ParcelFileDescriptor cached = android.os.ParcelFileDescriptor.open(
                    file, android.os.ParcelFileDescriptor.MODE_READ_ONLY)) {
                handle = NativePlayer.open(cached.getFd(), 0, file.length(), request.policy,
                        request.start, settings.enabled, settings.values);
            }
        } finally {
            file.delete();
        }
    }

    private void pump(Job current) {
        if (current.cancelled || handle == 0) return;
        try {
            int result = NativePlayer.step(handle);
            boolean terminal = result == NativePlayer.FINISHED || result == NativePlayer.DISCONNECTED;
            long now = SystemClock.elapsedRealtime();
            if (terminal || now - lastProgress >= 180) {
                lastProgress = now;
                long[] info = NativePlayer.info(handle);
                main.post(() -> {
                    if (job != current) return;
                    updateInfo(info);
                    if (result == NativePlayer.FINISHED) {
                        if (hasNext()) next();
                        else {
                            if (duration > 0) position = duration;
                            pause();
                        }
                    } else if (result == NativePlayer.DISCONNECTED) {
                        if (now - lastDisconnect < 1200) {
                            fail("Audio output disconnected. Press Play to retry.");
                        } else {
                            lastDisconnect = now;
                            begin(position);
                        }
                    } else {
                        notifyListener();
                    }
                });
            }
            if (!terminal) {
                worker.postDelayed(() -> pump(current), result == NativePlayer.WAITING ? 2 : 0);
            }
        } catch (IOException failure) {
            closeNative();
            main.post(() -> {
                if (job == current) fail(failure.getMessage());
            });
        }
    }

    private void updateInfo(long[] info) {
        duration = info[1];
        position = duration > 0 ? Math.min(duration, info[0]) : info[0];
        rate = (int) info[2];
        exclusive = info[3] == 1;
    }

    private void focusChanged(int change) {
        if (change == AudioManager.AUDIOFOCUS_GAIN) {
            if (resumeOnFocus) resume();
        } else if (change == AudioManager.AUDIOFOCUS_LOSS) {
            pause();
        } else if (active()) {
            resumeOnFocus = true;
            suspend();
        }
    }

    void pause() {
        resumeOnFocus = false;
        if (focused) audio.abandonAudioFocusRequest(focus);
        focused = false;
        suspend();
    }

    private void suspend() {
        cancelJob();
        playing = false;
        preparing = false;
        rate = 0;
        exclusive = false;
        if (wakeLock.isHeld()) wakeLock.release();
        if (foreground) stopForeground(STOP_FOREGROUND_DETACH);
        foreground = false;
        publish();
    }

    private void cancelJob() {
        if (job == null) return;
        Job previous = job;
        job = null;
        previous.cancelled = true;
        // Cancellation may cross into a documents provider. Keep that IPC off the main thread.
        if (previous.opening) {
            new Thread(previous.cancellation::cancel, "Audio document cancellation").start();
        }
        worker.post(this::closeNative);
    }

    private void closeNative() {
        if (handle == 0) return;
        NativePlayer.close(handle);
        handle = 0;
    }

    private void fail(String detail) {
        error = detail == null ? "Unable to play this file." : detail;
        pause();
    }

    private void stop() {
        index = -1;
        queue = Collections.emptyList();
        position = duration = 0;
        error = null;
        pause();
        stopForeground(STOP_FOREGROUND_REMOVE);
        getSystemService(NotificationManager.class).cancel(NOTIFICATION_ID);
        media.setActive(false);
        started = false;
        stopSelf();
    }

    private void publish() {
        Library.Track track = current();
        int state = track == null ? PlaybackState.STATE_NONE
                : preparing ? PlaybackState.STATE_BUFFERING
                : playing ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED;
        long actions = PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE
                | PlaybackState.ACTION_PLAY_PAUSE | PlaybackState.ACTION_STOP
                | PlaybackState.ACTION_SKIP_TO_PREVIOUS | PlaybackState.ACTION_SEEK_TO;
        if (hasNext()) actions |= PlaybackState.ACTION_SKIP_TO_NEXT;
        media.setPlaybackState(new PlaybackState.Builder().setActions(actions)
                .setState(state, position, playing ? 1 : 0).build());
        if (track != null) {
            media.setMetadata(new MediaMetadata.Builder()
                    .putString(MediaMetadata.METADATA_KEY_TITLE, track.name)
                    .putString(MediaMetadata.METADATA_KEY_ARTIST, track.folder)
                    .putLong(MediaMetadata.METADATA_KEY_DURATION, duration).build());
            getSystemService(NotificationManager.class).notify(NOTIFICATION_ID, notification());
        }
        notifyListener();
    }

    private void notifyListener() { if (listener != null) listener.run(); }

    private PendingIntent command(String action, int code) {
        return PendingIntent.getService(this, code, new Intent(this, PlaybackService.class).setAction(action),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private Notification notification() {
        Library.Track track = current();
        PendingIntent show = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification.Builder builder = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_audio)
                .setContentTitle(track == null ? "Folder Audio" : track.name)
                .setContentText(error != null ? error : track == null ? "" : track.folder)
                .setContentIntent(show)
                .setDeleteIntent(command(STOP, 4))
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setCategory(Notification.CATEGORY_TRANSPORT)
                .addAction(new Notification.Action.Builder(
                        android.R.drawable.ic_media_previous, "Previous", command(PREVIOUS, 1)).build())
                .addAction(new Notification.Action.Builder(
                        active() ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play,
                        active() ? "Pause" : "Play", command(TOGGLE, 2)).build());
        Notification.MediaStyle style = new Notification.MediaStyle().setMediaSession(media.getSessionToken());
        if (hasNext()) {
            builder.addAction(new Notification.Action.Builder(android.R.drawable.ic_media_next,
                    "Next", command(NEXT, 3)).build());
            style.setShowActionsInCompactView(0, 1, 2);
        } else {
            style.setShowActionsInCompactView(0, 1);
        }
        return builder.setStyle(style).setOngoing(active()).build();
    }

    @Override public void onDestroy() {
        listener = null;
        cancelJob();
        worker.post(this::closeNative);
        thread.quitSafely();
        if (wakeLock.isHeld()) wakeLock.release();
        if (focused) audio.abandonAudioFocusRequest(focus);
        unregisterReceiver(noisy);
        media.release();
        getSystemService(NotificationManager.class).cancel(NOTIFICATION_ID);
        super.onDestroy();
    }
}

package io.folderaudio.player;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.os.IBinder;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.inputmethod.EditorInfo;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public final class MainActivity extends Activity {
    private static final int PICK_FOLDER = 10;
    private static final int BG = Color.rgb(18, 24, 22);
    private static final int SURFACE = Color.rgb(25, 34, 30);
    private static final int LINE = Color.rgb(54, 70, 63);
    private static final int WHITE = Color.rgb(236, 243, 239);
    private static final int MUTED = Color.rgb(171, 191, 181);
    private static final int ACCENT = Color.rgb(159, 214, 192);

    private final ExecutorService scanner = Executors.newSingleThreadExecutor();
    private Future<?> pendingScan;
    private int scanId;
    private List<Library.Track> tracks = Collections.emptyList();
    private String scanStatus = "";
    private TrackAdapter adapter;
    private EqSettings eq;
    private boolean eqTab;
    private boolean scrubbing;
    private boolean bound;
    private PlaybackService service;
    private FrameLayout content;
    private Button libraryTab, equalizerTab, previous, play, next;
    private TextView currentTitle, currentDetail, time, libraryStatus;
    private SeekBar progress;
    private Uri highlighted;
    private int eqScroll;
    private ScrollView eqView;

    private final ServiceConnection connection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder binder) {
            service = ((PlaybackService.LocalBinder) binder).service();
            service.listen(MainActivity.this::updatePlayer);
        }

        @Override public void onServiceDisconnected(ComponentName name) {
            service = null;
            updatePlayer();
        }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        setVolumeControlStream(android.media.AudioManager.STREAM_MUSIC);
        eq = EqSettings.load(this);
        if (state != null) eqTab = state.getBoolean("eq");
        LinearLayout root = column();
        root.setBackgroundColor(BG);
        root.setPadding(dp(16), dp(8), dp(16), dp(8));
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            if (android.os.Build.VERSION.SDK_INT >= 35) {
                android.graphics.Insets safe = insets.getInsets(
                        WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                view.setPadding(dp(16) + safe.left, dp(8) + safe.top,
                        dp(16) + safe.right, dp(8) + safe.bottom);
            }
            return insets;
        });
        setContentView(root);

        LinearLayout titleRow = row();
        TextView appTitle = text("Folder Audio", 25, WHITE);
        appTitle.setTypeface(null, Typeface.BOLD);
        titleRow.addView(appTitle, new LinearLayout.LayoutParams(0, -2, 1));
        Button output = button("Output", v -> chooseOutput());
        titleRow.addView(output, new LinearLayout.LayoutParams(-2, dp(48)));
        root.addView(titleRow);

        LinearLayout tabs = row();
        libraryTab = button("Library", v -> showTab(false));
        equalizerTab = button("Equalizer", v -> showTab(true));
        tabs.addView(libraryTab, new LinearLayout.LayoutParams(0, dp(48), 1));
        tabs.addView(equalizerTab, new LinearLayout.LayoutParams(0, dp(48), 1));
        root.addView(tabs);
        root.addView(rule());

        content = new FrameLayout(this);
        root.addView(content, new LinearLayout.LayoutParams(-1, 0, 1));
        root.addView(rule());
        root.addView(makePlayer());
        showTab(eqTab);
        refresh();
        updatePlayer();
    }

    @Override protected void onStart() {
        super.onStart();
        bound = bindService(new Intent(this, PlaybackService.class), connection, Context.BIND_AUTO_CREATE);
    }

    @Override protected void onStop() {
        if (service != null) service.listen(null);
        if (bound) unbindService(connection);
        bound = false;
        service = null;
        super.onStop();
    }

    @Override protected void onSaveInstanceState(Bundle out) {
        out.putBoolean("eq", eqTab);
        super.onSaveInstanceState(out);
    }

    @Override protected void onDestroy() {
        scanner.shutdownNow();
        super.onDestroy();
    }

    private void showTab(boolean equalizer) {
        if (eqView != null) eqScroll = eqView.getScrollY();
        eqView = null;
        adapter = null;
        libraryStatus = null;
        eqTab = equalizer;
        styleTab(libraryTab, !equalizer);
        styleTab(equalizerTab, equalizer);
        content.removeAllViews();
        content.addView(equalizer ? makeEqualizer() : makeLibrary(),
                new FrameLayout.LayoutParams(-1, -1));
    }

    private void styleTab(Button view, boolean selected) {
        view.setSelected(selected);
        view.setTextColor(selected ? ACCENT : MUTED);
        view.setBackgroundTintList(ColorStateList.valueOf(selected ? SURFACE : BG));
    }

    private View makeLibrary() {
        LinearLayout panel = column();
        LinearLayout toolbar = row();
        toolbar.addView(button("Add folder", v -> pickFolder()));
        toolbar.addView(button("Folders", v -> folders()));
        toolbar.addView(button("Refresh", v -> refresh()));
        panel.addView(toolbar);
        libraryStatus = text(scanStatus, 13, MUTED);
        libraryStatus.setPadding(dp(3), dp(5), dp(3), dp(10));
        panel.addView(libraryStatus);
        ListView list = new ListView(this);
        list.setDivider(new android.graphics.drawable.ColorDrawable(LINE));
        list.setDividerHeight(dp(1));
        list.setClipToPadding(false);
        list.setPadding(0, 0, 0, dp(8));
        adapter = new TrackAdapter();
        list.setAdapter(adapter);
        list.setOnItemClickListener((parent, view, position, id) -> {
            if (service != null) service.play(tracks, position);
        });
        panel.addView(list, new LinearLayout.LayoutParams(-1, 0, 1));
        return panel;
    }

    private void pickFolder() {
        Intent pick = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        pick.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION |
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION |
                Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
        try {
            startActivityForResult(pick, PICK_FOLDER);
        } catch (android.content.ActivityNotFoundException unavailable) {
            Toast.makeText(this, "This device has no folder picker.", Toast.LENGTH_LONG).show();
        }
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != PICK_FOLDER || result != RESULT_OK || data == null || data.getData() == null) return;
        try {
            getContentResolver().takePersistableUriPermission(data.getData(),
                    Intent.FLAG_GRANT_READ_URI_PERMISSION);
            refresh();
        } catch (SecurityException failure) {
            Toast.makeText(this, "This folder provider did not grant persistent access.",
                    Toast.LENGTH_LONG).show();
        }
    }

    private void folders() {
        List<Uri> roots = Library.roots(this);
        AlertDialog.Builder dialog = new AlertDialog.Builder(this).setTitle("Audio folders")
                .setNegativeButton("Close", null).setPositiveButton("Add folder", (d, w) -> pickFolder());
        if (roots.isEmpty()) {
            dialog.setMessage("Choose the folders you want to include.");
        } else {
            String[] names = new String[roots.size()];
            for (int i = 0; i < roots.size(); i++) {
                names[i] = android.provider.DocumentsContract.getTreeDocumentId(roots.get(i));
            }
            dialog.setItems(names, (d, which) -> {
                Uri root = roots.get(which);
                new AlertDialog.Builder(this).setTitle("Remove folder?")
                        .setMessage("Files in " + Library.label(root) + " will be removed from this library.")
                        .setNegativeButton("Cancel", null).setPositiveButton("Remove", (confirm, w) -> {
                            try {
                                getContentResolver().releasePersistableUriPermission(root,
                                        Intent.FLAG_GRANT_READ_URI_PERMISSION);
                            } catch (SecurityException alreadyRemoved) {
                                // The provider may have revoked this grant since the dialog opened.
                            }
                            if (service != null) service.dropFolder(root);
                            tracks = Collections.emptyList();
                            if (adapter != null) adapter.notifyDataSetChanged();
                            refresh();
                        }).show();
            });
        }
        dialog.show();
    }

    private void refresh() {
        if (pendingScan != null) pendingScan.cancel(true);
        int started = ++scanId;
        List<Uri> roots = Library.roots(this);
        if (roots.isEmpty()) {
            tracks = Collections.emptyList();
            status("Choose a folder to find your audio files.");
            if (adapter != null) adapter.notifyDataSetChanged();
            return;
        }
        status("Reading " + count(roots.size(), "folder") + "…");
        pendingScan = scanner.submit(() -> {
            Library.Scan scan = Library.scan(getContentResolver(), roots);
            runOnUiThread(() -> {
                if (started != scanId || isDestroyed()) return;
                tracks = scan.tracks;
                String detail = count(tracks.size(), "track") + " · " + count(roots.size(), "folder");
                if (!scan.inaccessible.isEmpty()) {
                    detail += " · Couldn’t read " + String.join(", ", scan.inaccessible);
                }
                status(detail);
                if (adapter != null) adapter.notifyDataSetChanged();
            });
        });
    }

    private static String count(int size, String unit) {
        return size + " " + unit + (size == 1 ? "" : "s");
    }

    private void status(String value) {
        scanStatus = value;
        if (libraryStatus != null) libraryStatus.setText(value);
    }

    private View makeEqualizer() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout controls = column();
        controls.setPadding(0, dp(10), 0, dp(12));
        scroll.addView(controls);
        LinearLayout header = row();
        Switch enabled = new Switch(this);
        enabled.setText("Enable EQ");
        enabled.setTextColor(WHITE);
        enabled.setChecked(eq.enabled);
        enabled.setOnCheckedChangeListener((view, checked) -> {
            eq.enabled = checked;
            saveEq();
        });
        header.addView(enabled, new LinearLayout.LayoutParams(0, dp(48), 1));
        header.addView(button("Reset", v -> {
            eq = new EqSettings();
            saveEq();
            showTab(true);
        }));
        controls.addView(header);
        controls.addView(makeGain("Pre-gain", 0, null));
        controls.addView(rule());
        for (int i = 0; i < EqSettings.CENTERS.length; i++) {
            final int band = i;
            int offset = 1 + 3 * i;
            controls.addView(makeGain(bandLabel(offset), offset + 1, v -> editBand(band)));
            controls.addView(rule());
        }
        eqView = scroll;
        scroll.post(() -> scroll.scrollTo(0, eqScroll));
        return scroll;
    }

    private View makeGain(String title, int valueIndex, View.OnClickListener edit) {
        LinearLayout line = column();
        LinearLayout heading = row();
        TextView label = text(title, 15, WHITE);
        label.setMinHeight(dp(42));
        label.setGravity(Gravity.CENTER_VERTICAL);
        label.setPadding(dp(3), 0, 0, 0);
        heading.addView(label, new LinearLayout.LayoutParams(0, -2, 1));
        if (edit != null) {
            Button editButton = button("Edit", edit);
            editButton.setContentDescription("Edit " + title);
            heading.addView(editButton);
        }
        TextView db = text(decibels(eq.values[valueIndex]), 14, ACCENT);
        db.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        heading.addView(db, new LinearLayout.LayoutParams(dp(80), dp(42)));
        line.addView(heading);
        SeekBar gain = new SeekBar(this);
        gain.setMax(96);
        gain.setProgress(Math.round((eq.values[valueIndex] + 12) * 4));
        gain.setContentDescription(title + " gain");
        gain.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int value, boolean fromUser) {
                if (!fromUser) return;
                eq.values[valueIndex] = value / 4.0f - 12;
                db.setText(decibels(eq.values[valueIndex]));
                saveEq();
            }

            @Override public void onStartTrackingTouch(SeekBar bar) {}
            @Override public void onStopTrackingTouch(SeekBar bar) {}
        });
        line.addView(gain, new LinearLayout.LayoutParams(-1, dp(48)));
        return line;
    }

    private static String decibels(float gain) {
        return gain == 0 ? "0.00 dB" : String.format(Locale.US, "%+.2f dB", gain);
    }

    private String bandLabel(int offset) {
        return String.format(Locale.US, "%s Hz · Q %s", compact(eq.values[offset]), compact(eq.values[offset + 2]));
    }

    private static String compact(float value) {
        return value == Math.round(value) ? Integer.toString(Math.round(value)) : Float.toString(value);
    }

    private void editBand(int band) {
        int offset = 1 + 3 * band;
        LinearLayout form = column();
        form.setPadding(dp(24), dp(10), dp(24), 0);
        EditText hz = input(form, "Frequency in Hz (10–22,000)", eq.values[offset]);
        EditText q = input(form, "Q (0.1–20)", eq.values[offset + 2]);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("Band " + (band + 1))
                .setView(form).setNegativeButton("Cancel", null).setPositiveButton("Save", null).create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            Float frequency = number(hz, 10, 22000);
            Float width = number(q, 0.1f, 20);
            if (frequency == null || width == null) return;
            eq.values[offset] = frequency;
            eq.values[offset + 2] = width;
            saveEq();
            dialog.dismiss();
            showTab(true);
        }));
        dialog.show();
    }

    private EditText input(LinearLayout form, String label, float value) {
        TextView caption = text(label, 14, MUTED);
        form.addView(caption);
        EditText field = new EditText(this);
        field.setId(View.generateViewId());
        caption.setLabelFor(field.getId());
        field.setInputType(EditorInfo.TYPE_CLASS_NUMBER | EditorInfo.TYPE_NUMBER_FLAG_DECIMAL);
        field.setText(compact(value));
        field.setSelectAllOnFocus(true);
        form.addView(field, new LinearLayout.LayoutParams(-1, -2));
        return field;
    }

    private static Float number(EditText field, float minimum, float maximum) {
        try {
            float value = Float.parseFloat(field.getText().toString().trim().replace(',', '.'));
            if (Float.isFinite(value) && value >= minimum && value <= maximum) return value;
        } catch (NumberFormatException ignored) {
            // Show the range next to the invalid field.
        }
        field.setError("Enter a value from " + compact(minimum) + " to " + compact(maximum) + ".");
        return null;
    }

    private void saveEq() {
        eq.save(this);
        if (service != null) service.changeEq();
    }

    private View makePlayer() {
        LinearLayout panel = column();
        panel.setPadding(dp(8), dp(8), dp(8), 0);
        currentTitle = text("No track selected", 16, WHITE);
        currentTitle.setTypeface(null, Typeface.BOLD);
        currentTitle.setMaxLines(1);
        currentTitle.setEllipsize(android.text.TextUtils.TruncateAt.END);
        panel.addView(currentTitle);
        currentDetail = text("", 13, MUTED);
        currentDetail.setMaxLines(3);
        panel.addView(currentDetail);
        LinearLayout seekRow = row();
        progress = new SeekBar(this);
        progress.setMax(1000);
        progress.setContentDescription("Playback position");
        progress.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int value, boolean fromUser) {
                if (fromUser && service != null) {
                    long target = service.duration * value / 1000;
                    time.setText(formatTime(target) + " / " + formatTime(service.duration));
                    if (!scrubbing) service.seek(target);
                }
            }

            @Override public void onStartTrackingTouch(SeekBar bar) { scrubbing = true; }

            @Override public void onStopTrackingTouch(SeekBar bar) {
                if (service != null) service.seek(service.duration * bar.getProgress() / 1000);
                scrubbing = false;
                updatePlayer();
            }
        });
        seekRow.addView(progress, new LinearLayout.LayoutParams(0, dp(38), 1));
        time = text("0:00 / 0:00", 12, MUTED);
        time.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        seekRow.addView(time, new LinearLayout.LayoutParams(-2, dp(38)));
        panel.addView(seekRow);
        LinearLayout controls = row();
        previous = button("Previous", v -> { if (service != null) service.previous(); });
        play = button("Play", v -> { if (service != null) service.toggle(); });
        next = button("Next", v -> { if (service != null) service.next(); });
        controls.addView(previous, new LinearLayout.LayoutParams(0, dp(48), 1));
        controls.addView(play, new LinearLayout.LayoutParams(0, dp(48), 1));
        controls.addView(next, new LinearLayout.LayoutParams(0, dp(48), 1));
        panel.addView(controls);
        return panel;
    }

    private void updatePlayer() {
        Library.Track track = service == null ? null : service.current();
        Uri uri = track == null ? null : track.uri;
        if (!java.util.Objects.equals(uri, highlighted)) {
            highlighted = uri;
            if (adapter != null) adapter.notifyDataSetChanged();
        }
        currentTitle.setText(track == null ? "No track selected" : track.name);
        int policy = PlaybackService.policy(this);
        String detail = PlaybackService.OUTPUT_POLICIES[policy];
        if (track != null) {
            if (service.error != null) detail = service.error;
            else if (service.preparing) detail = "Opening audio…";
            else if (service.rate > 0) {
                detail = (service.exclusive ? "Exclusive · Android mixer bypass" :
                        policy == 0 ? "Shared fallback" : "Shared")
                        + " · " + compact(service.rate / 1000f) + " kHz";
            } else detail = "Paused · " + detail;
        }
        currentDetail.setText(detail);
        long duration = track == null ? 0 : service.duration;
        long position = track == null ? 0 : service.position;
        if (!scrubbing) {
            progress.setProgress(duration <= 0 ? 0 : (int) Math.min(1000, 1000 * position / duration));
            time.setText(formatTime(position) + " / " + formatTime(duration));
        }
        progress.setEnabled(duration > 0);
        play.setEnabled(track != null);
        previous.setEnabled(track != null);
        next.setEnabled(track != null && service.hasNext());
        play.setText(track != null && service.active() ? "Pause" : "Play");
    }

    private void chooseOutput() {
        new AlertDialog.Builder(this).setTitle("Audio output")
                .setSingleChoiceItems(PlaybackService.OUTPUT_POLICIES, PlaybackService.policy(this), (dialog, which) -> {
                    getSharedPreferences("output", MODE_PRIVATE).edit().putInt("policy", which).apply();
                    if (service != null) service.changeOutput();
                    updatePlayer();
                    dialog.dismiss();
                })
                .setNegativeButton("Close", null).show();
    }

    private static String formatTime(long millis) {
        long seconds = millis / 1000;
        if (seconds >= 3600) {
            return String.format(Locale.US, "%d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60);
        }
        return String.format(Locale.US, "%d:%02d", seconds / 60, seconds % 60);
    }

    private LinearLayout column() {
        LinearLayout view = new LinearLayout(this);
        view.setOrientation(LinearLayout.VERTICAL);
        return view;
    }

    private LinearLayout row() {
        LinearLayout view = new LinearLayout(this);
        view.setGravity(Gravity.CENTER_VERTICAL);
        return view;
    }

    private View rule() {
        View view = new View(this);
        view.setLayoutParams(new LinearLayout.LayoutParams(-1, dp(1)));
        view.setBackgroundColor(LINE);
        return view;
    }

    private TextView text(String title, int size, int color) {
        TextView view = new TextView(this);
        view.setText(title);
        view.setTextSize(size);
        view.setTextColor(color);
        return view;
    }

    private Button button(String title, View.OnClickListener click) {
        Button view = new Button(this);
        view.setText(title);
        view.setTextSize(13);
        view.setAllCaps(false);
        view.setMinWidth(0);
        view.setMinimumWidth(0);
        view.setPadding(dp(10), 0, dp(10), 0);
        view.setTextColor(WHITE);
        view.setBackgroundTintList(ColorStateList.valueOf(SURFACE));
        view.setOnClickListener(click);
        return view;
    }

    private int dp(int pixels) {
        return Math.round(pixels * getResources().getDisplayMetrics().density);
    }

    private final class TrackAdapter extends BaseAdapter {
        @Override public int getCount() { return tracks.size(); }
        @Override public Library.Track getItem(int position) { return tracks.get(position); }
        @Override public long getItemId(int position) { return position; }

        @Override public View getView(int position, View old, ViewGroup parent) {
            TrackRow view = old instanceof TrackRow ? (TrackRow) old : new TrackRow();
            Library.Track track = getItem(position);
            boolean selected = track.uri.equals(highlighted);
            view.name.setText(track.name);
            view.name.setTextColor(selected ? ACCENT : WHITE);
            view.detail.setText(track.format + " · " + track.folder);
            view.setBackgroundColor(selected ? SURFACE : BG);
            return view;
        }
    }

    private final class TrackRow extends LinearLayout {
        final TextView name = text("", 15, WHITE);
        final TextView detail = text("", 12, MUTED);

        TrackRow() {
            super(MainActivity.this);
            setOrientation(VERTICAL);
            setGravity(Gravity.CENTER_VERTICAL);
            setMinimumHeight(dp(59));
            setPadding(dp(8), dp(5), dp(8), dp(5));
            name.setMaxLines(2);
            name.setEllipsize(android.text.TextUtils.TruncateAt.END);
            detail.setMaxLines(1);
            detail.setEllipsize(android.text.TextUtils.TruncateAt.END);
            addView(name);
            addView(detail);
        }
    }
}

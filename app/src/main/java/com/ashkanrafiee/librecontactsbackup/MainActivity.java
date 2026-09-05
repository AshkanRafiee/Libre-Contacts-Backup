package com.ashkanrafiee.librecontactsbackup;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.*;
import android.text.InputType;
import android.text.TextUtils;
import android.util.Log;
import android.view.*;
import android.widget.*;

import com.ashkanrafiee.librecontactsbackup.archive.BackupArchiveReader;
import com.ashkanrafiee.librecontactsbackup.snapshot.AndroidContactsSnapshot;
import com.ashkanrafiee.librecontactsbackup.snapshot.BackupAnalysis;
import com.ashkanrafiee.librecontactsbackup.snapshot.BackupAnalyzer;
import com.ashkanrafiee.librecontactsbackup.snapshot.RestoreCategory;
import com.ashkanrafiee.librecontactsbackup.snapshot.RestoreOptions;
import com.ashkanrafiee.librecontactsbackup.snapshot.RestoreResult;

import java.text.SimpleDateFormat;
import java.util.*;

public class MainActivity extends Activity {
    static final int FOLDER = 10, FILE = 11, MANUAL_CSV = 30, MANUAL_VCF = 31, MANUAL_XLS = 32;
    int mint, background, card, muted;
    TextView status, folderValue, scheduleValue, keepValue, languageValue, restoreStatus;
    Switch encryptionSwitch; Dialog restoreProgress; TextView restoreProgressText; String pendingManualFormat; boolean pendingBackup; boolean pendingScheduleTime; String pendingNotificationActions; boolean compact;
    /** Spacing-only tightening: true on small screens (like {@link #compact}) OR any non-English language. Never used for font sizes or control heights — see {@link #build()}. */
    boolean dense;
    Button backupButton;
    ProgressBar backupProgress;
    boolean backupRunning;
    Uri pendingRestoreUri;

    int dp(float value) { return (int) (value * getResources().getDisplayMetrics().density + .5f); }
    int resColor(int id) { return getResources().getColor(id); }
    int v(int normal, int small) { return compact ? small : normal; }
    int d(int normal, int small) { return dense ? small : normal; }
    int[] windowPixels() { if (Build.VERSION.SDK_INT >= 30) { Rect bounds = getWindowManager().getCurrentWindowMetrics().getBounds(); return new int[]{bounds.width(), bounds.height()}; } android.util.DisplayMetrics metrics = new android.util.DisplayMetrics(); getWindowManager().getDefaultDisplay().getMetrics(metrics); return new int[]{metrics.widthPixels, metrics.heightPixels}; }
    TextView label(String value, float size, int color) {
        TextView view = new TextView(this);
        view.setText(value); view.setTextSize(size); view.setTextColor(color);
        return view;
    }
    GradientDrawable rounded(int color, float radius) {
        GradientDrawable drawable = new GradientDrawable(); drawable.setColor(color); drawable.setCornerRadius(dp(radius)); return drawable;
    }
    GradientDrawable gradient(int[] colors, float radius) {
        GradientDrawable drawable = new GradientDrawable(GradientDrawable.Orientation.TL_BR, colors); drawable.setCornerRadius(dp(radius)); return drawable;
    }
    LinearLayout.LayoutParams margins(int left, int top, int right, int bottom) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
        p.setMargins(dp(left), dp(top), dp(right), dp(bottom)); return p;
    }

    @Override protected void attachBaseContext(Context base) {
        super.attachBaseContext(LocaleHelper.wrap(base));
    }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        mint = resColor(R.color.accent); background = resColor(R.color.bg);
        card = resColor(R.color.panel); muted = resColor(R.color.muted);
        getWindow().setStatusBarColor(background);
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 20);
        build();
        String action = getIntent() != null ? getIntent().getStringExtra("notification_action") : null;
        if (action != null) getIntent().removeExtra("notification_action");
        if (action != null && !action.isEmpty()) triggerNotificationAction(action);
    }

    @Override public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            View content = findViewById(android.R.id.content);
            if (content != null) content.invalidate();
        }
    }
    // MainActivity is exported (required for the launcher intent-filter), so
    // this "notification_action" extra can arrive from any locally-installed
    // app via an explicit Intent, not just this app's own notifications —
    // treat it as untrusted input. split(",", 2) (a positive limit) always
    // returns a length-1-or-more array even for edge cases like a bare ","
    // or "" input, unlike the no-limit split(",") used previously, which
    // collapses an all-delimiter string like "," to a zero-length array and
    // crashed on parts[0].
    void triggerNotificationAction(String actions) {
        if (actions == null || actions.isEmpty()) return;
        String[] parts = actions.split(",", 2);
        String first = parts[0].trim();
        String remaining = parts.length > 1 ? parts[1].trim() : "";
        pendingNotificationActions = remaining.isEmpty() ? null : remaining;
        if ("folder_missing".equals(first) || "folder_revoked".equals(first)) { chooseFolder(); }
        else if ("permission_missing".equals(first)) { requestPermissions(new String[]{Manifest.permission.READ_CONTACTS}, 25); }
    }

    void build() {
        int[] window = windowPixels(); int widthPixels = window[0]; int heightPixels = window[1]; // Responsive breakpoint: narrow/short windows compact; large windows keep the spacious composition.
        compact = widthPixels <= 1080 || heightPixels < 2700;
        // Translated strings routinely run a little longer than their English
        // source, which on an otherwise spacious screen can be just enough to
        // force a scroll English never needs. Shrinking text or control sizes to
        // compensate would make the UI visibly smaller than English, so instead
        // only the whitespace between elements (via d(), below) tightens up for
        // any non-English language — same fonts, same button heights, just a
        // little less air around them.
        boolean nonEnglish = !"en".equals(getResources().getConfiguration().getLocales().get(0).getLanguage());
        dense = compact || nonEnglish;
        LinearLayout root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(v(14, 8)), dp(20), dp(v(14, 8))); root.setBackgroundColor(background);
        root.setOnApplyWindowInsetsListener((view, insets) -> { int top; int bottom; if (Build.VERSION.SDK_INT >= 30) { android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars()); top = bars.top; bottom = bars.bottom; } else { top = insets.getSystemWindowInsetTop(); bottom = insets.getSystemWindowInsetBottom(); } view.setPadding(dp(20), top + dp(v(14, 8)), dp(20), bottom + dp(v(14, 8))); return insets; });
        setContentView(root);
        ScrollView scroll = new ScrollView(this); scroll.setClipToPadding(false); scroll.setFillViewport(true);
        LinearLayout body = new LinearLayout(this); body.setOrientation(LinearLayout.VERTICAL); scroll.addView(body, new ScrollView.LayoutParams(-1, -1));
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));

        LinearLayout header = new LinearLayout(this); header.setGravity(Gravity.CENTER_VERTICAL);
        TextView mark = label("L", compact ? 16 : 18, resColor(R.color.accent_ink)); mark.setGravity(Gravity.CENTER); mark.setTypeface(null, android.graphics.Typeface.BOLD); mark.setBackground(rounded(mint, 12)); header.addView(mark, new LinearLayout.LayoutParams(dp(v(38, 34)), dp(v(38, 34))));
        LinearLayout name = new LinearLayout(this); name.setOrientation(LinearLayout.VERTICAL);
        name.addView(label(getString(R.string.app_name), compact ? 20 : 22, resColor(R.color.text_primary))); name.addView(label(getString(R.string.tagline_offline_encrypted), 12, muted));
        LinearLayout.LayoutParams nameParams = new LinearLayout.LayoutParams(-2, -2); nameParams.setMarginStart(dp(10)); header.addView(name, nameParams); body.addView(header, margins(0, 0, 0, v(16, 10)));

        LinearLayout hero = new LinearLayout(this); hero.setOrientation(LinearLayout.VERTICAL); hero.setPadding(dp(v(18, 14)), dp(d(17, 14)), dp(v(18, 14)), dp(d(16, 14)));
        hero.setBackground(gradient(new int[]{resColor(R.color.hero_grad_start), resColor(R.color.hero_grad_end)}, 22));
        FrameLayout heroTop = new FrameLayout(this);
        LinearLayout heroWords = new LinearLayout(this); heroWords.setOrientation(LinearLayout.VERTICAL);
        heroWords.addView(label(getString(R.string.hero_label), 10, mint));
        status = label(getString(R.string.status_ready), 18, resColor(R.color.text_primary)); status.setMaxLines(1); status.setEllipsize(TextUtils.TruncateAt.END); status.setPadding(0, dp(5), 0, dp(2)); heroWords.addView(status);
        backupProgress = new ProgressBar(this);
        backupProgress.setIndeterminate(true);
        backupProgress.setVisibility(View.GONE);
        LinearLayout.LayoutParams progressParams = new LinearLayout.LayoutParams(dp(20), dp(20));
        progressParams.setMargins(0, dp(4), 0, 0);
        heroWords.addView(backupProgress, progressParams);
        heroWords.addView(label(compact ? getString(R.string.hero_tagline_compact) : getString(R.string.hero_tagline_full), 12, resColor(R.color.subtitle)));
        int widthDp = (int) (getResources().getDisplayMetrics().widthPixels / getResources().getDisplayMetrics().density);
        int orbitDp = compact ? Math.max(72, Math.min(82, (int) (widthDp * .23f))) : Math.max(88, Math.min(132, (int) (widthDp * .28f)));
        FrameLayout.LayoutParams wordsParams = new FrameLayout.LayoutParams(-1, -2); wordsParams.setMarginEnd(dp(orbitDp + 12)); heroTop.addView(heroWords, wordsParams);
        heroTop.addView(new ContactOrbit(this), new FrameLayout.LayoutParams(dp(orbitDp), dp(orbitDp), Gravity.END | Gravity.TOP)); hero.addView(heroTop);
        Button backup = button(getString(R.string.backup_now), mint); backup.setTextColor(resColor(R.color.accent_ink)); backup.setOnClickListener(v -> backup());
        backupButton = backup;
        LinearLayout.LayoutParams actionParams = new LinearLayout.LayoutParams(-1, dp(v(48, 44))); actionParams.setMargins(0, dp(v(15, 10)), 0, 0); hero.addView(backup, actionParams); body.addView(hero, margins(0, 0, 0, d(22, 18)));

        body.addView(label(getString(R.string.section_pocket), 10, muted), margins(0, 0, 0, v(8, 5)));
        folderValue = label(BackupManager.folderLabel(this), 13, resColor(R.color.link));
        body.addView(setting(getString(R.string.setting_folder_title), getString(R.string.setting_folder_subtitle), folderValue, false, v -> chooseFolder()), margins(0, 0, 0, v(8, 5)));
        scheduleValue = label(AlarmScheduler.displayLabel(this), 13, resColor(R.color.link));
        body.addView(setting(getString(R.string.setting_schedule_title), getString(R.string.setting_schedule_subtitle), scheduleValue, false, v -> scheduleDialog()), margins(0, 0, 0, v(8, 5)));
        keepValue = label(keepLabel(this, BackupManager.prefs(this).getInt("keep", 5)), 13, resColor(R.color.link));
        body.addView(setting(getString(R.string.setting_keep_title), getString(R.string.setting_keep_subtitle), keepValue, false, v -> retentionDialog()), margins(0, 0, 0, v(8, 5)));
        body.addView(setting(getString(R.string.setting_encryption_title), getString(R.string.setting_encryption_subtitle), null, true, v -> {}), margins(0, 0, 0, d(20, 16)));

        body.addView(label(getString(R.string.section_export), 10, muted), margins(0, 0, 0, 3));
        body.addView(label(compact ? getString(R.string.export_note_compact) : getString(R.string.export_note_full), 11, resColor(R.color.amber)), margins(0, 0, 0, v(8, 5)));
        LinearLayout exports = new LinearLayout(this); exports.setOrientation(LinearLayout.HORIZONTAL);
        Button csv = button(getString(R.string.export_csv), resColor(R.color.button_surface)); Button vcf = button(getString(R.string.export_vcf), resColor(R.color.button_surface)); Button xls = button(getString(R.string.export_excel), resColor(R.color.button_surface));
        csv.setOnClickListener(v -> manualExport("csv")); vcf.setOnClickListener(v -> manualExport("vcf")); xls.setOnClickListener(v -> manualExport("xls"));
        exports.addView(csv, new LinearLayout.LayoutParams(0, dp(v(48, 42)), 1)); LinearLayout.LayoutParams exportGap = new LinearLayout.LayoutParams(0, dp(v(48, 42)), 1); exportGap.setMargins(dp(v(8, 5)), 0, 0, 0); exports.addView(vcf, exportGap); LinearLayout.LayoutParams excelGap = new LinearLayout.LayoutParams(0, dp(v(48, 42)), 1); excelGap.setMargins(dp(v(8, 5)), 0, 0, 0); exports.addView(xls, excelGap); body.addView(exports, margins(0, 0, 0, d(20, 16)));

        body.addView(label(getString(R.string.section_restore), 10, muted), margins(0, 0, 0, v(8, 5)));
        Button restore = button(getString(R.string.restore_button), resColor(R.color.button_surface)); restore.setTextColor(resColor(R.color.button_text)); restore.setOnClickListener(v -> chooseFile());
        LinearLayout.LayoutParams restoreParams = new LinearLayout.LayoutParams(-1, dp(v(48, 42))); restoreParams.setMargins(0, 0, 0, dp(v(12, 7))); body.addView(restore, restoreParams);
        restoreStatus = label(getString(R.string.restore_status_none), 11, resColor(R.color.text_tertiary)); restoreStatus.setGravity(Gravity.CENTER); if (compact) restoreStatus.setVisibility(View.GONE); body.addView(restoreStatus, margins(0, 0, 0, 0));
        LinearLayout footer = new LinearLayout(this); footer.setGravity(Gravity.CENTER);
        TextView footerText = label(compact ? getString(R.string.footer_compact) : getString(R.string.footer_full), 11, resColor(R.color.text_tertiary));
        TextView about = label(getString(R.string.footer_about), 11, resColor(R.color.link)); about.setPaintFlags(about.getPaintFlags() | android.graphics.Paint.UNDERLINE_TEXT_FLAG); about.setOnClickListener(v -> startActivity(new Intent(this, AboutActivity.class)));
        TextView footerSeparator = label("  ·  ", 11, resColor(R.color.text_tertiary));
        languageValue = label(getString(R.string.footer_language), 11, resColor(R.color.link));
        languageValue.setPaintFlags(languageValue.getPaintFlags() | android.graphics.Paint.UNDERLINE_TEXT_FLAG);
        languageValue.setOnClickListener(v -> languageDialog());
        footer.addView(footerText); footer.addView(about); footer.addView(footerSeparator); footer.addView(languageValue);
        body.addView(footer, margins(0, d(18, 14), 0, 0));
        Space breathingRoom = new Space(this); body.addView(breathingRoom, new LinearLayout.LayoutParams(1, 0, 1)); load();
    }

    LinearLayout setting(String title, String subtitle, TextView value, boolean toggle, View.OnClickListener click) {
        LinearLayout box = new LinearLayout(this); box.setGravity(Gravity.CENTER_VERTICAL); box.setPadding(dp(14), dp(d(12, 10)), dp(10), dp(d(12, 10)));
        box.setBackground(rounded(card, 15));
        LinearLayout words = new LinearLayout(this); words.setOrientation(LinearLayout.VERTICAL);
        words.addView(label(title, compact ? 14 : 15, resColor(R.color.text_primary))); words.addView(label(subtitle, compact ? 10 : 11, muted));
        LinearLayout.LayoutParams wordParams = new LinearLayout.LayoutParams(0, -2, 1); wordParams.setMarginEnd(dp(8)); box.addView(words, wordParams);
        if (toggle) {
            Switch sw = new Switch(this); encryptionSwitch = sw; sw.setChecked(BackupManager.prefs(this).getBoolean("encrypted", false));
            sw.setOnCheckedChangeListener((button, checked) -> configureEncryption(checked)); box.addView(sw);
        } else {
            value.setGravity(Gravity.END | Gravity.CENTER_VERTICAL); value.setMaxLines(1); value.setEllipsize(TextUtils.TruncateAt.END); value.setPadding(0, 0, dp(8), 0);
            int widthDp = (int) (getResources().getDisplayMetrics().widthPixels / getResources().getDisplayMetrics().density);
            int valueWidth = widthDp < 360 ? 84 : widthDp < 420 ? 96 : 110;
            boolean rtl = getResources().getConfiguration().getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;
            box.addView(value, new LinearLayout.LayoutParams(dp(valueWidth), dp(v(42, 36)))); TextView arrow = label(rtl ? "‹" : "›", 24, muted); arrow.setGravity(Gravity.CENTER); arrow.setPadding(dp(6), 0, 0, 0);
            box.addView(arrow, new LinearLayout.LayoutParams(dp(28), dp(v(42, 36)))); box.setOnClickListener(click);
        }
        return box;
    }

    Button button(String value, int color) { Button b = new Button(this); b.setText(value); b.setTextColor(resColor(R.color.text_primary)); b.setTextSize(14); b.setAllCaps(false); b.setMinHeight(0); b.setMinimumHeight(0); b.setPadding(0, 0, 0, 0); b.setBackground(rounded(color, 14)); return b; }

    void load() {
        folderValue.setText(BackupManager.folderLabel(this));
        long last = BackupManager.prefs(this).getLong("last", 0);
        if (last > 0) status.setText(getString(R.string.status_last_backup, new SimpleDateFormat("MMM d, h:mm a", Locale.getDefault()).format(new Date(last))));
        scheduleValue.setText(AlarmScheduler.displayLabel(this));
        int keep = BackupManager.prefs(this).getInt("keep", 5); keepValue.setText(keepLabel(this, keep));
        long restored = BackupManager.prefs(this).getLong("lastRestore", 0); int restoredCount = BackupManager.prefs(this).getInt("lastRestoreCount", 0);
        if (restored > 0) {
            restoreStatus.setVisibility(View.VISIBLE);
            String contactsPart = getResources().getQuantityString(R.plurals.contacts_count, restoredCount, restoredCount);
            restoreStatus.setText(getString(R.string.restore_status_done, contactsPart, new SimpleDateFormat("MMM d, h:mm a", Locale.getDefault()).format(new Date(restored))));
        } else if (compact) restoreStatus.setVisibility(View.GONE);
    }
    void chooseFolder() { startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION), FOLDER); }
    void chooseFile() { startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("application/octet-stream").addCategory(Intent.CATEGORY_OPENABLE).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), FILE); }
    void manualExport(String format) { if (checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) { pendingManualFormat = format; requestPermissions(new String[]{Manifest.permission.READ_CONTACTS}, 23); return; } launchManualExport(format); }
    void launchManualExport(String format) { int request = format.equals("csv") ? MANUAL_CSV : format.equals("vcf") ? MANUAL_VCF : MANUAL_XLS; String extension = format.equals("csv") ? ".csv" : format.equals("vcf") ? ".vcf" : ".xls"; String mime = format.equals("csv") ? "text/csv" : format.equals("vcf") ? "text/x-vcard" : "application/vnd.ms-excel"; String name = "manual_librecontacts_" + new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(new Date()) + extension; startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT).setType(mime).putExtra(Intent.EXTRA_TITLE, name).addCategory(Intent.CATEGORY_OPENABLE), request); }
    void backup() {
        if (BackupManager.folder(this).isEmpty()) { pendingBackup = true; chooseFolder(); return; }
        if (checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) { pendingBackup = true; requestPermissions(new String[]{Manifest.permission.READ_CONTACTS}, 21); return; }
        if (backupRunning) return;
        backupRunning = true;
        backupButton.setEnabled(false);
        backupProgress.setVisibility(View.VISIBLE);
        status.setText(getString(R.string.status_backing_up));
        new Thread(() -> { BackupManager.BackupOutcome result = BackupManager.runBackup(this, true); runOnUiThread(() -> { status.setText(result.message); backupButton.setEnabled(true); backupProgress.setVisibility(View.GONE); backupRunning = false; }); }).start();
    }
    void scheduleDialog() {
        new AlertDialog.Builder(this).setTitle(getString(R.string.dialog_schedule_title)).setItems(new String[]{getString(R.string.schedule_option_off), getString(R.string.schedule_option_daily)}, (dialog, which) -> {
            if (which == 0) { AlarmScheduler.setEnabled(this, false); scheduleValue.setText(getString(R.string.schedule_off)); AlarmScheduler.scheduleNext(this); return; }
            if (BackupManager.folder(this).isEmpty()) { pendingScheduleTime = true; chooseFolder(); return; }
            if (checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) { pendingScheduleTime = true; requestPermissions(new String[]{Manifest.permission.READ_CONTACTS}, 24); return; }
            pickScheduleTime();
        }).show();
    }
    void pickScheduleTime() {
        new TimePickerDialog(this, (view, hour, minute) -> { String s = AlarmScheduler.dailyLabel(this, hour, minute); AlarmScheduler.setAtTime(this, hour, minute); scheduleValue.setText(s); }, 9, 0, true).show();
    }
    void retentionDialog() {
        final int[] values = {1, 3, 5, 10, 9999};
        final String[] options = new String[values.length];
        for (int i = 0; i < values.length - 1; i++) options[i] = keepLabel(this, values[i]);
        options[values.length - 1] = getString(R.string.keep_option_all);
        new AlertDialog.Builder(this).setTitle(getString(R.string.dialog_keep_title)).setItems(options, (dialog, which) -> { int keep = values[which]; BackupManager.prefs(this).edit().putInt("keep", keep).apply(); keepValue.setText(keepLabel(this, keep)); }).show();
    }
    void languageDialog() {
        String[] tags = LocaleHelper.SUPPORTED;
        String[] labels = new String[tags.length];
        for (int i = 0; i < tags.length; i++) labels[i] = tags[i].isEmpty() ? getString(R.string.language_system_default) : LocaleHelper.displayName(tags[i]);
        String current = LocaleHelper.currentTag(this);
        int checkedIndex = 0;
        for (int i = 0; i < tags.length; i++) if (tags[i].equals(current)) { checkedIndex = i; break; }
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle(getString(R.string.dialog_language_title)).setSingleChoiceItems(labels, checkedIndex, (dialogInterface, which) -> {
            LocaleHelper.setLanguage(this, tags[which]);
            dialogInterface.dismiss();
            recreate();
        }).create();
        dialog.show();
    }
    @SuppressLint("WrongConstant") // data.getFlags() is masked to exactly the two accepted persistable flags below
    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data); if (result != RESULT_OK || data == null) { if (request == FOLDER) { pendingBackup = false; pendingScheduleTime = false; pendingNotificationActions = null; } return; } Uri uri = data.getData();
        try { if (request == MANUAL_CSV || request == MANUAL_VCF || request == MANUAL_XLS) { String format = request == MANUAL_CSV ? "csv" : request == MANUAL_VCF ? "vcf" : "xls"; new Thread(() -> { try { BackupManager.writeManualExport(this, uri, format); } catch (Exception e) { notice(this, getString(R.string.notice_export_failed_title), e.getMessage()); } }).start(); }
            else if (request == FOLDER) { getContentResolver().takePersistableUriPermission(uri, data.getFlags() & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION)); BackupManager.prefs(this).edit().putString("folder", uri.toString()).apply(); load(); if (pendingBackup) { pendingBackup = false; backup(); } else if (pendingScheduleTime) { pendingScheduleTime = false; if (checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) { pendingScheduleTime = true; requestPermissions(new String[]{Manifest.permission.READ_CONTACTS}, 24); } else pickScheduleTime(); } else if (pendingNotificationActions != null) { String remaining = pendingNotificationActions; pendingNotificationActions = null; triggerNotificationAction(remaining); } }
            else {
                // Restore needs READ_CONTACTS too, not just WRITE_CONTACTS: it queries
                // existing RawContacts (to detect and split platform-auto-merged
                // contacts) and existing Groups (to match/create target groups).
                // A user who goes straight to Restore without ever running a Backup
                // first would otherwise only ever be asked for WRITE_CONTACTS, and
                // those internal queries would silently fail for lack of READ_CONTACTS.
                boolean hasWrite = checkSelfPermission(Manifest.permission.WRITE_CONTACTS) == PackageManager.PERMISSION_GRANTED;
                boolean hasRead = checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED;
                if (!hasWrite || !hasRead) {
                    pendingRestoreUri = uri;
                    requestPermissions(new String[]{Manifest.permission.READ_CONTACTS, Manifest.permission.WRITE_CONTACTS}, 22);
                    return;
                }
                restoreSelected(uri);
            }
        } catch (Exception e) { notice(this, getString(R.string.notice_open_failed_title), e.getMessage()); }
    }
    @Override public void onRequestPermissionsResult(int request, String[] permissions, int[] results) { super.onRequestPermissionsResult(request, permissions, results); if (request == 21) { boolean granted = results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED; boolean resume = pendingBackup; pendingBackup = false; if (granted && resume) backup(); } else if (request == 22) { boolean granted = results.length > 0; for (int r : results) if (r != PackageManager.PERMISSION_GRANTED) granted = false; Uri uri = pendingRestoreUri; pendingRestoreUri = null; if (granted && uri != null) restoreSelected(uri); } else if (request == 23 && results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED && pendingManualFormat != null) { String format = pendingManualFormat; pendingManualFormat = null; launchManualExport(format); } else if (request == 24) { boolean granted = results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED; boolean resume = pendingScheduleTime; pendingScheduleTime = false; if (granted && resume) pickScheduleTime(); } else if (request == 25) { if (pendingNotificationActions != null) { String remaining = pendingNotificationActions; pendingNotificationActions = null; triggerNotificationAction(remaining); } } }
    void configureEncryption(boolean enabled) {
        if (!enabled) { BackupManager.prefs(this).edit().putBoolean("encrypted", false).apply(); return; }
        // "encrypted" is only persisted once a password is actually saved (inside the
        // success callback below) — not eagerly here. Setting it up front meant that
        // canceling the password dialog left the app believing backups should be
        // encrypted with no password actually saved, so every backup would then fail
        // with "Set an encryption password first" until the switch was toggled again.
        passwordDialog(getString(R.string.password_set_title), true, password -> { try { BackupManager.saveEncryptionPassword(this, password); BackupManager.prefs(this).edit().putBoolean("encrypted", true).apply(); } catch (Exception e) { encryptionSwitch.setChecked(false); notice(this, getString(R.string.notice_encryption_unavailable_title), e.getMessage()); } });
    }
    interface PasswordAction { void run(String password); }
    void passwordDialog(String title, boolean confirm, PasswordAction action) {
        LinearLayout form = new LinearLayout(this); form.setOrientation(LinearLayout.VERTICAL); form.setPadding(dp(24), dp(8), dp(24), 0);
        EditText first = new EditText(this); first.setHint(getString(R.string.password_hint)); first.setSingleLine(true); first.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD); form.addView(first, new LinearLayout.LayoutParams(-1, dp(54)));
        EditText second = null; if (confirm) { second = new EditText(this); second.setHint(getString(R.string.password_repeat_hint)); second.setSingleLine(true); second.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD); form.addView(second, new LinearLayout.LayoutParams(-1, dp(54))); }
        final EditText repeated = second;
        // Reverting the switch must cover BOTH cancel paths: tapping the "Cancel"
        // button only dismisses the dialog (Dialog.dismiss(), not cancel()), so
        // OnCancelListener alone never fires for it — only for back-press/outside-touch.
        // Relying on just one of the two left the switch visibly ON with no password
        // ever saved whenever the user tapped Cancel explicitly.
        Runnable revertSwitchIfConfirm = () -> { if (confirm) encryptionSwitch.setChecked(false); };
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle(title).setMessage(confirm ? getString(R.string.password_message_set) : getString(R.string.password_message_unlock)).setView(form)
                .setNegativeButton(getString(R.string.action_cancel), (dialogInterface, which) -> revertSwitchIfConfirm.run())
                .setPositiveButton(confirm ? getString(R.string.action_enable) : getString(R.string.action_restore), null).create();
        dialog.setOnCancelListener(ignored -> revertSwitchIfConfirm.run());
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> { String password = first.getText().toString(); if (password.length() < 8 || (repeated != null && !password.equals(repeated.getText().toString()))) { first.setError(repeated == null ? getString(R.string.password_error_length) : getString(R.string.password_error_mismatch)); return; } dialog.dismiss(); action.run(password); })); dialog.show();
    }
    void restoreSelected(Uri uri) {
        showRestoreProgress(getString(R.string.restore_progress_checking)); new Thread(() -> { try { boolean encrypted = BackupManager.isEncrypted(this, uri); runOnUiThread(() -> { hideRestoreProgress(); if (encrypted) passwordDialog(getString(R.string.password_unlock_title), false, password -> restoreWithPassword(uri, password)); else restoreWithPassword(uri, null); }); } catch (Exception e) { hideRestoreProgress(); notice(this, getString(R.string.notice_restore_failed_title), e.getMessage()); } }).start();
    }
    void restoreWithPassword(Uri uri, String password) {
        showRestoreProgress(getString(R.string.restore_progress_preparing));
        new Thread(() -> {
            try {
                BackupArchiveReader.ArchiveData archiveData = BackupManager.openArchive(this, uri, password, (message, current, total) -> {
                    runOnUiThread(() -> { if (restoreProgressText != null) restoreProgressText.setText(message); });
                });
                AndroidContactsSnapshot snapshot = BackupManager.resolveSnapshot(archiveData);
                BackupAnalysis analysis = BackupAnalyzer.analyze(snapshot);
                runOnUiThread(() -> { hideRestoreProgress(); showRestoreSelectionDialog(snapshot, analysis); });
            } catch (Exception e) {
                // GCM's own authentication check is exactly what fails when
                // the wrong password derives the wrong key — a real crypto
                // signal, not a guess. Anything else (corrupt archive, I/O
                // error, oversized entry, etc.) gets its real message
                // instead of always blaming the password.
                String message = (e instanceof javax.crypto.AEADBadTagException
                        || e instanceof javax.crypto.BadPaddingException
                        || e instanceof SecurityException)
                        ? getString(R.string.error_wrong_password)
                        : (e.getMessage() != null ? e.getMessage() : getString(R.string.error_wrong_password));
                runOnUiThread(() -> { hideRestoreProgress(); notice(this, getString(R.string.notice_restore_failed_title), message); });
            }
        }).start();
    }

    static String keepLabel(Context c, int keep) {
        return keep > 100 ? c.getString(R.string.keep_all_label) : c.getResources().getQuantityString(R.plurals.backup_sets_count, keep, keep);
    }

    /** Plain-language "N things" label for a category's item count, e.g. "12 data fields". */
    static String categoryCountLabel(Context c, RestoreCategory category, int count) {
        android.content.res.Resources r = c.getResources();
        switch (category) {
            case CONTACT_INFO: return r.getQuantityString(R.plurals.data_fields_count, count, count);
            case PHOTOS: return r.getQuantityString(R.plurals.photos_count, count, count);
            case GROUPS: return r.getQuantityString(R.plurals.groups_count, count, count);
            case ADDITIONAL_DATA: return r.getQuantityString(R.plurals.fields_count, count, count);
            case ACCOUNT_INFO: return r.getQuantityString(R.plurals.account_contacts_count, count, count);
            default: return String.valueOf(count);
        }
    }

    /**
     * Shows what the backup contains and lets the user choose which
     * categories to materialize into the Contacts Provider (spec sections
     * 6–9). Only the recommended categories are pre-selected; the rest can
     * be added by the user. Nothing is restored until "Restore" is tapped;
     * "Cancel" leaves the Contacts Provider untouched. The backup itself is
     * never modified regardless of the choice made here. Tapping anywhere
     * in a row — including its text — toggles that row's checkbox.
     */
    void showRestoreSelectionDialog(AndroidContactsSnapshot snapshot, BackupAnalysis analysis) {
        LinearLayout panel = new LinearLayout(this); panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(22), dp(20), dp(22), dp(16));
        panel.setBackground(rounded(card, 22));

        panel.addView(label(getString(R.string.restore_dialog_title), 18, resColor(R.color.text_primary)), margins(0, 0, 0, 4));

        String contactsPart = getResources().getQuantityString(R.plurals.contacts_count, analysis.contactCount, analysis.contactCount);
        String fieldsPart = getResources().getQuantityString(R.plurals.data_fields_count, analysis.dataRowCount, analysis.dataRowCount);
        String summary = getString(R.string.restore_dialog_summary, contactsPart, fieldsPart);
        panel.addView(label(summary, 12, muted), margins(0, 0, 0, 14));

        ScrollView scroll = new ScrollView(this);
        // Keep the scrollbar always visible (not just while actively
        // scrolling) so it's obvious there are more categories below —
        // the checkbox list can exceed the dialog's visible height.
        scroll.setScrollbarFadingEnabled(false);
        scroll.setVerticalScrollBarEnabled(true);
        LinearLayout content = new LinearLayout(this); content.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(content, new ScrollView.LayoutParams(-1, -2));

        LinkedHashMap<RestoreCategory, CheckBox> boxes = new LinkedHashMap<>();
        for (RestoreCategory category : RestoreCategory.values()) {
            LinearLayout row = new LinearLayout(this); row.setOrientation(LinearLayout.HORIZONTAL); row.setGravity(Gravity.TOP);
            row.setPadding(0, dp(10), 0, dp(10));

            CheckBox box = new CheckBox(this);
            box.setChecked(category.recommended);
            boxes.put(category, box);
            LinearLayout.LayoutParams boxParams = new LinearLayout.LayoutParams(-2, -2);
            boxParams.topMargin = dp(2);
            row.addView(box, boxParams);

            LinearLayout words = new LinearLayout(this); words.setOrientation(LinearLayout.VERTICAL);
            String count = categoryCountLabel(this, category, analysis.countFor(category));
            words.addView(label(getString(category.titleRes) + "  ·  " + count, 14, resColor(R.color.text_primary)));
            words.addView(label(getString(category.descriptionRes) + " " + getString(category.exampleRes), 11, muted));
            if (!category.recommended) {
                words.addView(label(getString(R.string.restore_not_recommended, getString(category.notRecommendedReasonRes)), 11, resColor(R.color.amber)));
            }
            LinearLayout.LayoutParams wordParams = new LinearLayout.LayoutParams(0, -2, 1);
            wordParams.setMarginStart(dp(10));
            row.addView(words, wordParams);

            row.setOnClickListener(v -> box.toggle());
            content.addView(row);
        }
        panel.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));

        LinearLayout buttons = new LinearLayout(this); buttons.setOrientation(LinearLayout.HORIZONTAL);
        Button cancel = button(getString(R.string.action_cancel), resColor(R.color.button_surface)); cancel.setTextColor(resColor(R.color.button_text));
        Button restore = button(getString(R.string.action_restore), mint); restore.setTextColor(resColor(R.color.accent_ink));
        buttons.addView(cancel, new LinearLayout.LayoutParams(0, dp(46), 1));
        LinearLayout.LayoutParams restoreParams = new LinearLayout.LayoutParams(0, dp(46), 1); restoreParams.setMarginStart(dp(10));
        buttons.addView(restore, restoreParams);
        panel.addView(buttons, margins(0, 14, 0, 0));

        Dialog dialog = new Dialog(this);
        dialog.setContentView(panel);
        dialog.setCancelable(true);
        Window window = dialog.getWindow();
        if (window != null) { window.setBackgroundDrawableResource(android.R.color.transparent); window.setLayout(dp(320), dp(480)); }

        cancel.setOnClickListener(v -> dialog.dismiss());
        restore.setOnClickListener(v -> {
            LinkedHashSet<RestoreCategory> selected = new LinkedHashSet<>();
            for (Map.Entry<RestoreCategory, CheckBox> entry : boxes.entrySet()) {
                if (entry.getValue().isChecked()) selected.add(entry.getKey());
            }
            dialog.dismiss();
            performRestore(snapshot, RestoreOptions.of(selected));
        });
        dialog.show();
    }

    void performRestore(AndroidContactsSnapshot snapshot, RestoreOptions options) {
        showRestoreProgress(getString(R.string.restore_progress_restoring_contacts));
        new Thread(() -> {
            try {
                RestoreResult result = BackupManager.restoreWithOptions(this, snapshot, options, (message, current, total) -> {
                    runOnUiThread(() -> { if (restoreProgressText != null) restoreProgressText.setText(message); });
                });
                BackupManager.prefs(this).edit().putLong("lastRestore", System.currentTimeMillis())
                    .putInt("lastRestoreCount", result.contactsCreated).apply();
                final String title = result.hasErrors() ? getString(R.string.notice_restore_completed_issues_title)
                        : result.hasWarnings() ? getString(R.string.notice_restore_completed_warnings_title) : getString(R.string.notice_restore_complete_title);
                final String briefMsg = result.briefSummary(this);
                runOnUiThread(() -> { hideRestoreProgress(); load(); notice(this, title, briefMsg); });
            } catch (Exception e) {
                runOnUiThread(() -> { hideRestoreProgress(); notice(this, getString(R.string.notice_restore_failed_title), e.getMessage()); });
            }
        }).start();
    }
    void showRestoreProgress(String message) {
        if (restoreProgress != null && restoreProgress.isShowing()) { restoreProgressText.setText(message); return; }
        LinearLayout panel = new LinearLayout(this); panel.setOrientation(LinearLayout.VERTICAL); panel.setGravity(Gravity.CENTER_HORIZONTAL); panel.setPadding(dp(28), dp(26), dp(28), dp(26)); panel.setBackground(rounded(card, 22));
        ProgressBar spinner = new ProgressBar(this); panel.addView(spinner, new LinearLayout.LayoutParams(dp(46), dp(46)));
        TextView title = label(getString(R.string.restore_progress_restoring_contacts), 18, resColor(R.color.text_primary)); title.setPadding(0, dp(16), 0, dp(5)); panel.addView(title);
        restoreProgressText = label(message, 13, muted); restoreProgressText.setGravity(Gravity.CENTER); panel.addView(restoreProgressText);
        TextView note = label(getString(R.string.restore_progress_note), 11, resColor(R.color.text_tertiary)); note.setPadding(0, dp(14), 0, 0); panel.addView(note);
        restoreProgress = new Dialog(this); restoreProgress.setContentView(panel); restoreProgress.setCancelable(false); Window window = restoreProgress.getWindow(); if (window != null) { window.setBackgroundDrawableResource(android.R.color.transparent); window.setLayout(dp(300), -2); } restoreProgress.show(); if (restoreProgress.getWindow() != null) restoreProgress.getWindow().setLayout(dp(300), -2);
    }
    void hideRestoreProgress() { runOnUiThread(() -> { if (restoreProgress != null && restoreProgress.isShowing()) restoreProgress.dismiss(); restoreProgress = null; restoreProgressText = null; }); }
    public static void notice(Context context, String title, String body) { new Handler(Looper.getMainLooper()).post(() -> Toast.makeText(context, title + ": " + body, Toast.LENGTH_LONG).show()); }
    public static void showScheduledNotification(Context context, String result, boolean success, String reason) {
        try {
            final String channelId = "scheduled_backups";
            NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            if (Build.VERSION.SDK_INT >= 26) manager.createNotificationChannel(new NotificationChannel(channelId, context.getString(R.string.notification_channel_scheduled_backups), NotificationManager.IMPORTANCE_DEFAULT));
            Intent open = new Intent(context, MainActivity.class);
            if (reason != null) open.putExtra("notification_action", reason);
            PendingIntent pending = PendingIntent.getActivity(context, 91, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            String title = success ? context.getString(R.string.notification_backup_complete_title) : context.getString(R.string.notification_backup_needs_attention_title);
            String message = success ? result : (result == null ? context.getString(R.string.notification_backup_not_completed) : result);
            Notification.Builder builder = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(context, channelId) : new Notification.Builder(context);
            builder.setSmallIcon(R.drawable.ic_launcher).setContentTitle(title).setContentText(message).setAutoCancel(true).setContentIntent(pending).setCategory(Notification.CATEGORY_STATUS);
            manager.notify(91, builder.build());
        } catch (Exception error) { Log.e("LibreContactsBackup", "Unable to show scheduled backup notification", error); }
    }
    public static void showScheduledNotification(Context context, String result, boolean success) { showScheduledNotification(context, result, success, null); }

    static final class ContactOrbit extends View {
        final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        ContactOrbit(Context context) { super(context); paint.setStrokeCap(Paint.Cap.ROUND); setContentDescription(context.getString(R.string.contact_orbit_content_description)); }
        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas); float cx = getWidth() * .54f, cy = getHeight() * .50f;
            int orbitAccent = getContext().getResources().getColor(R.color.accent);
            int orbitIndigo = getContext().getResources().getColor(R.color.orbit_indigo);
            int orbitPale = getContext().getResources().getColor(R.color.orbit_pale);
            int orbitAmber = getContext().getResources().getColor(R.color.orbit_amber);
            paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(2); paint.setColor(Color.argb(90, 143, 240, 208));
            canvas.drawCircle(cx, cy, getWidth() * .30f, paint); canvas.drawCircle(cx, cy, getWidth() * .46f, paint);
            paint.setStyle(Paint.Style.FILL); paint.setColor(orbitAccent); canvas.drawCircle(cx, cy, getWidth() * .12f, paint);
            paint.setColor(orbitIndigo); canvas.drawCircle(cx - getWidth() * .39f, cy - getHeight() * .18f, getWidth() * .07f, paint);
            paint.setColor(orbitPale); canvas.drawCircle(cx + getWidth() * .35f, cy + getHeight() * .14f, getWidth() * .055f, paint);
            paint.setColor(orbitAmber); canvas.drawCircle(cx + getWidth() * .04f, cy - getHeight() * .43f, getWidth() * .045f, paint);
        }
    }
}

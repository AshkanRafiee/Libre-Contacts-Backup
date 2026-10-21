package com.ashkanrafiee.librecontactsbackup;

import android.app.*;
import android.content.*;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.*;
import android.widget.*;

public class AboutActivity extends Activity {
    static final String APP_WEBSITE = "https://librecontactsbackup.ashkanrafiee.com/";
    static final String ISSUES_URL = "https://github.com/AshkanRafiee/Libre-Contacts-Backup/issues";
    static final String DONATION_URL = "https://librecontactsbackup.ashkanrafiee.com/#donate";
    static final String CONTACT_EMAIL = "librecontactsbackup.abstract692@passmail.net";
    static final String CONTACT = "mailto:" + CONTACT_EMAIL;
    int background, card, muted, mint, link, textPrimary, subtitle, textTertiary;
    /** Spacing-only tightening: true on small screens OR any non-English language. Never used for font sizes or icon dimensions — see {@link #onCreate}. */
    boolean dense;
    int dp(float value) { return (int) (value * getResources().getDisplayMetrics().density + .5f); }
    int resColor(int id) { return getResources().getColor(id); }
    int d(int normal, int small) { return dense ? small : normal; }
    TextView text(String value, float size, int color) { TextView v = new TextView(this); v.setText(value); v.setTextSize(size); v.setTextColor(color); return v; }
    GradientDrawable rounded(int color, float radius) { GradientDrawable d = new GradientDrawable(); d.setColor(color); d.setCornerRadius(dp(radius)); return d; }
    LinearLayout.LayoutParams margins(int l, int t, int r, int b) { LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2); p.setMargins(dp(l), dp(t), dp(r), dp(b)); return p; }

    @Override protected void attachBaseContext(Context base) {
        super.attachBaseContext(ThemeHelper.wrap(LocaleHelper.wrap(base)));
    }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        background = resColor(R.color.bg); card = resColor(R.color.panel); muted = resColor(R.color.muted);
        mint = resColor(R.color.accent); link = resColor(R.color.link); textPrimary = resColor(R.color.text_primary);
        subtitle = resColor(R.color.subtitle); textTertiary = resColor(R.color.text_tertiary);
        getWindow().setStatusBarColor(background);
        // Translated strings routinely run a little longer than their English
        // source, which can be just enough to force a scroll English never
        // needs. Shrinking text or icon sizes to compensate would make the UI
        // visibly smaller than English, so instead only the whitespace between
        // elements (via d(), below) tightens up — same fonts, same icon size,
        // just a little less air around them. (Small screens get the same
        // treatment, same as MainActivity.build().)
        android.util.DisplayMetrics metrics = getResources().getDisplayMetrics();
        boolean nonEnglish = !"en".equals(getResources().getConfiguration().getLocales().get(0).getLanguage());
        dense = metrics.widthPixels / metrics.density <= 360 || metrics.heightPixels / metrics.density < 900 || nonEnglish;
        LinearLayout root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); root.setPadding(dp(20), dp(14), dp(20), dp(14)); root.setBackgroundColor(background);
        root.setOnApplyWindowInsetsListener((view, insets) -> { int top; int bottom; if (android.os.Build.VERSION.SDK_INT >= 30) { android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars()); top = bars.top; bottom = bars.bottom; } else { top = insets.getSystemWindowInsetTop(); bottom = insets.getSystemWindowInsetBottom(); } view.setPadding(dp(20), top + dp(14), dp(20), bottom + dp(14)); return insets; }); setContentView(root);
        boolean rtl = getResources().getConfiguration().getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;
        LinearLayout bar = new LinearLayout(this); bar.setGravity(Gravity.CENTER_VERTICAL); TextView back = text(rtl ? "›" : "‹", 34, textPrimary); back.setGravity(Gravity.CENTER); back.setOnClickListener(v -> finish()); bar.addView(back, new LinearLayout.LayoutParams(dp(42), dp(48)));
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(-2, -2); titleParams.setMarginStart(dp(10));
        bar.addView(text(getString(R.string.about_title), 21, textPrimary), titleParams); root.addView(bar, margins(0, 0, 0, d(12, 8)));
        ScrollView scroll = new ScrollView(this); scroll.setFillViewport(true); scroll.setVerticalScrollBarEnabled(false); LinearLayout body = new LinearLayout(this); body.setOrientation(LinearLayout.VERTICAL); scroll.addView(body, new ScrollView.LayoutParams(-1, -1)); root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        LinearLayout hero = new LinearLayout(this); hero.setOrientation(LinearLayout.VERTICAL); hero.setGravity(Gravity.CENTER_HORIZONTAL); hero.setPadding(dp(20), dp(d(16, 20)), dp(20), dp(d(16, 20))); hero.setBackground(rounded(resColor(R.color.hero_grad_end), 22)); TextView mark = text("L", 24, resColor(R.color.accent_ink)); mark.setGravity(Gravity.CENTER); mark.setTypeface(null, Typeface.BOLD); mark.setBackground(rounded(mint, 16)); hero.addView(mark, new LinearLayout.LayoutParams(dp(56), dp(56))); TextView title = text(getString(R.string.app_name), 24, textPrimary); title.setPadding(0, dp(14), 0, dp(2)); hero.addView(title); hero.addView(text(getString(R.string.tagline_offline_encrypted), 13, subtitle)); body.addView(hero, margins(0, 0, 0, d(12, 18)));
        body.addView(section(getString(R.string.about_section_heading), getString(R.string.about_section_body)), margins(0, 0, 0, 8));
        body.addView(info(getString(R.string.about_created_by_label), "Ashkan Rafiee", v -> open("https://AshkanRafiee.com")), margins(0, 0, 0, 6));
        body.addView(info(getString(R.string.about_license_label), "GNU General Public License v3.0", v -> open("https://github.com/AshkanRafiee/Libre-Contacts-Backup/blob/main/LICENSE")), margins(0, 0, 0, 6));
        body.addView(info(getString(R.string.about_source_label), "github.com/AshkanRafiee/Libre-Contacts-Backup", v -> open("https://github.com/AshkanRafiee/Libre-Contacts-Backup")), margins(0, 0, 0, 6));
        body.addView(info(getString(R.string.about_website_label), "librecontactsbackup.ashkanrafiee.com", v -> open(APP_WEBSITE)), margins(0, 0, 0, 6));
        body.addView(info(getString(R.string.about_suggestions_label), "github.com/AshkanRafiee/Libre-Contacts-Backup/issues", v -> open(ISSUES_URL)), margins(0, 0, 0, 6));
        body.addView(info(getString(R.string.about_contact_label), CONTACT_EMAIL, v -> open(CONTACT)), margins(0, 0, 0, 6));
        body.addView(info(getString(R.string.about_donate_label), "librecontactsbackup.ashkanrafiee.com/#donate", v -> open(DONATION_URL)), margins(0, 0, 0, 6));
        body.addView(info(getString(R.string.about_privacy_label), getString(R.string.about_privacy_value), null), margins(0, 0, 0, d(12, 16)));
        TextView report = text(getString(R.string.about_report_issue_label), 12, link);
        report.setGravity(Gravity.CENTER); report.setTypeface(null, Typeface.BOLD);
        report.setPaintFlags(report.getPaintFlags() | android.graphics.Paint.UNDERLINE_TEXT_FLAG);
        report.setPadding(0, dp(4), 0, dp(10)); report.setOnClickListener(v -> showReportDialog());
        body.addView(report, new LinearLayout.LayoutParams(-1, -2));
        TextView footer = text(getString(R.string.about_footer, BuildConfig.VERSION_NAME), 11, textTertiary); footer.setGravity(Gravity.CENTER); body.addView(footer);
    }
    TextView section(String heading, String body) { TextView v = text(heading + "\n" + body, 12, muted); v.setLineSpacing(2, 1.05f); return v; }
    LinearLayout info(String heading, String value, View.OnClickListener click) { LinearLayout box = new LinearLayout(this); box.setOrientation(LinearLayout.VERTICAL); box.setPadding(dp(16), dp(d(13, 11)), dp(16), dp(d(13, 11))); box.setBackground(rounded(card, 15)); TextView h = text(heading, 12, muted); box.addView(h); TextView v = text(value, 14, click == null ? textPrimary : link); v.setPadding(0, dp(5), 0, 0); v.setMaxLines(2); v.setEllipsize(TextUtils.TruncateAt.END); if (click != null) { v.setPaintFlags(v.getPaintFlags() | android.graphics.Paint.UNDERLINE_TEXT_FLAG); box.setOnClickListener(click); } box.addView(v); return box; }
    void open(String url) { try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))); } catch (Exception e) { Toast.makeText(this, getString(R.string.about_no_browser), Toast.LENGTH_SHORT).show(); } }
    private void showReportDialog() {
        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.report_issue_title))
                .setMessage(getString(R.string.report_issue_consent_message))
                .setNegativeButton(getString(R.string.action_cancel), null)
                .setNeutralButton(getString(R.string.report_issue_without_diagnostics), (dialog, which) -> shareReport(false))
                .setPositiveButton(getString(R.string.report_issue_with_diagnostics), (dialog, which) -> shareReport(true))
                .show();
    }
    private void shareReport(boolean includeDiagnostics) {
        Intent share = buildShareIntent(includeDiagnostics);
        Intent email = buildEmailIntent(includeDiagnostics);
        try {
            if (share.resolveActivity(getPackageManager()) != null) {
                startActivity(Intent.createChooser(share, getString(R.string.report_issue_share_title)));
                return;
            }
            if (email.resolveActivity(getPackageManager()) != null) {
                startActivity(email);
                return;
            }
        } catch (Exception ignored) {
            try {
                if (email.resolveActivity(getPackageManager()) != null) {
                    startActivity(email);
                    return;
                }
            } catch (Exception ignoredEmail) { }
        }
        Toast.makeText(this, getString(R.string.report_issue_no_app), Toast.LENGTH_LONG).show();
    }
    Intent buildShareIntent(boolean includeDiagnostics) {
        return new Intent(Intent.ACTION_SEND).setType("text/plain")
                .putExtra(Intent.EXTRA_EMAIL, new String[]{CONTACT_EMAIL})
                .putExtra(Intent.EXTRA_SUBJECT, getString(R.string.report_issue_subject))
                .putExtra(Intent.EXTRA_TEXT, reportBody(includeDiagnostics));
    }
    Intent buildEmailIntent(boolean includeDiagnostics) {
        return new Intent(Intent.ACTION_SENDTO, Uri.parse(CONTACT))
                        .putExtra(Intent.EXTRA_SUBJECT, getString(R.string.report_issue_subject))
                        .putExtra(Intent.EXTRA_TEXT, reportBody(includeDiagnostics));
    }
    private String reportBody(boolean includeDiagnostics) {
        String body = getString(R.string.report_issue_body);
        if (includeDiagnostics) body += "\n\n--- Anonymized diagnostics ---\n" + ReportDiagnostics.create(this);
        return body;
    }
}

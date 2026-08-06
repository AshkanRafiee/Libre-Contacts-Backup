package com.ashkanrafiee.librecontactsbackup;

import android.app.*;
import android.content.*;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.*;
import android.widget.*;

public class AboutActivity extends Activity {
    final int background = Color.rgb(10, 14, 24), card = Color.rgb(20, 27, 42), muted = Color.rgb(151, 161, 181), mint = Color.rgb(143, 240, 208), purple = Color.rgb(137, 125, 255);
    int dp(float value) { return (int) (value * getResources().getDisplayMetrics().density + .5f); }
    TextView text(String value, float size, int color) { TextView v = new TextView(this); v.setText(value); v.setTextSize(size); v.setTextColor(color); return v; }
    GradientDrawable rounded(int color, float radius) { GradientDrawable d = new GradientDrawable(); d.setColor(color); d.setCornerRadius(dp(radius)); return d; }
    LinearLayout.LayoutParams margins(int l, int t, int r, int b) { LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2); p.setMargins(dp(l), dp(t), dp(r), dp(b)); return p; }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state); getWindow().setStatusBarColor(background);
        LinearLayout root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); root.setPadding(dp(20), dp(14), dp(20), dp(14)); root.setBackgroundColor(background);
        root.setOnApplyWindowInsetsListener((view, insets) -> { int top; int bottom; if (android.os.Build.VERSION.SDK_INT >= 30) { android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars()); top = bars.top; bottom = bars.bottom; } else { top = insets.getSystemWindowInsetTop(); bottom = insets.getSystemWindowInsetBottom(); } view.setPadding(dp(20), top + dp(14), dp(20), bottom + dp(14)); return insets; }); setContentView(root);
        LinearLayout bar = new LinearLayout(this); bar.setGravity(Gravity.CENTER_VERTICAL); TextView back = text("‹", 34, Color.WHITE); back.setGravity(Gravity.CENTER); back.setOnClickListener(v -> finish()); bar.addView(back, new LinearLayout.LayoutParams(dp(42), dp(48))); bar.addView(text("About", 21, Color.WHITE), margins(10, 0, 0, 0)); root.addView(bar, margins(0, 0, 0, 16));
        ScrollView scroll = new ScrollView(this); scroll.setFillViewport(true); LinearLayout body = new LinearLayout(this); body.setOrientation(LinearLayout.VERTICAL); scroll.addView(body, new ScrollView.LayoutParams(-1, -1)); root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        LinearLayout hero = new LinearLayout(this); hero.setOrientation(LinearLayout.VERTICAL); hero.setGravity(Gravity.CENTER_HORIZONTAL); hero.setPadding(dp(20), dp(24), dp(20), dp(24)); hero.setBackground(rounded(Color.rgb(35, 34, 72), 22)); TextView mark = text("L", 24, background); mark.setGravity(Gravity.CENTER); mark.setTypeface(null, Typeface.BOLD); mark.setBackground(rounded(mint, 16)); hero.addView(mark, new LinearLayout.LayoutParams(dp(56), dp(56))); TextView title = text("Libre Contacts Backup", 24, Color.WHITE); title.setPadding(0, dp(14), 0, dp(2)); hero.addView(title); hero.addView(text("Offline & encrypted", 13, Color.rgb(201, 211, 230))); body.addView(hero, margins(0, 0, 0, 22));
        body.addView(section("ABOUT THIS APP", "A private, local-first contact backup tool. Your scheduled backups stay on the storage location you choose, and encrypted archives are protected with your password."), margins(0, 0, 0, 10));
        body.addView(info("Created by", "Ashkan Rafiee", null), margins(0, 0, 0, 8));
        body.addView(info("License", "GNU General Public License v3.0", v -> open("https://github.com/AshkanRafiee/Libre-Contacts-Backup/blob/main/LICENSE")), margins(0, 0, 0, 8));
        body.addView(info("Source code", "github.com/AshkanRafiee/Libre-Contacts-Backup", v -> open("https://github.com/AshkanRafiee/Libre-Contacts-Backup")), margins(0, 0, 0, 8));
        body.addView(info("GitHub profile", "github.com/AshkanRafiee", v -> open("https://github.com/AshkanRafiee/")), margins(0, 0, 0, 8));
        body.addView(info("Suggestions", "librecontactsbackup.abstract692@passmail.net", v -> email()), margins(0, 0, 0, 20));
        String version = "1.0"; try { version = getPackageManager().getPackageInfo(getPackageName(), 0).versionName; } catch (Exception ignored) { }
        TextView footer = text("Libre Contacts Backup · Version " + version, 11, Color.rgb(103, 115, 136)); footer.setGravity(Gravity.CENTER); body.addView(footer);
    }
    TextView section(String heading, String body) { TextView v = text(heading + "\n" + body, 12, muted); v.setLineSpacing(2, 1.05f); return v; }
    LinearLayout info(String heading, String value, View.OnClickListener click) { LinearLayout box = new LinearLayout(this); box.setOrientation(LinearLayout.VERTICAL); box.setPadding(dp(16), dp(13), dp(16), dp(13)); box.setBackground(rounded(card, 15)); TextView h = text(heading, 12, muted); box.addView(h); TextView v = text(value, 14, click == null ? Color.WHITE : Color.rgb(190, 184, 255)); v.setPadding(0, dp(5), 0, 0); v.setMaxLines(2); v.setEllipsize(TextUtils.TruncateAt.END); if (click != null) { v.setPaintFlags(v.getPaintFlags() | android.graphics.Paint.UNDERLINE_TEXT_FLAG); box.setOnClickListener(click); } box.addView(v); return box; }
    void open(String url) { try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))); } catch (Exception e) { Toast.makeText(this, "No browser available", Toast.LENGTH_SHORT).show(); } }
    void email() { try { startActivity(new Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:librecontactsbackup.abstract692@passmail.net"))); } catch (Exception e) { Toast.makeText(this, "No email app available", Toast.LENGTH_SHORT).show(); } }
}

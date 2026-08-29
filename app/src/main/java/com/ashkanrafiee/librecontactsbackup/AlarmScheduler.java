package com.ashkanrafiee.librecontactsbackup;
import android.app.*;import android.content.*;import android.os.Build;import java.util.*;
public final class AlarmScheduler {
    public static void setAtTime(Context c,int h,int m){
        BackupManager.prefs(c).edit().putBoolean("scheduleEnabled", true).putInt("hour", h).putInt("minute", m).apply();
        install(c,nextRun(h,m).getTimeInMillis());
    }
    public static void setEnabled(Context c, boolean enabled) {
        BackupManager.prefs(c).edit().putBoolean("scheduleEnabled", enabled).apply();
    }
    public static String dailyLabel(Context c,int h,int m){return c.getString(R.string.schedule_daily_at, String.format(Locale.getDefault(),"%02d:%02d",h,m));}
    public static String displayLabel(Context c){
        migrateLegacyPref(c);
        if(!BackupManager.prefs(c).getBoolean("scheduleEnabled", false)) return c.getString(R.string.schedule_off);
        return dailyLabel(c, BackupManager.prefs(c).getInt("hour",9), BackupManager.prefs(c).getInt("minute",0));
    }
    public static void scheduleNext(Context c){
        migrateLegacyPref(c);
        if(!BackupManager.prefs(c).getBoolean("scheduleEnabled", false)){install(c,0);return;}
        install(c,nextRun(BackupManager.prefs(c).getInt("hour",9),BackupManager.prefs(c).getInt("minute",0)).getTimeInMillis());
    }
    // Before localization, "schedule" stored the displayed English text itself
    // ("Off" or "Daily at HH:mm") and doubled as the on/off flag. Displaying a
    // translated label from that same stored string would either show stale
    // English forever or require re-parsing a possibly-translated string back
    // into a flag. Instead, migrate once to a locale-independent boolean (the
    // hour/minute needed to rebuild the label were already stored separately)
    // and drop the legacy key so this only ever runs once per install.
    private static void migrateLegacyPref(Context c) {
        SharedPreferences prefs = BackupManager.prefs(c);
        if (!prefs.contains("schedule")) return;
        boolean enabled = !"Off".equals(prefs.getString("schedule", "Off"));
        prefs.edit().putBoolean("scheduleEnabled", enabled).remove("schedule").apply();
    }
    private static Calendar nextRun(int h,int m){Calendar next=Calendar.getInstance();next.set(Calendar.HOUR_OF_DAY,h);next.set(Calendar.MINUTE,m);next.set(Calendar.SECOND,0);next.set(Calendar.MILLISECOND,0);if(next.getTimeInMillis()<=System.currentTimeMillis())next.add(Calendar.DAY_OF_YEAR,1);return next;}
    private static void install(Context c,long when){
        AlarmManager manager=(AlarmManager)c.getSystemService(Context.ALARM_SERVICE);
        Intent intent=new Intent(c,BackupAlarmReceiver.class);
        PendingIntent pending=PendingIntent.getBroadcast(c,7,intent,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        manager.cancel(pending);
        if(when==0)return;
        if(Build.VERSION.SDK_INT>=23){
            try{manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,when,pending);return;}
            catch(SecurityException ignored){}
            try{manager.setAlarmClock(new AlarmManager.AlarmClockInfo(when,pending),pending);return;}
            catch(Exception ignored){}
            manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,when,pending);
        }else{
            manager.set(AlarmManager.RTC_WAKEUP,when,pending);
        }
    }
}

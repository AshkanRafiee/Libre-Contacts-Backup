package com.ashkanrafiee.librecontactsbackup;
import android.app.*;import android.content.*;import android.os.Build;import java.text.DateFormatSymbols;import java.util.*;
/** Schedules automatic backups at a daily, weekly, or monthly cadence — exactly one
 *  schedule is active at a time. The mode is stored in {@code scheduleMode} ("daily",
 *  "weekly", or "monthly"); a schedule enabled before this feature shipped has no
 *  mode and is treated as daily. A single alarm (request code 7) always points at the
 *  next occurrence of the currently selected schedule. */
public final class AlarmScheduler {
    private static final String MODE = "scheduleMode";
    public static void setDaily(Context c,int h,int m){
        BackupManager.prefs(c).edit().putBoolean("scheduleEnabled", true).putString(MODE,"daily").putInt("hour", h).putInt("minute", m).apply();
        install(c,nextRun(prefs(c)).getTimeInMillis());
    }
    public static void setWeekly(Context c,int weekday,int h,int m){
        BackupManager.prefs(c).edit().putBoolean("scheduleEnabled", true).putString(MODE,"weekly").putInt("weekday", weekday).putInt("hour", h).putInt("minute", m).apply();
        install(c,nextRun(prefs(c)).getTimeInMillis());
    }
    public static void setMonthly(Context c,int dayOfMonth,int h,int m){
        BackupManager.prefs(c).edit().putBoolean("scheduleEnabled", true).putString(MODE,"monthly").putInt("dayOfMonth", dayOfMonth).putInt("hour", h).putInt("minute", m).apply();
        install(c,nextRun(prefs(c)).getTimeInMillis());
    }
    public static void setEnabled(Context c, boolean enabled) {
        BackupManager.prefs(c).edit().putBoolean("scheduleEnabled", enabled).apply();
    }
    public static String displayLabel(Context c){
        migrateLegacyPref(c);
        SharedPreferences prefs = prefs(c);
        if(!prefs.getBoolean("scheduleEnabled", false)) return c.getString(R.string.schedule_off);
        int h=prefs.getInt("hour",9), m=prefs.getInt("minute",0);
        String time = String.format(Locale.getDefault(),"%02d:%02d",h,m);
        switch(prefs.getString(MODE,"daily")){
            case "weekly": return c.getString(R.string.schedule_weekly_at, weekdayName(c, prefs.getInt("weekday",Calendar.MONDAY)), time);
            case "monthly": return c.getString(R.string.schedule_monthly_at, prefs.getInt("dayOfMonth",1), time);
            default: return c.getString(R.string.schedule_daily_at, time);
        }
    }
    public static void scheduleNext(Context c){
        migrateLegacyPref(c);
        SharedPreferences prefs = prefs(c);
        if(!prefs.getBoolean("scheduleEnabled", false)){install(c,0);return;}
        install(c,nextRun(prefs).getTimeInMillis());
    }
    // Before localization, "schedule" stored the displayed English text itself
    // ("Off" or "Daily at HH:mm") and doubled as the on/off flag. Displaying a
    // translated label from that same stored string would either show stale
    // English forever or require re-parsing a possibly-translated string back
    // into a flag. Instead, migrate once to a locale-independent boolean (the
    // hour/minute needed to rebuild the label were already stored separately)
    // and drop the legacy key so this only ever runs once per install.
    private static void migrateLegacyPref(Context c) {
        SharedPreferences prefs = prefs(c);
        if (!prefs.contains("schedule")) return;
        boolean enabled = !"Off".equals(prefs.getString("schedule", "Off"));
        prefs.edit().putBoolean("scheduleEnabled", enabled).remove("schedule").apply();
    }
    private static SharedPreferences prefs(Context c) { return BackupManager.prefs(c); }
    private static Calendar nextRun(SharedPreferences prefs){
        int h=prefs.getInt("hour",9), m=prefs.getInt("minute",0);
        long now=System.currentTimeMillis();
        switch(prefs.getString(MODE,"daily")){
            case "weekly": return calendarAt(nextWeeklyRun(now, prefs.getInt("weekday",Calendar.MONDAY), h, m));
            case "monthly": return calendarAt(nextMonthlyRun(now, prefs.getInt("dayOfMonth",1), h, m));
            default: return calendarAt(nextDailyRun(now, h, m));
        }
    }
    private static Calendar calendarAt(long millis){Calendar cal=Calendar.getInstance();cal.setTimeInMillis(millis);return cal;}
    /** Next daily hh:mm strictly in the future. */
    static long nextDailyRun(long now,int h,int m){
        Calendar next=Calendar.getInstance();next.setTimeInMillis(now);next.set(Calendar.HOUR_OF_DAY,h);next.set(Calendar.MINUTE,m);next.set(Calendar.SECOND,0);next.set(Calendar.MILLISECOND,0);
        if(next.getTimeInMillis()<=now)next.add(Calendar.DAY_OF_YEAR,1);
        return next.getTimeInMillis();
    }
    /** Next occurrence of {@code weekday} at hh:mm strictly in the future. */
    static long nextWeeklyRun(long now,int weekday,int h,int m){
        Calendar next=Calendar.getInstance();next.setTimeInMillis(now);next.set(Calendar.DAY_OF_WEEK,weekday);next.set(Calendar.HOUR_OF_DAY,h);next.set(Calendar.MINUTE,m);next.set(Calendar.SECOND,0);next.set(Calendar.MILLISECOND,0);
        if(next.getTimeInMillis()<=now)next.add(Calendar.DAY_OF_YEAR,7);
        return next.getTimeInMillis();
    }
    /** Next {@code dayOfMonth} (1-31) at hh:mm strictly in the future. In months too
     *  short for the chosen day, the run clamps to that month's last day. */
    static long nextMonthlyRun(long now,int dayOfMonth,int h,int m){
        Calendar next=Calendar.getInstance();next.setTimeInMillis(now);next.set(Calendar.HOUR_OF_DAY,h);next.set(Calendar.MINUTE,m);next.set(Calendar.SECOND,0);next.set(Calendar.MILLISECOND,0);
        next.set(Calendar.DAY_OF_MONTH,Math.min(dayOfMonth,next.getActualMaximum(Calendar.DAY_OF_MONTH)));
        if(next.getTimeInMillis()<=now){
            next.add(Calendar.MONTH,1);
            next.set(Calendar.DAY_OF_MONTH,Math.min(dayOfMonth,next.getActualMaximum(Calendar.DAY_OF_MONTH)));
        }
        return next.getTimeInMillis();
    }
    /** Localized full weekday name (e.g. "Monday") for a Calendar.DAY_OF_WEEK constant. */
    static String weekdayName(Context c,int weekday){
        String[] days=new DateFormatSymbols(Locale.getDefault()).getWeekdays();
        return weekday>=Calendar.SUNDAY&&weekday<=Calendar.SATURDAY?days[weekday]:days[Calendar.MONDAY];
    }
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

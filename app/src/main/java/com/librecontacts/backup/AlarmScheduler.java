package com.librecontacts.backup;
import android.app.*;import android.content.*;import android.os.Build;import java.util.*;
public final class AlarmScheduler {
    public static void set(Context c,String ignored){scheduleNext(c);}
    public static void setAtTime(Context c,int h,int m){Calendar next=Calendar.getInstance();next.set(Calendar.HOUR_OF_DAY,h);next.set(Calendar.MINUTE,m);next.set(Calendar.SECOND,0);next.set(Calendar.MILLISECOND,0);if(next.getTimeInMillis()<=System.currentTimeMillis())next.add(Calendar.DAY_OF_YEAR,1);install(c,next.getTimeInMillis());}
    public static void restore(Context c){scheduleNext(c);}
    public static void scheduleNext(Context c){String s=BackupManager.prefs(c).getString("schedule","Off");if(s.equals("Off")){install(c,0);return;}if(s.startsWith("Daily at ")){setAtTime(c,BackupManager.prefs(c).getInt("hour",9),BackupManager.prefs(c).getInt("minute",0));return;}Calendar next=Calendar.getInstance();if(s.equals("Weekly"))next.add(Calendar.DAY_OF_YEAR,7);else if(s.equals("Monthly"))next.add(Calendar.DAY_OF_YEAR,30);else next.add(Calendar.DAY_OF_YEAR,1);install(c,next.getTimeInMillis());}
    private static void install(Context c,long when){AlarmManager manager=(AlarmManager)c.getSystemService(Context.ALARM_SERVICE);Intent intent=new Intent(c,BackupAlarmReceiver.class);PendingIntent pending=PendingIntent.getBroadcast(c,7,intent,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);manager.cancel(pending);if(when==0)return;if(Build.VERSION.SDK_INT>=23)manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,when,pending);else manager.set(AlarmManager.RTC_WAKEUP,when,pending);}
}

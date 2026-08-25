package com.ashkanrafiee.librecontactsbackup;
import android.app.*;import android.content.*;import android.os.Build;import java.util.*;
public final class AlarmScheduler {
    public static void set(Context c,String ignored){scheduleNext(c);}
    public static void setAtTime(Context c,int h,int m){install(c,nextRun(h,m).getTimeInMillis());}
    public static void restore(Context c){scheduleNext(c);}
    public static String displayLabel(Context c){
        String s=BackupManager.prefs(c).getString("schedule","Off");
        if(!s.equals("Off")&&!s.startsWith("Daily at "))return String.format(Locale.getDefault(),"Daily at %02d:%02d",BackupManager.prefs(c).getInt("hour",9),BackupManager.prefs(c).getInt("minute",0));
        return s;
    }
    public static void scheduleNext(Context c){
        String s=BackupManager.prefs(c).getString("schedule","Off");
        if(s.equals("Off")){install(c,0);return;}
        if(!s.startsWith("Daily at ")){
            s=String.format(Locale.getDefault(),"Daily at %02d:%02d",BackupManager.prefs(c).getInt("hour",9),BackupManager.prefs(c).getInt("minute",0));
            BackupManager.prefs(c).edit().putString("schedule",s).apply();
        }
        install(c,nextRun(BackupManager.prefs(c).getInt("hour",9),BackupManager.prefs(c).getInt("minute",0)).getTimeInMillis());
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

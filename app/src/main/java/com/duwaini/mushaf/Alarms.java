package com.duwaini.mushaf;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.AudioAttributes;
import android.media.RingtoneManager;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;

/** Scheduling helpers for prayer, adhkar and daily wird notifications. */
public final class Alarms {
    static final String PREFS = "mushaf";
    static final String CH_ADHAN = "adhan";
    static final String CH_REMIND = "remind";
    static final int BASE = 1000, WIRD_ID = 900;

    private Alarms() { }

    static SharedPreferences prefs(Context c) { return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE); }

    static void channels(Context c) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        if (nm == null || nm.getNotificationChannel(CH_ADHAN) != null) return;
        NotificationChannel a = new NotificationChannel(CH_ADHAN, "مواقيت الصلاة", NotificationManager.IMPORTANCE_HIGH);
        a.setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION),
                new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION).build());
        a.enableVibration(true);
        NotificationChannel r = new NotificationChannel(CH_REMIND, "التذكيرات", NotificationManager.IMPORTANCE_DEFAULT);
        nm.createNotificationChannel(a);
        nm.createNotificationChannel(r);
    }

    private static void setAlarm(Context c, long at, PendingIntent pi) {
        AlarmManager am = c.getSystemService(AlarmManager.class);
        if (am == null) return;
        boolean exact = Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms();
        if (exact) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
        else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
    }

    private static PendingIntent alarmIntent(Context c, int id, String title, String body, String kind, String open) {
        Intent i = new Intent(c, AlarmReceiver.class);
        i.putExtra("id", id).putExtra("title", title).putExtra("body", body).putExtra("kind", kind).putExtra("open", open);
        return PendingIntent.getBroadcast(c, id, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    /** Replaces all prayer and adhkar alarms with the given list of {at,title,body,kind,open}. */
    static void schedule(Context c, String json) {
        SharedPreferences p = prefs(c);
        AlarmManager am = c.getSystemService(AlarmManager.class);
        int old = p.getInt("schedCount", 0);
        for (int k = 0; k < old; k++) {
            PendingIntent pi = alarmIntent(c, BASE + k, "", "", "", "");
            if (am != null) am.cancel(pi);
        }
        p.edit().putString("sched", json).apply();
        int n = 0;
        try {
            JSONArray a = new JSONArray(json);
            long now = System.currentTimeMillis();
            for (int k = 0; k < a.length(); k++) {
                JSONObject o = a.getJSONObject(k);
                long at = o.getLong("at");
                if (at <= now) continue;
                setAlarm(c, at, alarmIntent(c, BASE + n, o.optString("title"), o.optString("body"), o.optString("kind"), o.optString("open")));
                n++;
            }
        } catch (Exception ignored) { }
        p.edit().putInt("schedCount", n).apply();
    }

    /** Daily wird reminder at HH:MM, or none for an empty string. */
    static void setWird(Context c, String hhmm) {
        prefs(c).edit().putString("wirdTime", hhmm).apply();
        AlarmManager am = c.getSystemService(AlarmManager.class);
        PendingIntent pi = alarmIntent(c, WIRD_ID, "ورد اليوم", "لسه ما خلصتش ورد اليوم، افتح المصحف وكمّل وردك", "wird", "wird");
        if (am != null) am.cancel(pi);
        if (hhmm == null || !hhmm.matches("\\d{1,2}:\\d{2}")) return;
        String[] t = hhmm.split(":");
        Calendar cal = Calendar.getInstance();
        cal.set(Calendar.HOUR_OF_DAY, Integer.parseInt(t[0]));
        cal.set(Calendar.MINUTE, Integer.parseInt(t[1]));
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        if (cal.getTimeInMillis() <= System.currentTimeMillis() + 1000) cal.add(Calendar.DAY_OF_MONTH, 1);
        setAlarm(c, cal.getTimeInMillis(), pi);
    }

    /** Daily reminder (e.g. morning / evening adhkar) at HH:MM; an empty time cancels it. */
    static void setDaily(Context c, int id, String hhmm, String title, String body, String open) {
        prefs(c).edit().putString("daily_" + id, hhmm + "|" + title + "|" + body + "|" + open).apply();
        AlarmManager am = c.getSystemService(AlarmManager.class);
        PendingIntent pi = alarmIntent(c, id, title, body, "daily", open);
        if (am != null) am.cancel(pi);
        if (hhmm == null || !hhmm.matches("\\d{1,2}:\\d{2}")) return;
        String[] t = hhmm.split(":");
        Calendar cal = Calendar.getInstance();
        cal.set(Calendar.HOUR_OF_DAY, Integer.parseInt(t[0]));
        cal.set(Calendar.MINUTE, Integer.parseInt(t[1]));
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        if (cal.getTimeInMillis() <= System.currentTimeMillis() + 1000) cal.add(Calendar.DAY_OF_MONTH, 1);
        setAlarm(c, cal.getTimeInMillis(), pi);
    }

    /** Re-arms a daily reminder from what was saved, after it fires or after a restart. */
    static void restoreDaily(Context c, int id) {
        String s = prefs(c).getString("daily_" + id, "");
        String[] p = s.split("\\|", -1);
        if (p.length == 4) setDaily(c, id, p[0], p[1], p[2], p[3]);
    }

    static String todayKey() {
        Calendar d = Calendar.getInstance();
        return d.get(Calendar.YEAR) + "-" + (d.get(Calendar.MONTH) + 1) + "-" + d.get(Calendar.DAY_OF_MONTH);
    }

    static void notifyNow(Context c, int id, String title, String body, String kind, String open) {
        channels(c);
        Intent i = new Intent(c, MainActivity.class).putExtra("open", open)
                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pi = PendingIntent.getActivity(c, id, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification n = new Notification.Builder(c, "adhan".equals(kind) ? CH_ADHAN : CH_REMIND)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(new Notification.BigTextStyle().bigText(body))
                .setColor(0xFF0E4D40)
                .setAutoCancel(true)
                .setContentIntent(pi)
                .build();
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        try { if (nm != null) nm.notify(id, n); } catch (SecurityException ignored) { }
    }

    static String fmt(long t) { return new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(new Date(t)); }
}

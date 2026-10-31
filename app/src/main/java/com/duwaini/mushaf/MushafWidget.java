package com.duwaini.mushaf;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.widget.RemoteViews;

/** Home-screen widget: ayah of the day and today's wird progress. */
public class MushafWidget extends AppWidgetProvider {
    @Override
    public void onUpdate(Context c, AppWidgetManager m, int[] ids) { update(c); }

    static void update(Context c) {
        SharedPreferences p = Alarms.prefs(c);
        RemoteViews v = new RemoteViews(c.getPackageName(), R.layout.widget);
        v.setTextViewText(R.id.w_ayah, p.getString("w_ayah", "افتح التطبيق لعرض آية اليوم"));
        v.setTextViewText(R.id.w_ref, p.getString("w_ref", "المصحف الشريف"));
        v.setTextViewText(R.id.w_wird, "ورد اليوم: " + p.getString("w_wird", "—"));
        Intent i = new Intent(c, MainActivity.class).setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        v.setOnClickPendingIntent(R.id.w_root, PendingIntent.getActivity(c, 7, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
        AppWidgetManager m = AppWidgetManager.getInstance(c);
        m.updateAppWidget(new ComponentName(c, MushafWidget.class), v);
    }
}

package com.duwaini.mushaf;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class AlarmReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent i) {
        String kind = i.getStringExtra("kind");
        if ("wird".equals(kind)) {
            String done = Alarms.prefs(c).getString("wirdDone", "");
            if (!Alarms.todayKey().equals(done)) {
                Alarms.notifyNow(c, Alarms.WIRD_ID, i.getStringExtra("title"), i.getStringExtra("body"), kind, "wird");
            }
            Alarms.setWird(c, Alarms.prefs(c).getString("wirdTime", ""));
            return;
        }
        if ("daily".equals(kind)) {
            int id = i.getIntExtra("id", 901);
            String open = i.getStringExtra("open");
            String done = Alarms.prefs(c).getString("done_" + open, "");
            if (!Alarms.todayKey().equals(done)) Alarms.notifyNow(c, id, i.getStringExtra("title"), i.getStringExtra("body"), "daily", open);
            Alarms.restoreDaily(c, id);
            return;
        }
        Alarms.notifyNow(c, i.getIntExtra("id", 1), i.getStringExtra("title"), i.getStringExtra("body"), kind, i.getStringExtra("open"));
    }
}

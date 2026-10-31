package com.duwaini.mushaf;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Restores scheduled notifications after the phone restarts or the app is updated. */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent i) {
        String sched = Alarms.prefs(c).getString("sched", "[]");
        Alarms.schedule(c, sched);
        Alarms.setWird(c, Alarms.prefs(c).getString("wirdTime", ""));
        Alarms.restoreDaily(c, 901);
        Alarms.restoreDaily(c, 902);
    }
}

package com.anormalq.kapanmadedektifi;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;

/** Telefon uykudayken de birkaç dakikada bir durum kaydı alır. */
public class AlarmReceiver extends BroadcastReceiver {
    private static final long INTERVAL = 5 * 60_000L;

    static void schedule(Context c) {
        try {
            AlarmManager am = MonitorService.alarms(c);
            PendingIntent pi = PendingIntent.getBroadcast(c, 7, new Intent(c, AlarmReceiver.class),
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    SystemClock.elapsedRealtime() + INTERVAL, pi);
        } catch (Exception ignored) {
        }
    }

    @Override
    public void onReceive(Context c, Intent i) {
        Store.heartbeat(c);
        schedule(c);
        if (!MonitorService.running) {
            try {
                MonitorService.start(c);
            } catch (Exception e) {
                Store.log(c, "SERVİS", "Servis kapalıydı ve arka plandan başlatılamadı. Uygulamayı bir kez açın.");
            }
        }
    }
}

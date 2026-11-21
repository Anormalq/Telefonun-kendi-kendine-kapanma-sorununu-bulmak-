package com.anormalq.kapanmadedektifi;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Telefon "düzgün" kapatılırken (yazılım yoluyla) sistem bu sinyali yollar.
 *  Ani kapanmada (pil kopması, donanım, çökme, zorla kapatma) bu sinyal GELMEZ. */
public class ShutdownReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent i) {
        Analyzer.check(c, "kapanma sinyali");
        Store.Snap s = Store.snap(c);
        long now = System.currentTimeMillis();
        boolean userspaceOnly = i.getBooleanExtra("android.intent.extra.SHUTDOWN_USERSPACE_ONLY", false);
        Store.prefs(c).edit()
                .putLong("sd_time", now)
                .putString("sd_action", String.valueOf(i.getAction()))
                .putInt("sd_batt", s.pct)
                .putInt("sd_temp", s.tempTenths)
                .putInt("sd_volt", s.volt)
                .putInt("sd_thermal", s.thermal)
                .putInt("sd_prox", MonitorService.proxNear)
                .putBoolean("sd_screen", s.interactive)
                .commit();
        Store.log(c, "KAPANMA", "⚠ SİSTEM KAPANIYOR (sinyal: " + i.getAction() + (userspaceOnly ? ", yazılım yeniden başlatma" : "")
                + ") | " + s.battText() + " | ekran " + (s.interactive ? "açık" : "kapalı")
                + " | yakınlık: " + Store.proxText(MonitorService.proxNear));
    }
}

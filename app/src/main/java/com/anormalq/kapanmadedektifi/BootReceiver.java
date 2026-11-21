package com.anormalq.kapanmadedektifi;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;

public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent i) {
        String a = i.getAction();
        if (a == null) return;
        if (Intent.ACTION_LOCKED_BOOT_COMPLETED.equals(a)) {
            // Kilit henüz açılmadı; rapor kilit açılınca (BOOT_COMPLETED) çıkarılacak.
            Store.log(c, "AÇILIŞ", "Telefon açıldı (ekran kilidi henüz açılmadı). Açılış süresi: "
                    + Store.dur(SystemClock.elapsedRealtime()));
            return;
        }
        if (Intent.ACTION_MY_PACKAGE_REPLACED.equals(a)) {
            Store.log(c, "UYGULAMA", "Kapanma Dedektifi güncellendi");
        } else {
            Analyzer.check(c, "açılış");
            Store.log(c, "AÇILIŞ", "Telefon AÇILDI ve kilit açıldı. " + Store.snap(c).battText());
        }
        try {
            MonitorService.start(c);
        } catch (Exception e) {
            Store.log(c, "SERVİS", "Servis açılışta başlatılamadı: " + e.getClass().getSimpleName());
        }
    }
}

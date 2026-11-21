package com.anormalq.kapanmadedektifi;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.BatteryManager;
import android.os.Build;
import android.os.PowerManager;
import android.provider.Settings;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Locale;

/** Kayıt dosyaları ve son bilinen durum. Her şey "cihaz korumalı" depoda tutulur,
 *  böylece telefon açılıp kilit daha açılmadan da yazılabilir. */
public final class Store {
    private static final Object LOCK = new Object();

    private Store() {}

    static Context dp(Context c) {
        Context a = c.getApplicationContext();
        if (a == null) a = c;
        return a.createDeviceProtectedStorageContext();
    }

    static SharedPreferences prefs(Context c) {
        return dp(c).getSharedPreferences("durum", Context.MODE_PRIVATE);
    }

    static File logFile(Context c) { return new File(dp(c).getFilesDir(), "olaylar.log"); }
    static File oldLogFile(Context c) { return new File(dp(c).getFilesDir(), "olaylar.eski.log"); }
    static File reportFile(Context c) { return new File(dp(c).getFilesDir(), "raporlar.log"); }

    static String fmt(long t) {
        if (t <= 0) return "-";
        return new SimpleDateFormat("dd.MM.yyyy HH:mm:ss", Locale.US).format(new Date(t));
    }

    static String dur(long ms) {
        if (ms < 0) return "?";
        long s = ms / 1000;
        if (s < 60) return s + " sn";
        long m = s / 60;
        if (m < 60) return m + " dk " + (s % 60) + " sn";
        long h = m / 60;
        if (h < 48) return h + " sa " + (m % 60) + " dk";
        return (h / 24) + " gün " + (h % 24) + " sa";
    }

    /** Olay günlüğüne bir satır ekler ve diske hemen yazar (ani kapanmada kaybolmasın). */
    static void log(Context c, String type, String msg) {
        String line = fmt(System.currentTimeMillis()) + " | " + type + " | " + msg + "\n";
        synchronized (LOCK) {
            File f = logFile(c);
            if (f.length() > 3_000_000) {
                File old = oldLogFile(c);
                //noinspection ResultOfMethodCallIgnored
                old.delete();
                //noinspection ResultOfMethodCallIgnored
                f.renameTo(old);
            }
            append(f, line);
        }
    }

    static void appendReport(Context c, String report) {
        synchronized (LOCK) {
            append(reportFile(c), report + "\n");
        }
        log(c, "RAPOR", "Yeni kapanma raporu oluşturuldu (Raporlar bölümüne bakın)");
    }

    private static void append(File f, String s) {
        try (FileOutputStream o = new FileOutputStream(f, true)) {
            o.write(s.getBytes(StandardCharsets.UTF_8));
            o.getFD().sync();
        } catch (IOException ignored) {
        }
    }

    static String tail(File f, int maxLines) {
        ArrayDeque<String> q = new ArrayDeque<>();
        synchronized (LOCK) {
            if (!f.exists()) return "";
            try (BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8))) {
                String l;
                while ((l = r.readLine()) != null) {
                    q.addLast(l);
                    if (q.size() > maxLines) q.removeFirst();
                }
            } catch (IOException ignored) {
            }
        }
        StringBuilder sb = new StringBuilder();
        for (String s : q) sb.append(s).append('\n');
        return sb.toString();
    }

    static void clearAll(Context c) {
        synchronized (LOCK) {
            //noinspection ResultOfMethodCallIgnored
            logFile(c).delete();
            //noinspection ResultOfMethodCallIgnored
            oldLogFile(c).delete();
            //noinspection ResultOfMethodCallIgnored
            reportFile(c).delete();
        }
    }

    static int bootCount(Context c) {
        try {
            return Settings.Global.getInt(c.getContentResolver(), Settings.Global.BOOT_COUNT, -1);
        } catch (Exception e) {
            return -1;
        }
    }

    // ---- Anlık durum ----

    static final class Snap {
        int pct = -1, tempTenths = -1000, volt = -1, plugged = 0, health = 0, status = 0;
        boolean interactive;
        int thermal = -1;

        String battText() {
            StringBuilder sb = new StringBuilder();
            sb.append("pil %").append(pct);
            if (tempTenths > -1000) sb.append(", ").append(tempTenths / 10.0).append("°C");
            if (volt > 0) sb.append(", ").append(volt).append(" mV");
            sb.append(", ").append(plugText(plugged));
            if (health != 0 && health != BatteryManager.BATTERY_HEALTH_GOOD) sb.append(", sağlık: ").append(healthText(health));
            return sb.toString();
        }
    }

    static Snap snap(Context c) {
        Snap s = new Snap();
        try {
            Intent b = c.getApplicationContext().registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (b != null) {
                int level = b.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
                int scale = b.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
                if (level >= 0 && scale > 0) s.pct = level * 100 / scale;
                s.tempTenths = b.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1000);
                s.volt = b.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1);
                s.plugged = b.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0);
                s.health = b.getIntExtra(BatteryManager.EXTRA_HEALTH, 0);
                s.status = b.getIntExtra(BatteryManager.EXTRA_STATUS, 0);
            }
        } catch (Exception ignored) {
        }
        try {
            PowerManager pm = (PowerManager) c.getSystemService(Context.POWER_SERVICE);
            s.interactive = pm.isInteractive();
            if (Build.VERSION.SDK_INT >= 29) s.thermal = pm.getCurrentThermalStatus();
        } catch (Exception ignored) {
        }
        return s;
    }

    /** Son bilinen durumu kaydeder. Ani kapanmada "en son ne durumdaydı" buradan okunur. */
    static void heartbeat(Context c) {
        Analyzer.check(c, "kalp atışı");
        Snap s = snap(c);
        prefs(c).edit()
                .putLong("hb_time", System.currentTimeMillis())
                .putInt("hb_batt", s.pct)
                .putInt("hb_temp", s.tempTenths)
                .putInt("hb_volt", s.volt)
                .putInt("hb_plug", s.plugged)
                .putBoolean("hb_screen", s.interactive)
                .putInt("hb_thermal", s.thermal)
                .putInt("hb_prox", MonitorService.proxNear)
                .putFloat("hb_light", MonitorService.lightLux)
                .commit();
    }

    static String plugText(int p) {
        if (p == BatteryManager.BATTERY_PLUGGED_AC) return "şarjda (adaptör)";
        if (p == BatteryManager.BATTERY_PLUGGED_USB) return "şarjda (USB)";
        if (p == BatteryManager.BATTERY_PLUGGED_WIRELESS) return "şarjda (kablosuz)";
        if (p != 0) return "şarjda";
        return "şarjda değil";
    }

    static String healthText(int h) {
        switch (h) {
            case BatteryManager.BATTERY_HEALTH_GOOD: return "iyi";
            case BatteryManager.BATTERY_HEALTH_OVERHEAT: return "AŞIRI ISINMA";
            case BatteryManager.BATTERY_HEALTH_DEAD: return "ÖLÜ PİL";
            case BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE: return "AŞIRI VOLTAJ";
            case BatteryManager.BATTERY_HEALTH_UNSPECIFIED_FAILURE: return "ARIZA";
            case BatteryManager.BATTERY_HEALTH_COLD: return "SOĞUK";
            default: return "bilinmiyor";
        }
    }

    static String thermalText(int t) {
        switch (t) {
            case 0: return "normal";
            case 1: return "hafif ısınma";
            case 2: return "orta ısınma";
            case 3: return "ciddi ısınma";
            case 4: return "KRİTİK ısınma";
            case 5: return "ACİL ısınma";
            case 6: return "ISIDAN KAPANMA";
            default: return "bilinmiyor";
        }
    }

    static String proxText(int p) {
        if (p == 1) return "YAKIN (cepte/kapalı yerde olabilir)";
        if (p == 0) return "uzak (açıkta)";
        return "bilinmiyor";
    }
}

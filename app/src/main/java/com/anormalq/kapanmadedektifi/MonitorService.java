package com.anormalq.kapanmadedektifi;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;

/** Arka planda sürekli çalışan izleme servisi. */
public class MonitorService extends Service implements SensorEventListener {

    /** -1 bilinmiyor, 0 uzak, 1 yakın (cepte) */
    static volatile int proxNear = -1;
    static volatile float lightLux = -1f;
    static volatile boolean running = false;

    private static final String CH = "izleme";
    private static final long HEARTBEAT_MS = 30_000;

    private Handler handler;
    private BroadcastReceiver rx, pkgRx;
    private SensorManager sm;
    private Object thermalListener;
    private int lastLevel = -1;
    private int lastTempBand = -99;
    private long lastProxLog = 0;

    private final Runnable beat = new Runnable() {
        @Override public void run() {
            Store.heartbeat(MonitorService.this);
            handler.postDelayed(this, HEARTBEAT_MS);
        }
    };

    static void start(Context c) {
        Intent i = new Intent(c, MonitorService.class);
        if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i); else c.startService(i);
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onCreate() {
        super.onCreate();
        running = true;
        goForeground();
        // Önceki oturum için rapor (varsa) yeni kayıtlar yazılmadan önce çıkarılmalı.
        Analyzer.check(this, "servis başladı");
        Store.log(this, "SERVİS", "İzleme başladı. " + Store.snap(this).battText());

        handler = new Handler(Looper.getMainLooper());
        registerSystemReceivers();
        registerSensors();
        registerThermal();
        handler.post(beat);
        AlarmReceiver.schedule(this);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        Store.log(this, "SERVİS", "Uygulama son kullanılanlardan kaydırıldı (servis çalışmaya devam etmeli)");
        super.onTaskRemoved(rootIntent);
    }

    @Override
    public void onDestroy() {
        Store.log(this, "SERVİS", "İzleme servisi DURDU (sistem ya da kullanıcı durdurdu)");
        running = false;
        try { if (rx != null) unregisterReceiver(rx); } catch (Exception ignored) {}
        try { if (pkgRx != null) unregisterReceiver(pkgRx); } catch (Exception ignored) {}
        try { if (sm != null) sm.unregisterListener(this); } catch (Exception ignored) {}
        if (Build.VERSION.SDK_INT >= 29 && thermalListener != null) {
            try {
                ((PowerManager) getSystemService(POWER_SERVICE))
                        .removeThermalStatusListener((PowerManager.OnThermalStatusChangedListener) thermalListener);
            } catch (Exception ignored) {}
        }
        if (handler != null) handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    private void goForeground() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm.getNotificationChannel(CH) == null) {
            NotificationChannel ch = new NotificationChannel(CH, "İzleme", NotificationManager.IMPORTANCE_MIN);
            ch.setDescription("Kapanma Dedektifi arka planda kayıt tutuyor");
            ch.setShowBadge(false);
            nm.createNotificationChannel(ch);
        }
        PendingIntent pi = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification n = new Notification.Builder(this, CH)
                .setSmallIcon(android.R.drawable.ic_menu_search)
                .setContentTitle("Kapanma Dedektifi çalışıyor")
                .setContentText("Kapanmalar ve olaylar kaydediliyor")
                .setOngoing(true)
                .setContentIntent(pi)
                .build();
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(1, n);
        }
    }

    private void reg(BroadcastReceiver r, IntentFilter f) {
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(r, f, Context.RECEIVER_EXPORTED);
        else registerReceiver(r, f);
    }

    private void registerSystemReceivers() {
        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_SCREEN_OFF);
        f.addAction(Intent.ACTION_SCREEN_ON);
        f.addAction(Intent.ACTION_USER_PRESENT);
        f.addAction(Intent.ACTION_POWER_CONNECTED);
        f.addAction(Intent.ACTION_POWER_DISCONNECTED);
        f.addAction(Intent.ACTION_BATTERY_LOW);
        f.addAction(Intent.ACTION_BATTERY_OKAY);
        f.addAction(Intent.ACTION_BATTERY_CHANGED);
        f.addAction(Intent.ACTION_AIRPLANE_MODE_CHANGED);
        f.addAction(Intent.ACTION_CLOSE_SYSTEM_DIALOGS);
        f.addAction(Intent.ACTION_TIME_CHANGED);
        f.addAction(Intent.ACTION_TIMEZONE_CHANGED);
        f.addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED);
        f.addAction(PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED);
        f.addAction("android.intent.action.ACTION_REQUEST_SHUTDOWN");
        rx = new BroadcastReceiver() {
            @Override public void onReceive(Context c, Intent i) { onSystemEvent(i); }
        };
        reg(rx, f);

        IntentFilter pf = new IntentFilter();
        pf.addAction(Intent.ACTION_PACKAGE_ADDED);
        pf.addAction(Intent.ACTION_PACKAGE_REMOVED);
        pf.addAction(Intent.ACTION_PACKAGE_REPLACED);
        pf.addDataScheme("package");
        pkgRx = new BroadcastReceiver() {
            @Override public void onReceive(Context c, Intent i) {
                Uri d = i.getData();
                String pkg = d != null ? d.getSchemeSpecificPart() : "?";
                boolean replacing = i.getBooleanExtra(Intent.EXTRA_REPLACING, false);
                String a = i.getAction();
                if (Intent.ACTION_PACKAGE_ADDED.equals(a) && !replacing) Store.log(c, "UYGULAMA", "Yüklendi: " + Analyzer.label(c, pkg));
                else if (Intent.ACTION_PACKAGE_REPLACED.equals(a)) Store.log(c, "UYGULAMA", "Güncellendi: " + Analyzer.label(c, pkg));
                else if (Intent.ACTION_PACKAGE_REMOVED.equals(a) && !replacing) Store.log(c, "UYGULAMA", "Kaldırıldı: " + pkg);
            }
        };
        reg(pkgRx, pf);
    }

    private void onSystemEvent(Intent i) {
        String a = i.getAction();
        if (a == null) return;
        switch (a) {
            case Intent.ACTION_SCREEN_OFF: {
                Store.prefs(this).edit()
                        .putLong("so_time", System.currentTimeMillis())
                        .putInt("so_prox", proxNear).commit();
                Store.log(this, "EKRAN", "Ekran KAPANDI | yakınlık sensörü: " + Store.proxText(proxNear) + lightText());
                Store.heartbeat(this);
                break;
            }
            case Intent.ACTION_SCREEN_ON:
                Store.log(this, "EKRAN", "Ekran AÇILDI | yakınlık sensörü: " + Store.proxText(proxNear) + lightText());
                Store.heartbeat(this);
                break;
            case Intent.ACTION_USER_PRESENT:
                Store.log(this, "EKRAN", "Ekran kilidi açıldı");
                break;
            case Intent.ACTION_POWER_CONNECTED:
                Store.log(this, "ŞARJ", "Şarj kablosu TAKILDI. " + Store.snap(this).battText());
                break;
            case Intent.ACTION_POWER_DISCONNECTED:
                Store.log(this, "ŞARJ", "Şarj kablosu ÇIKARILDI. " + Store.snap(this).battText());
                break;
            case Intent.ACTION_BATTERY_LOW:
                Store.log(this, "PİL", "Sistem: PİL DÜŞÜK uyarısı");
                break;
            case Intent.ACTION_BATTERY_OKAY:
                Store.log(this, "PİL", "Sistem: pil seviyesi tekrar normal");
                break;
            case Intent.ACTION_BATTERY_CHANGED:
                onBattery(i);
                break;
            case Intent.ACTION_AIRPLANE_MODE_CHANGED:
                Store.log(this, "AYAR", "Uçak modu " + (i.getBooleanExtra("state", false) ? "AÇILDI" : "kapatıldı"));
                break;
            case Intent.ACTION_CLOSE_SYSTEM_DIALOGS: {
                String reason = i.getStringExtra("reason");
                if ("globalactions".equals(reason)) {
                    Store.prefs(this).edit().putLong("pm_time", System.currentTimeMillis())
                            .putInt("pm_prox", proxNear).commit();
                    Store.log(this, "GÜÇ TUŞU", "GÜÇ MENÜSÜ AÇILDI (güç tuşuna basılı tutuldu) | yakınlık: " + Store.proxText(proxNear));
                } else if (reason != null && !"homekey".equals(reason) && !"recentapps".equals(reason)) {
                    Store.log(this, "SİSTEM", "Sistem penceresi kapandı, neden: " + reason);
                }
                break;
            }
            case Intent.ACTION_TIME_CHANGED:
                Store.log(this, "SAAT", "Telefon saati değişti (kayıt saatleri kayabilir)");
                break;
            case Intent.ACTION_TIMEZONE_CHANGED:
                Store.log(this, "SAAT", "Saat dilimi değişti");
                break;
            case PowerManager.ACTION_POWER_SAVE_MODE_CHANGED: {
                PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
                Store.log(this, "AYAR", "Güç tasarrufu modu " + (pm.isPowerSaveMode() ? "AÇIK" : "kapalı"));
                break;
            }
            case PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED: {
                PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
                Store.log(this, "UYKU", pm.isDeviceIdleMode() ? "Telefon derin uykuya girdi (Doze)" : "Derin uykudan çıktı");
                break;
            }
            default:
                Store.log(this, "SİSTEM", "Olay: " + a);
        }
    }

    private void onBattery(Intent b) {
        int level = b.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int scale = b.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
        if (level < 0 || scale <= 0) return;
        int pct = level * 100 / scale;
        int temp = b.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1000);
        int band = temp > -1000 ? (temp >= 500 ? 2 : temp >= 420 ? 1 : 0) : -1;
        if (pct != lastLevel) {
            if (lastLevel >= 0) {
                String jump = Math.abs(pct - lastLevel) >= 3 ? "  ⚠ ANİ DEĞİŞİM (" + lastLevel + "→" + pct + ")" : "";
                Store.log(this, "PİL", Store.snap(this).battText() + jump);
            }
            lastLevel = pct;
            Store.heartbeat(this);
        }
        if (band != lastTempBand) {
            if (lastTempBand != -99 && band > lastTempBand) {
                Store.log(this, "ISI", "Pil sıcaklığı yükseldi: " + (temp / 10.0) + "°C" + (band == 2 ? "  ⚠ ÇOK SICAK" : ""));
            }
            lastTempBand = band;
        }
    }

    private String lightText() {
        return lightLux >= 0 ? " | ışık: " + Math.round(lightLux) + " lux" + (lightLux < 3 ? " (karanlık)" : "") : "";
    }

    private void registerSensors() {
        try {
            sm = (SensorManager) getSystemService(SENSOR_SERVICE);
            Sensor p = sm.getDefaultSensor(Sensor.TYPE_PROXIMITY);
            if (p != null) sm.registerListener(this, p, SensorManager.SENSOR_DELAY_NORMAL);
            Sensor l = sm.getDefaultSensor(Sensor.TYPE_LIGHT);
            if (l != null) sm.registerListener(this, l, SensorManager.SENSOR_DELAY_NORMAL);
        } catch (Exception e) {
            Store.log(this, "SENSÖR", "Sensörler açılamadı: " + e);
        }
    }

    private void registerThermal() {
        if (Build.VERSION.SDK_INT < 29) return;
        try {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            PowerManager.OnThermalStatusChangedListener l = status -> {
                Store.log(MonitorService.this, "ISI", "Telefon ısı durumu: " + Store.thermalText(status)
                        + (status >= 4 ? "  ⚠ Isıdan kapanma riski!" : ""));
                Store.heartbeat(MonitorService.this);
            };
            pm.addThermalStatusListener(l);
            thermalListener = l;
        } catch (Exception ignored) {
        }
    }

    @Override
    public void onSensorChanged(SensorEvent e) {
        if (e.sensor.getType() == Sensor.TYPE_PROXIMITY) {
            float max = e.sensor.getMaximumRange();
            int near = e.values[0] < Math.min(max, 5f) ? 1 : 0;
            if (near != proxNear) {
                int old = proxNear;
                proxNear = near;
                long now = SystemClock.elapsedRealtime();
                PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
                if (old != -1 && !pm.isInteractive() && now - lastProxLog > 15_000) {
                    lastProxLog = now;
                    Store.log(this, "CEP", near == 1 ? "Telefon cebe/kapalı yere girdi (yakınlık: yakın)" : "Telefon cepten çıktı (yakınlık: uzak)");
                }
            }
        } else if (e.sensor.getType() == Sensor.TYPE_LIGHT) {
            lightLux = e.values[0];
        }
    }

    @Override public void onAccuracyChanged(Sensor sensor, int accuracy) {}

    // AlarmManager tekrar planlama yardımcıları AlarmReceiver içinde.
    static AlarmManager alarms(Context c) { return (AlarmManager) c.getSystemService(Context.ALARM_SERVICE); }
}

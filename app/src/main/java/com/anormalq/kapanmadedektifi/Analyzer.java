package com.anormalq.kapanmadedektifi;

import android.app.AppOpsManager;
import android.app.admin.DevicePolicyManager;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.DropBoxManager;
import android.os.Process;
import android.os.SystemClock;
import android.os.UserManager;
import android.provider.Settings;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Telefon yeniden açıldığında önceki kapanmayı inceler ve rapor yazar. */
public final class Analyzer {
    private Analyzer() {}

    private static final String[] SESSION_KEYS = {
            "sd_time", "sd_action", "sd_batt", "sd_temp", "sd_volt", "sd_thermal", "sd_prox", "sd_screen",
            "pm_time", "pm_prox", "so_time", "so_prox"
    };

    /** Yeni bir açılış olduysa (telefon kapanıp açıldıysa) rapor oluşturur. Birden çok kez çağrılması güvenlidir. */
    static synchronized void check(Context c, String trigger) {
        SharedPreferences p = Store.prefs(c);
        int bc = Store.bootCount(c);
        long bootTime = System.currentTimeMillis() - SystemClock.elapsedRealtime();
        int lastBc = p.getInt("boot_count", Integer.MIN_VALUE);
        long lastBootTime = p.getLong("boot_time", 0);

        if (lastBc == Integer.MIN_VALUE && lastBootTime == 0) {
            p.edit().putInt("boot_count", bc).putLong("boot_time", bootTime).commit();
            Store.log(c, "BAŞLANGIÇ", "Kapanma Dedektifi ilk kez çalıştı. Bundan sonraki her kapanma kaydedilecek.");
            return;
        }
        boolean newBoot;
        if (bc >= 0 && lastBc != Integer.MIN_VALUE && lastBc >= 0) newBoot = bc != lastBc;
        else newBoot = Math.abs(bootTime - lastBootTime) > 120_000;
        if (!newBoot) return;

        String report;
        try {
            report = buildReport(c, p, bootTime, bc, lastBc);
        } catch (Throwable t) {
            report = "=== KAPANMA RAPORU ===\nTelefon açıldı: " + Store.fmt(bootTime) + "\nRapor hazırlanırken hata: " + t + "\n";
        }
        Store.appendReport(c, report);

        SharedPreferences.Editor e = p.edit().putInt("boot_count", bc).putLong("boot_time", bootTime)
                .putLong("prev_boot_time", lastBootTime);
        for (String k : SESSION_KEYS) e.remove(k);
        e.commit();
    }

    private static String buildReport(Context c, SharedPreferences p, long bootTime, int bc, int lastBc) {
        long hbTime = p.getLong("hb_time", 0);
        int hbBatt = p.getInt("hb_batt", -1);
        int hbTemp = p.getInt("hb_temp", -1000);
        int hbVolt = p.getInt("hb_volt", -1);
        int hbPlug = p.getInt("hb_plug", 0);
        boolean hbScreen = p.getBoolean("hb_screen", false);
        int hbThermal = p.getInt("hb_thermal", -1);
        int hbProx = p.getInt("hb_prox", -1);

        long sdTime = p.getLong("sd_time", 0);
        long pmTime = p.getLong("pm_time", 0);
        int pmProx = p.getInt("pm_prox", -1);
        long soTime = p.getLong("so_time", 0);
        int soProx = p.getInt("so_prox", -1);
        long prevBoot = p.getLong("boot_time", 0);

        // Önceki oturuma ait olmayan (çok eski) heartbeat'i kullanma
        if (hbTime > 0 && hbTime < prevBoot) hbTime = 0;

        boolean graceful = sdTime > 0;
        long lastAlive = Math.max(hbTime, sdTime);
        int batt = graceful ? p.getInt("sd_batt", hbBatt) : hbBatt;
        int temp = graceful ? p.getInt("sd_temp", hbTemp) : hbTemp;
        int volt = graceful ? p.getInt("sd_volt", hbVolt) : hbVolt;
        int thermal = graceful ? p.getInt("sd_thermal", hbThermal) : hbThermal;
        int prox = graceful ? p.getInt("sd_prox", hbProx) : hbProx;
        boolean screen = graceful ? p.getBoolean("sd_screen", hbScreen) : hbScreen;
        if (prox == -1) prox = soProx;

        StringBuilder r = new StringBuilder();
        r.append("==================================\n");
        r.append("=== KAPANMA RAPORU ===\n");
        r.append("Telefon yeniden açıldı: ").append(Store.fmt(bootTime)).append('\n');
        if (bc >= 0 && lastBc >= 0 && bc - lastBc > 1)
            r.append("Not: Arada ").append(bc - lastBc - 1).append(" açılış daha olmuş ama uygulama çalışmamış.\n");
        r.append("Önceki açılış: ").append(Store.fmt(prevBoot)).append('\n');
        if (lastAlive > 0) {
            r.append("Telefonun son canlı görüldüğü an: ").append(Store.fmt(lastAlive)).append('\n');
            r.append("Kapalı kalma süresi (yaklaşık): ").append(Store.dur(bootTime - lastAlive)).append('\n');
        } else {
            r.append("Son canlılık kaydı yok (izleme servisi çalışmıyordu olabilir).\n");
        }
        r.append("O andaki durum: pil %").append(batt);
        if (temp > -1000) r.append(", ").append(temp / 10.0).append("°C");
        if (volt > 0) r.append(", ").append(volt).append(" mV");
        r.append(", ").append(Store.plugText(hbPlug));
        r.append(", ekran ").append(screen ? "AÇIK" : "kapalı");
        r.append(", ısı durumu: ").append(Store.thermalText(thermal)).append('\n');
        r.append("Yakınlık sensörü (cep): ").append(Store.proxText(prox)).append('\n');
        if (soTime > 0) r.append("Ekranın son kapanışı: ").append(Store.fmt(soTime)).append('\n');

        r.append("\n-- Kapanma sinyali (ACTION_SHUTDOWN): ");
        if (graceful) r.append("ALINDI, ").append(Store.fmt(sdTime)).append(" (").append(p.getString("sd_action", "")).append(")\n");
        else r.append("ALINMADI\n");
        boolean powerMenu = pmTime > 0 && (graceful ? (sdTime - pmTime) >= 0 && (sdTime - pmTime) < 180_000
                : lastAlive - pmTime < 180_000);
        if (pmTime > 0) {
            r.append("-- Güç menüsü son açılışı: ").append(Store.fmt(pmTime));
            if (pmProx == 1) r.append(" (o an telefon CEPTE/kapalı yerdeydi)");
            r.append('\n');
        }

        // Sistem açılış nedeni
        Map<String, String> props = bootProps();
        String reasonAll = "";
        if (!props.isEmpty()) {
            r.append("\n-- Sistemin kaydettiği açılış/kapanış nedeni:\n");
            for (Map.Entry<String, String> en : props.entrySet()) {
                r.append("   ").append(en.getKey()).append(" = ").append(en.getValue()).append('\n');
                reasonAll += " " + en.getValue().toLowerCase(Locale.US);
            }
        }
        String sysReason = explainBootReason(props.get("sys.boot.reason") != null ? props.get("sys.boot.reason")
                : props.get("ro.boot.bootreason") != null ? props.get("ro.boot.bootreason") : reasonAll);

        // Kapanmadan hemen önce hangi uygulamalar açıktı
        long from = (lastAlive > 0 ? lastAlive : bootTime - 3_600_000L) - 10 * 60_000L;
        String usage = usageBefore(c, from, bootTime + 60_000L);
        r.append("\n-- Kapanmadan önceki uygulama hareketleri:\n").append(usage);

        // Sistem çökme kayıtları
        String drop = dropBox(c, from - 20 * 60_000L);
        if (drop != null) r.append("\n-- Sistem kayıtları (DropBox):\n").append(drop);

        // ---- SONUÇ ----
        r.append("\n>>> SONUÇ / OLASI NEDEN:\n");
        List<String> v = new ArrayList<>();
        if (sysReason != null) v.add("Sistemin kendi kaydı: " + sysReason);
        if (graceful) {
            long gap = bootTime - sdTime;
            boolean reboot = gap < 150_000;
            if (batt >= 0 && batt <= 3) {
                v.add("PİL BİTTİ: Pil %" + batt + " iken sistem kendini kapattı. (Pil düzenli olarak yüksek gösterip birden düşüyorsa pil yıpranmış olabilir.)");
            } else if (thermal >= 4 || temp >= 550) {
                v.add("AŞIRI ISINMA: Telefon ısındığı için kendini korumaya aldı.");
            } else if (powerMenu) {
                v.add("GÜÇ TUŞU: Güç tuşuna BASILI TUTULDU, güç menüsü açıldı ve '" + (reboot ? "Yeniden başlat" : "Kapat") + "' seçildi."
                        + (pmProx == 1 || prox == 1 ? " O sırada telefon CEPTEYDİ → cepte tuşa bir şey bastırıp ekrana dokunulmuş olabilir (kılıf, anahtar vb.)." : ""));
            } else {
                v.add("DÜZGÜN (YAZILIMLA) KAPANMA: Telefon sistem tarafından düzgünce " + (reboot ? "yeniden başlatıldı" : "kapatıldı")
                        + ", ama güç menüsü açıldığı görülmedi. Olasılıklar: zamanlanmış açma/kapama ayarı, otomatik yeniden başlatma (ör. Samsung 'Otomatik optimizasyon'), sistem güncellemesi, "
                        + "yetkili bir uygulama/yönetici, ya da güç tuşu + ses tuşu kombinasyonu. 'Şüpheli uygulamaları tara' ve yukarıdaki uygulama hareketlerine bakın.");
            }
            v.add(reboot ? "Telefon " + Store.dur(gap) + " içinde tekrar açıldı → YENİDEN BAŞLATMA." :
                    "Telefon " + Store.dur(gap) + " kapalı kaldı → tamamen KAPANDI, sonra elle açıldı.");
        } else {
            v.add("ANİ KAPANMA: Sistem kapanma sinyali GÖNDERMEDİ. Yani telefon düzgünce kapatılmadı; elektriği kesilmiş gibi aniden kapandı. "
                    + "Bu durumda bir UYGULAMANIN ya da güç menüsünün kapatma ihtimali çok düşüktür.");
            if (batt >= 0 && batt <= 5) {
                v.add("Pil çok düşüktü (%" + batt + "). Pil yıpranmış olabilir: yüzde yanlış gösteriliyor ve voltaj aniden çöküyor.");
            } else if (thermal >= 4 || temp >= 550) {
                v.add("Telefon çok sıcaktı → ısıdan acil kapanma.");
            } else {
                String vt = (volt > 0 && volt < 3550) ? " Son voltaj düşüktü (" + volt + " mV) → pil şüpheli." : "";
                v.add("Pil %" + (batt < 0 ? "?" : String.valueOf(batt)) + " iken kapandı." + vt + " Muhtemel nedenler: "
                        + "(1) pil temas/konnektör sorunu (darbe, cepte sıkışma, şişmiş pil), (2) yıpranmış pil (yük altında voltaj düşmesi), "
                        + "(3) güç tuşunun ~10 saniyeden uzun BASILI kalması (zorla yeniden başlatma - cepte sıkışan tuş), "
                        + "(4) sistem/çekirdek çökmesi, (5) anakart arızası.");
            }
            if (prox == 1) v.add("Kapanmaya yakın telefon CEPTEYDİ (yakınlık sensörü kapalı). Cepte basılı kalan güç tuşu veya baskı/darbe ihtimali artar.");
            if (hbTime > 0) v.add("Kesin kapanma anı " + Store.fmt(hbTime) + " ile " + Store.fmt(bootTime) + " arasında.");
        }
        if (lastAlive > 0 && (bootTime - lastAlive) > 6 * 3_600_000L && !graceful)
            v.add("Not: Son kayıttan bu yana çok uzun süre geçmiş; uygulama arka planda durdurulmuş olabilir. 'Pil kısıtlamasını kaldır' adımını yapın.");
        for (String s : v) r.append(" • ").append(s).append('\n');
        r.append("==================================\n");
        return r.toString();
    }

    // ---------- Açılış nedeni (sistem özellikleri) ----------

    private static Map<String, String> bootProps() {
        String[] keys = {"sys.boot.reason", "sys.boot.reason.last", "ro.boot.bootreason", "persist.sys.boot.reason",
                "persist.sys.boot.reason.history", "ro.boot.bootreason_history", "ro.boot.poweronreason",
                "ro.boot.pwronreason", "ro.boot.hw_reset_reason", "sys.powerctl"};
        Map<String, String> m = new LinkedHashMap<>();
        for (String k : keys) {
            String val = getprop(k);
            if (val != null && !val.isEmpty()) m.put(k, val.replace('\n', ' '));
        }
        return m;
    }

    static String getprop(String key) {
        try {
            java.lang.Process pr = Runtime.getRuntime().exec(new String[]{"getprop", key});
            try (BufferedReader br = new BufferedReader(new InputStreamReader(pr.getInputStream()))) {
                StringBuilder sb = new StringBuilder();
                String l;
                while ((l = br.readLine()) != null) sb.append(l).append(' ');
                return sb.toString().trim();
            }
        } catch (Exception e) {
            return null;
        }
    }

    private static String explainBootReason(String raw) {
        if (raw == null || raw.trim().isEmpty()) return null;
        String s = raw.toLowerCase(Locale.US);
        if (s.contains("battery") || s.contains("vbat") || s.contains("uvlo") || s.contains("low_batt"))
            return "'" + raw.trim() + "' → PİL kaynaklı (pil bitti/voltaj düştü).";
        if (s.contains("thermal") || s.contains("overtemp")) return "'" + raw.trim() + "' → AŞIRI ISINMA.";
        if (s.contains("panic") || s.contains("kernel")) return "'" + raw.trim() + "' → SİSTEM ÇEKİRDEĞİ ÇÖKTÜ (yazılım/donanım hatası).";
        if (s.contains("watchdog") || s.contains("wdog") || s.contains("wdt")) return "'" + raw.trim() + "' → SİSTEM DONDU, bekçi (watchdog) yeniden başlattı.";
        if (s.contains("longkey") || s.contains("long_press") || s.contains("longpress") || s.contains("keys_clear"))
            return "'" + raw.trim() + "' → GÜÇ TUŞU UZUN SÜRE BASILI KALDI (zorla yeniden başlatma).";
        if (s.contains("userrequested") || s.contains("user_requested")) return "'" + raw.trim() + "' → Kullanıcı/yazılım isteğiyle kapatıldı.";
        if (s.contains("ota") || s.contains("recovery")) return "'" + raw.trim() + "' → Sistem güncellemesi/kurtarma.";
        if (s.contains("hw_reset") || s.contains("hard") || s.contains("pmic") || s.contains("ocp")) return "'" + raw.trim() + "' → DONANIM sıfırlaması (güç devresi / ani elektrik kesilmesi).";
        if (s.contains("cold") || s.contains("powerkey") || s.contains("pwrkey")) return "'" + raw.trim() + "' → Telefon kapalıyken güç tuşuyla açıldı (öncesinde tamamen kapanmış).";
        return "'" + raw.trim() + "' (yorumlanamadı; araştırmak için bu metni kullanabilirsiniz).";
    }

    // ---------- Kullanım istatistikleri ----------

    static boolean hasUsageAccess(Context c) {
        try {
            AppOpsManager ao = (AppOpsManager) c.getSystemService(Context.APP_OPS_SERVICE);
            int mode = Build.VERSION.SDK_INT >= 29
                    ? ao.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), c.getPackageName())
                    : ao.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), c.getPackageName());
            return mode == AppOpsManager.MODE_ALLOWED;
        } catch (Exception e) {
            return false;
        }
    }

    private static String usageBefore(Context c, long from, long to) {
        if (!hasUsageAccess(c)) return "   (Kullanım erişimi izni verilmemiş - uygulamadaki butondan izin verin)\n";
        try {
            UserManager um = (UserManager) c.getSystemService(Context.USER_SERVICE);
            if (!um.isUserUnlocked()) return "   (Ekran kilidi açılmadığı için okunamadı)\n";
            UsageStatsManager usm = (UsageStatsManager) c.getSystemService(Context.USAGE_STATS_SERVICE);
            UsageEvents ev = usm.queryEvents(from, to);
            UsageEvents.Event e = new UsageEvents.Event();
            List<String> lines = new ArrayList<>();
            String lastFg = null;
            long lastFgTime = 0;
            String fgAtShutdown = null;
            while (ev.hasNextEvent()) {
                ev.getNextEvent(e);
                int t = e.getEventType();
                String what = null;
                switch (t) {
                    case 1: what = "ön plana geldi: " + label(c, e.getPackageName()); lastFg = e.getPackageName(); lastFgTime = e.getTimeStamp(); break;
                    case 15: what = "EKRAN açıldı"; break;
                    case 16: what = "EKRAN kapandı"; break;
                    case 17: what = "kilit ekranı göründü"; break;
                    case 18: what = "kilit açıldı"; break;
                    case 26: what = "⚠ CİHAZ KAPANIYOR (sistem kaydı)"; fgAtShutdown = lastFg; break;
                    case 27: what = "CİHAZ AÇILDI (sistem kaydı)"; break;
                    case 19: what = "ön plan servisi başladı: " + label(c, e.getPackageName()); break;
                    default: break;
                }
                if (what != null) lines.add("   " + Store.fmt(e.getTimeStamp()) + "  " + what);
            }
            StringBuilder sb = new StringBuilder();
            int start = Math.max(0, lines.size() - 40);
            if (start > 0) sb.append("   ... (").append(start).append(" eski olay gizlendi)\n");
            for (int i = start; i < lines.size(); i++) sb.append(lines.get(i)).append('\n');
            if (lines.isEmpty()) sb.append("   (Bu aralıkta kayıtlı uygulama hareketi yok)\n");
            if (fgAtShutdown != null) sb.append("   → Kapanma anında açık uygulama: ").append(label(c, fgAtShutdown)).append('\n');
            else if (lastFg != null) sb.append("   → Kapanmadan önce en son açılan uygulama: ").append(label(c, lastFg))
                    .append(" (").append(Store.fmt(lastFgTime)).append(")\n");
            return sb.toString();
        } catch (Exception ex) {
            return "   (okunamadı: " + ex.getClass().getSimpleName() + ")\n";
        }
    }

    // ---------- DropBox (ADB ile READ_LOGS verilirse) ----------

    static boolean hasReadLogs(Context c) {
        return c.checkSelfPermission("android.permission.READ_LOGS") == PackageManager.PERMISSION_GRANTED;
    }

    private static String dropBox(Context c, long since) {
        if (!hasReadLogs(c)) return null;
        try {
            DropBoxManager db = (DropBoxManager) c.getSystemService(Context.DROPBOX_SERVICE);
            StringBuilder sb = new StringBuilder();
            long t = since;
            int n = 0;
            DropBoxManager.Entry en;
            while ((en = db.getNextEntry(null, t)) != null && n < 60) {
                t = en.getTimeMillis();
                String tag = en.getTag();
                boolean important = tag.startsWith("SYSTEM_") || tag.startsWith("system_server")
                        || tag.contains("kernel") || tag.contains("panic") || tag.contains("watchdog")
                        || tag.contains("restart") || tag.contains("thermal");
                sb.append("   ").append(Store.fmt(t)).append("  ").append(tag).append('\n');
                if (important) {
                    String txt = en.getText(1500);
                    if (txt != null) {
                        for (String l : txt.split("\n")) {
                            if (l.trim().isEmpty()) continue;
                            sb.append("      ").append(l.length() > 160 ? l.substring(0, 160) : l).append('\n');
                        }
                    }
                }
                en.close();
                n++;
            }
            if (n == 0) sb.append("   (kayıt yok)\n");
            return sb.toString();
        } catch (Exception e) {
            return "   (okunamadı: " + e.getClass().getSimpleName() + ")\n";
        }
    }

    // ---------- Şüpheli uygulama taraması ----------

    static String label(Context c, String pkg) {
        if (pkg == null) return "?";
        try {
            PackageManager pm = c.getPackageManager();
            ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
            return pm.getApplicationLabel(ai) + " (" + pkg + ")";
        } catch (Exception e) {
            return pkg;
        }
    }

    static String suspiciousApps(Context c) {
        StringBuilder sb = new StringBuilder();
        PackageManager pm = c.getPackageManager();

        sb.append("1) Cihaz yöneticisi yetkili uygulamalar (telefonu kilitleyebilir/silebilir):\n");
        try {
            DevicePolicyManager dpm = (DevicePolicyManager) c.getSystemService(Context.DEVICE_POLICY_SERVICE);
            List<ComponentName> admins = dpm.getActiveAdmins();
            if (admins == null || admins.isEmpty()) sb.append("   yok\n");
            else for (ComponentName cn : admins) sb.append("   • ").append(label(c, cn.getPackageName())).append('\n');
        } catch (Exception e) { sb.append("   okunamadı\n"); }

        sb.append("\n2) Erişilebilirlik izni olan uygulamalar (ekranda tıklama yapabilir, güç menüsünü açabilir):\n");
        try {
            String acc = Settings.Secure.getString(c.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (acc == null || acc.trim().isEmpty()) sb.append("   yok\n");
            else for (String s : acc.split(":")) {
                ComponentName cn = ComponentName.unflattenFromString(s);
                sb.append("   • ").append(cn != null ? label(c, cn.getPackageName()) : s).append('\n');
            }
        } catch (Exception e) { sb.append("   okunamadı\n"); }

        sb.append("\n3) Kapatma/yeniden başlatma izni İSTEYEN sistem dışı uygulamalar:\n");
        int cnt = 0;
        List<PackageInfo> pkgs;
        try {
            pkgs = pm.getInstalledPackages(PackageManager.GET_PERMISSIONS);
        } catch (Exception e) {
            pkgs = new ArrayList<>();
        }
        String[] danger = {"android.permission.REBOOT", "android.permission.SHUTDOWN", "android.permission.DEVICE_POWER",
                "android.permission.RECOVERY", "android.permission.WRITE_SECURE_SETTINGS"};
        for (PackageInfo pi : pkgs) {
            if (pi.applicationInfo == null || (pi.applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0) continue;
            if (pi.requestedPermissions == null) continue;
            List<String> hits = new ArrayList<>();
            for (int i = 0; i < pi.requestedPermissions.length; i++) {
                for (String d : danger) {
                    if (d.equals(pi.requestedPermissions[i])) {
                        boolean granted = pi.requestedPermissionsFlags != null
                                && (pi.requestedPermissionsFlags[i] & PackageInfo.REQUESTED_PERMISSION_GRANTED) != 0;
                        hits.add(d.substring(d.lastIndexOf('.') + 1) + (granted ? " [VERİLMİŞ!]" : " [verilmemiş]"));
                    }
                }
            }
            if (!hits.isEmpty()) {
                cnt++;
                sb.append("   • ").append(label(c, pi.packageName)).append(": ").append(String.join(", ", hits)).append('\n');
            }
        }
        if (cnt == 0) sb.append("   yok (iyi haber)\n");

        sb.append("\n4) Son 14 günde yüklenen/güncellenen sistem dışı uygulamalar (sorun bunlardan sonra mı başladı?):\n");
        long lim = System.currentTimeMillis() - 14L * 24 * 3_600_000L;
        List<PackageInfo> recent = new ArrayList<>();
        for (PackageInfo pi : pkgs) {
            if (pi.applicationInfo == null || (pi.applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0) continue;
            if (pi.lastUpdateTime >= lim) recent.add(pi);
        }
        recent.sort((a, b) -> Long.compare(b.lastUpdateTime, a.lastUpdateTime));
        if (recent.isEmpty()) sb.append("   yok\n");
        for (PackageInfo pi : recent) {
            boolean fresh = pi.firstInstallTime == pi.lastUpdateTime;
            sb.append("   • ").append(Store.fmt(pi.lastUpdateTime)).append("  ").append(fresh ? "YÜKLENDİ  " : "güncellendi ")
                    .append(label(c, pi.packageName)).append('\n');
        }

        sb.append("\nNot: Normal bir uygulama Android'de telefonu KAPATAMAZ; bunun için sistem imzası veya root gerekir. ")
                .append("Raporlarda 'ANİ KAPANMA' görüyorsanız sorun büyük olasılıkla pil/donanım ya da cepte basılı kalan güç tuşudur. ")
                .append("Ayrıca Ayarlar'da 'Zamanlanmış açma/kapama' ve 'Otomatik yeniden başlatma' seçeneklerini kontrol edin.\n");
        return sb.toString();
    }
}

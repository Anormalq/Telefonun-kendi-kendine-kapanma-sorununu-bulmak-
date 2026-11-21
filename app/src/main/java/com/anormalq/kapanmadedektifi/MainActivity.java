package com.anormalq.kapanmadedektifi;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

public class MainActivity extends Activity {

    private TextView status, reports, log, scan;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        ScrollView sv = new ScrollView(this);
        sv.setFitsSystemWindows(true);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(14);
        root.setPadding(pad, pad, pad, pad);
        sv.addView(root);

        TextView intro = text("Telefon kendi kendine kapandığında, tekrar açıldığında burada bir RAPOR çıkar: "
                + "kapanma düzgün mü (güç menüsü / yazılım) yoksa ani mi (pil, donanım, basılı kalan tuş) oldu, "
                + "o an pil, sıcaklık, cepte olup olmadığı ve hangi uygulamanın açık olduğu yazılır.\n\n"
                + "Kurulum: aşağıdaki 3 adımı bir kez yapın, sonra telefonu normal kullanın.", 15);
        root.addView(intro);

        status = text("", 14);
        status.setPadding(0, dp(10), 0, dp(6));
        root.addView(status);

        root.addView(button("1) İzlemeyi başlat", v -> startMonitoring()));
        root.addView(button("2) Kullanım erişimi izni ver (hangi uygulama açıktı?)", v ->
                safeStart(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))));
        root.addView(button("3) Pil kısıtlamasını kaldır (arka planda öldürülmesin)", v -> askBattery()));
        root.addView(button("Şüpheli uygulamaları tara", v -> {
            scan.setText(Analyzer.suspiciousApps(this));
            scan.setVisibility(View.VISIBLE);
        }));
        root.addView(button("Kayıtları paylaş / kaydet", v -> share()));
        root.addView(button("Yenile", v -> refresh()));
        root.addView(button("Tüm kayıtları sil", v -> new AlertDialog.Builder(this)
                .setMessage("Tüm kayıtlar ve raporlar silinsin mi?")
                .setPositiveButton("Sil", (d, w) -> { Store.clearAll(this); Store.log(this, "UYGULAMA", "Kayıtlar silindi"); refresh(); })
                .setNegativeButton("Vazgeç", null).show()));

        scan = mono();
        scan.setVisibility(View.GONE);
        scan.setBackgroundColor(Color.parseColor("#FFF8E1"));
        root.addView(scan);

        root.addView(header("KAPANMA RAPORLARI"));
        reports = mono();
        root.addView(reports);

        root.addView(header("OLAY KAYDI (son 400 satır)"));
        log = mono();
        root.addView(log);

        root.addView(header("İleri seviye (isteğe bağlı)"));
        root.addView(text("Bilgisayarınız varsa, USB hata ayıklamayı açıp şu komutu çalıştırırsanız uygulama sistemin "
                + "kendi çökme/açılış kayıtlarını da okuyabilir:\n\nadb shell pm grant " + getPackageName()
                + " android.permission.READ_LOGS\n\nAyrıca Xiaomi/Huawei/Oppo'da Ayarlar > Uygulamalar > Kapanma Dedektifi > "
                + "'Otomatik başlatma' iznini açın. Samsung'da 'Pil > Arka plan kullanım sınırları'ndan uygulamayı 'Asla uyutma' listesine ekleyin.", 13));

        setContentView(sv);
        startMonitoring();
    }

    @Override
    protected void onResume() {
        super.onResume();
        Analyzer.check(this, "uygulama açıldı");
        refresh();
    }

    private void startMonitoring() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission("android.permission.POST_NOTIFICATIONS") != 0) {
            requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 1);
        }
        try {
            MonitorService.start(this);
        } catch (Exception e) {
            Store.log(this, "SERVİS", "Başlatılamadı: " + e);
        }
        status.postDelayed(this::refresh, 600);
    }

    private void askBattery() {
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        if (pm.isIgnoringBatteryOptimizations(getPackageName())) {
            safeStart(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
            return;
        }
        Intent i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + getPackageName()));
        safeStart(i);
    }

    private void safeStart(Intent i) {
        try {
            startActivity(i);
        } catch (Exception e) {
            safeStartFallback();
        }
    }

    private void safeStartFallback() {
        try {
            startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName())));
        } catch (Exception ignored) {
        }
    }

    private void refresh() {
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        String s = "Durum:\n"
                + mark(MonitorService.running) + " İzleme servisi çalışıyor\n"
                + mark(Analyzer.hasUsageAccess(this)) + " Kullanım erişimi izni\n"
                + mark(pm.isIgnoringBatteryOptimizations(getPackageName())) + " Pil kısıtlaması kaldırıldı\n"
                + mark(Analyzer.hasReadLogs(this)) + " Sistem kayıtları (ADB, isteğe bağlı)\n"
                + "Son kayıt: " + Store.fmt(Store.prefs(this).getLong("hb_time", 0)) + "   |   " + Store.snap(this).battText();
        status.setText(s);
        String rep = Store.tail(Store.reportFile(this), 600);
        reports.setText(rep.isEmpty() ? "Henüz kapanma olmadı. Telefon kendi kendine kapanıp tekrar açıldığında rapor burada görünecek.\n"
                + "(Denemek için telefonu bir kez kapatıp açabilirsiniz.)" : rep);
        log.setText(Store.tail(Store.logFile(this), 400));
    }

    private void share() {
        String rep = Store.tail(Store.reportFile(this), 3000);
        String lg = Store.tail(Store.logFile(this), 2500);
        String all = "KAPANMA DEDEKTİFİ - " + Build.MANUFACTURER + " " + Build.MODEL + ", Android " + Build.VERSION.RELEASE
                + "\n\n### RAPORLAR\n" + rep + "\n\n### ŞÜPHELİ UYGULAMA TARAMASI\n" + Analyzer.suspiciousApps(this)
                + "\n\n### OLAY KAYDI\n" + lg;
        if (all.length() > 400_000) all = all.substring(all.length() - 400_000);
        Intent i = new Intent(Intent.ACTION_SEND);
        i.setType("text/plain");
        i.putExtra(Intent.EXTRA_SUBJECT, "Kapanma Dedektifi kayıtları");
        i.putExtra(Intent.EXTRA_TEXT, all);
        startActivity(Intent.createChooser(i, "Kayıtları paylaş"));
    }

    private static String mark(boolean ok) { return ok ? "✅" : "❌"; }

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    private TextView text(String s, int sp) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextIsSelectable(true);
        return t;
    }

    private TextView header(String s) {
        TextView t = text(s, 16);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setPadding(0, dp(18), 0, dp(6));
        return t;
    }

    private TextView mono() {
        TextView t = text("", 11);
        t.setTypeface(Typeface.MONOSPACE);
        t.setPadding(dp(6), dp(6), dp(6), dp(6));
        return t;
    }

    private Button button(String s, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(s);
        b.setAllCaps(false);
        b.setOnClickListener(l);
        return b;
    }
}

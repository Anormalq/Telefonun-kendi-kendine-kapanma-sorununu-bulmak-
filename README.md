# Kapanma Dedektifi 🔍📱

Telefon cepteyken kendi kendine kapanıyorsa **neden** kapandığını bulmak için Android uygulaması.

## İndir

👉 **[KapanmaDedektifi.apk (son sürüm)](https://github.com/Anormalq/telefonun-kendi-kendine-kapanma-sorununu-bulmak-/releases/latest/download/KapanmaDedektifi.apk)**

Telefondan bu bağlantıya dokunun, inen dosyayı açın, "bilinmeyen kaynaklardan yükleme" iznini verin ve kurun.

## Kurulumdan sonra (bir kez)

1. **İzlemeyi başlat** (bildirim iznine "İzin ver" deyin)
2. **Kullanım erişimi izni ver** → listeden *Kapanma Dedektifi*'ni açın
3. **Pil kısıtlamasını kaldır** → "İzin ver"
4. Xiaomi / Huawei / Oppo: Ayarlar'dan **Otomatik başlatma**'yı açın. Samsung: *Pil → Arka plan kullanım sınırları → Asla uyutma* listesine ekleyin.

Sonra telefonu normal kullanın. Telefon kendi kendine kapanıp tekrar açıldığında uygulamada bir **KAPANMA RAPORU** çıkar.

## Ne kaydediyor?

| Kayıt | Neden önemli |
|---|---|
| Sistemin kapanma sinyali (`ACTION_SHUTDOWN`) geldi mi | Geldiyse telefon **yazılımla düzgün** kapatıldı (güç menüsü, sistem, zamanlayıcı). Gelmediyse **ani kapanma** → pil / donanım / basılı kalan tuş |
| Güç menüsünün açılması | Güç tuşuna basılı tutulup "Kapat" mı seçildi? |
| Yakınlık + ışık sensörü | Kapanırken telefon **cepte** miydi? |
| Ekran açılma/kapanma, kilit açma | Cepte ekranın kendi kendine açılıp açılmadığı |
| Pil %, sıcaklık, voltaj, şarj, ani pil düşüşleri | Pil bitmesi, yıpranmış pil, ısınma |
| Telefon ısı durumu | Isıdan kapanma |
| Kapanmadan önce açık olan uygulamalar | Bir uygulama mı tetikledi? |
| Sistemin açılış nedeni (`sys.boot.reason` vb.) | Bazı telefonlarda "kernel_panic", "battery", "longkey" gibi kesin neden yazar |
| Uygulama yükleme/güncelleme/kaldırma | Sorun bir uygulamadan sonra mı başladı? |
| Şüpheli uygulama taraması | Cihaz yöneticisi, erişilebilirlik, yeniden başlatma izni isteyen uygulamalar |
| Uçak modu, güç tasarrufu, derin uyku, saat değişimi | Bağlam |

Kayıtlar **"Kayıtları paylaş"** butonuyla WhatsApp, e-posta, Drive vb. yere gönderilebilir.

## Raporu nasıl okumalı?

- **ANİ KAPANMA (sinyal yok)** → Telefonu bir uygulama kapatmadı. En olası: pil teması/yıpranmış pil, cepte ~10 sn basılı kalan güç tuşu, sistem çökmesi, donanım.
- **GÜÇ TUŞU (güç menüsü açıldı)** → Cepte tuşa basılmış ve menüden kapatılmış.
- **DÜZGÜN KAPANMA, güç menüsü yok** → Zamanlanmış kapatma, otomatik yeniden başlatma, güncelleme ya da yetkili bir uygulama. Şüpheli uygulama taramasına bakın.
- **PİL BİTTİ / AŞIRI ISINMA** → Adından belli.

## İleri seviye (isteğe bağlı)

Bilgisayardan ADB ile şu izni verirseniz sistemin kendi çökme/açılış kayıtları da (DropBox) rapora eklenir:

```
adb shell pm grant com.anormalq.kapanmadedektifi android.permission.READ_LOGS
```

## Derleme

GitHub Actions her push'ta APK'yı derleyip **Releases** bölümüne koyar. Yerelde: `gradle :app:assembleDebug` (Android SDK gerekir).

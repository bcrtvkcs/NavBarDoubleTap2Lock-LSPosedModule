# NavBar DoubleTap2Lock

Navigasyon cubugunun herhangi bir yerine cift tiklayarak ekrani kapatan LSPosed / Xposed moduludur.

## Nasil Calisir?

Modul, `com.android.systemui` icindeki `NavigationBarView.dispatchTouchEvent` metodunu hooklar. Dokunma olaylarini izleyerek ard arda gelen iki hizli tiklamayi (double tap) algilar ve ekrani kapatir.

### Gezinme Moduna Gore Davranis

| Gezinme Modu | Durum | Sonuc |
|---|---|---|
| 3 dugmeli gezinme | - | Her zaman aktif |
| 2 dugmeli gezinme | - | Her zaman aktif |
| Hareketle gezinme | Ipucu cizgisi **gorunur** | Aktif |
| Hareketle gezinme | Ipucu cizgisi **gizli** | Devre disi |

Hareketle gezinmede ipucu cizgisi (en alttaki ince beyaz cizgi) gizlendiginde, dokunuslar navigasyon cubugundan gecip alttaki uygulamaya iletilir. Bu durumda moduldeki cift tiklama ozelligi devre disi kalir.

## Gereksinimler

- Android 10+ (API 29)
- Root erisimi (Magisk / KernelSU)
- LSPosed veya Xposed Framework

## Kurulum

### 1. Projeyi Derleme

```
XposedBridgeAPI-89.jar dosyasini app/lib/ klasorune koyun.
```

JAR dosyasini [XposedBridge releases](https://github.com/rovo89/XposedBridge/releases) sayfasindan veya mevcut bir Xposed modul projesinden alabilirsiniz.

Ardindan projeyi Android Studio ile acin ve derleyin:

```
Build > Build Bundle(s) / APK(s) > Build APK(s)
```

### 2. APK Yukleme

Derlenen APK dosyasini cihaza yukleyin.

### 3. Modul Etkinlestirme

1. LSPosed Manager'i acin
2. Modul listesinden **NavBar DoubleTap2Lock** modulunu bulun ve etkinlestirin
3. Kapsam (scope) olarak **System UI** secili oldugundan emin olun
4. Cihazi yeniden baslatin

## Proje Yapisi

```
app/src/main/
├── assets/
│   └── xposed_init                       # Modul giris noktasi
├── java/com/navbardoubletap2lock/
│   └── MainHook.java                     # Tum hook ve mantik kodu
├── res/values/
│   └── strings.xml                       # Modul adi, aciklama, scope
└── AndroidManifest.xml                   # Xposed metadata
```

## Teknik Detaylar

### Cift Tiklama Algilama

- `ACTION_DOWN` ile parmak pozisyonu ve zamani kaydedilir
- `ACTION_UP` geldiginde sure (`< 300ms`) ve mesafe (`< 100px`) kontrol edilir; kosullari sagliyorsa gecerli bir tiklama sayilir
- Iki ardisik tiklama arasi sure `ViewConfiguration.getDoubleTapTimeout()` degerini asmiyorsa cift tiklama onaylanir
- Kaydirma (swipe) ve uzun basma hareketleri otomatik olarak filtrelenir

### Gezinme Modu Tespiti

Sistem kaynagindan `config_navBarInteractionMode` degeri okunur (SystemUI'nin kendi kullandigi yontem). Okunamazsa `Settings.Secure "navigation_mode"` degerine bakilir.

- `0` = 3 dugmeli gezinme
- `1` = 2 dugmeli gezinme
- `2` = Hareketle gezinme

### Ipucu Cizgisi Gorunurlugu

Hareketle gezinme modunda, navigasyon cubugu icindeki ipucu cizgisi gorunurlugu iki yontemle kontrol edilir:

1. `home_handle` resource ID ile view aranir
2. Bulunamazsa `NavigationHandle` sinif adiyla recursive arama yapilir

Bulunan view'in hem `visibility == VISIBLE` hem de `alpha > 0` olmasi gerekir.

### Ekran Kapatma

SystemUI prosesi `DEVICE_POWER` iznine sahip oldugu icin, `PowerManager.goToSleep()` metodu reflection ile cagrilarak ekran kapatilir.

## Uyumluluk

| Android Surumu | NavigationBarView Yolu |
|---|---|
| 12+ (S) | `com.android.systemui.navigationbar.NavigationBarView` |
| 10-11 (Q, R) | `com.android.systemui.statusbar.phone.NavigationBarView` |

Modul her iki sinif yolunu da sirayla dener; hangisi bulunursa onu hooklar.

## Lisans

GPL-3.0

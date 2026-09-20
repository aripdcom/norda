# Norda — Gizlilik Politikası

English (canonical): [../PRIVACY.md](../PRIVACY.md)

_Son güncelleme: 2026-08-25_

Norda, çevrimdışı çalışmak üzere tasarlanmış bir outdoor navigasyon
uygulamasıdır. Gizlilik duruşu tek cümledir: **konum veriniz cihazınızda
kalır.**

- **Toplanan veri:** Kayıt sırasında GPS konumları, rakım ve pil yüzdesi
  yalnızca cihazdaki SQLite veritabanına yazılır. Hesap yoktur, bulut
  yoktur, telemetri/analitik/reklam SDK'sı yoktur.
- **Ağ erişimi:** Uygulamada ağa dokunan tek bileşen harita paketi
  indiricisidir (`TileDownloader`); yalnızca GitHub Releases üzerinden
  yayımlanan harita paketlerini ve paket listesini indirir. Bu isteklerde
  kimlik, konum ya da kullanım verisi gönderilmez. Kayıt, pusula,
  navigasyon ve GPX tamamen çevrimdışı çalışır.
- **İzinler:** Konum (kayıt ve pusula için), bildirim (kayıt sürerken
  kalıcı bildirim için). İkisi de yalnız bu amaçlarla kullanılır.
- **Paylaşım:** Veri ancak siz dışa aktarırsanız (GPX) cihazdan çıkar ve
  nereye gideceğini siz seçersiniz.
- **Silme:** Aktiviteler ve noktalar uygulama içinden silinebilir;
  uygulamayı kaldırmak tüm veriyi kaldırır.

İletişim: <https://github.com/aripdcom/norda/issues>

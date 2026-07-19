# Değişiklik Günlüğü

[Keep a Changelog](https://keepachangelog.com/tr/1.1.0/) formatı ·
[Semantic Versioning](https://semver.org/lang/tr/)

> Bu dosya **sizin projenizin** günlüğüdür. Şablonun kendi inşa süreci
> `../history/CHANGELOG-template-build.md` altında arşivlendi — orada gördüğünüz faz/slice
> kavramları sizi bağlamaz.

## [Yayınlanmamış]

### Eklendi
### Değişti
### Kaldırıldı
### Düzeltildi
### Güvenlik

---

## Şablon temeli — 2026-07-19

Klonladığınız temel bu. Aşağıdakiler şablonun kendi sertleştirme turlarının **sonucudur**;
kendi günlüğünüzü yukarıya yazın.

### Güvenlik

- **Oturum iptali ve impersonation ticket'ı artık sahibine bağlı.** `logout`, sunulan refresh
  token'ın çağıranın kendi token'ı olduğunu doğruluyor; **statü bilerek 204'te bırakıldı** —
  403/404 dönmek ucu bir *varlık oracle*'ına çevirirdi (statü farkı "bu string canlı bir refresh
  token'dır" bilgisini onaylar). Ayrım operatörün göreceği WARN satırına taşındı.
  `ImpersonationTokenStore.consume(String, Long callerUserId)` — aktör bir **parametre**, ticket'tan
  okunan alan değil, yani bağ çağrı yerinde **unutulamaz**. Yanlış aktör ticket'ı **yakmıyor**;
  aksi hâlde sızan bir ticket meşru hand-off'a DoS'a dönerdi.
- **Export'lar sınırlı.** `/api/users/export` ve `/api/audit-logs/export` tüm scope'unu tek listeye
  çekiyordu; audit tablosu her servis edilen istekle büyüdüğü için ikincisi daha ağırdı. İkisi de
  ortak `BoundedExport` üzerinden `maxRows+1` çekiyor ve sınır aşılırsa **reddediyor** (400
  ProblemDetail), **kesmiyor** — sessizce kısaltılmış bir export, tam görünen ve olmayan bir
  dosyadır. `zero.export.max-rows` (varsayılan 10 000) boot'ta doğrulanıyor: `0` ile kurulum
  **açılmıyor**, her export'ta 500 üretmiyor.
- **Korumasız 6 handler'a yetki beyanı eklendi.** ArchUnit Rule 5 donmuş 6 → 0.
- **İzin dizgeleri artık sabitlerle yazılıyor.** 31 ham `hasAuthority('...')` literali
  `AppPermissions` (ve modül sahipli `AuditPermissions` / `SettingsPermissions` /
  `TenantPermissions` / `SaasPermissions`) sabitlerine taşındı. Yazım hatası içeren bir literal
  derlenir, testten geçer ve endpoint'i **sonsuza dek 403**'te bırakır; hiçbir katman yakalamaz.
  ArchUnit kuralı yeni literalleri build zamanında reddediyor.
- **Kiracı filtresi üç entity'ye daha uygulandı** (`AuditLog`, `EntityChange`,
  `UserNotification`). `hostFilter` bilinçli olarak **eklenmedi**: host'un kiracılar arası audit
  incelemesi bir ürün özelliği, filtre onu kırardı.
- **`roles.manage` kaldırıldı.** Seeder her Admin rolüne veriyordu ama izin ağacında, iki mesaj
  paketinde, hiçbir `@PreAuthorize`'da ve frontend'de yoktu — hiçbir şeyi korumayan, ekranda
  görünmeyen ve geri alınamayan bir grant. `V7__drop_dead_roles_manage_permission.sql` bayat
  satırları temizliyor.

### Düzeltildi

- **`/api/users` ve `/api/roles` artık veritabanında sayfalıyor.** `@EntityGraph` + `Pageable`
  birlikte kullanıldığında Hibernate tüm satırları çekip bellekte diliyordu
  (`HHH90003004`) — 5 kayıtta görünmez, 50 binde heap uçurumu. İki aşamalı sorguya geçildi
  (id sayfası → fetch join), sayfa sırası açıkça geri yükleniyor.
- E-postalardaki bağlantıların tabanı (`zero.app.base-url`) `localhost:4200`'ü işaret ediyordu —
  Angular'ın portu. Frontend Vite/5173'te olduğu için geliştirmede gönderilen her şifre sıfırlama
  ve doğrulama bağlantısı ölü bir porta gidiyordu.

### Eklendi

- **ArchUnit cırcırı (5 kural), donmuş ihlal 58 → 0.** Mevcut ihlaller donduruldu, **yeni** ihlal
  build'i kırar; liste düzeltildikçe küçülür ve geri büyüyemez. Kuralların ikisi bytecode'da ifade
  edilemediği için (`javac` sabit katlaması, anotasyonsuz `package-info`'nun `.class` üretmemesi)
  `.java` kaynağını okuyor — kaynak kökü bulunamazsa **fırlatıyor**, "ihlal yok" demiyor.
  Rule 4 ayrıca yeniden formüle edildi (ADR-0016): entity'nin **kendi** paketinde dosya aramak
  yerine modül köküne yukarı yürüyüp `@ApplicationModule` **beyanı** arıyor — eski hâli, koruduğunu
  iddia ettiği beyan silindiğinde yeşil kalıyordu.
- **Export'ların sınırının SQL'e indiğini tutan test.** Bir davranış testi bunu göremez: `Pageable`'ı
  yok sayıp tüm satırları okuyan ve sınırı Java'da uygulayan bir fetcher dışarıdan **birebir aynı**
  davranır. `ExportRowBoundIT`, `org.hibernate.SQL`'e `ListAppender` bağlayıp sorgunun satır limiti
  taşıdığını doğruluyor — önünde bir vacuity guard var, sıfır statement yakalanırsa test kendini düşürüyor.
- Test profilinde `hibernate.query.fail_on_pagination_over_collection_fetch` — ArchUnit'in
  göremediği `join fetch` + `Pageable` şeklini de kapatır.
- Hesap ekranları: şifremi unuttum/sıfırla, profil + şifre değiştirme, e-posta doğrulama,
  kiracı yönetimi.

### Bilinen kısıtlar

`RISK-REGISTER.md` → "Şablonu klonluyorsanız — devraldığınız açık kısıtlar".

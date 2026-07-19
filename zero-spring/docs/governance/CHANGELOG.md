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

- **ArchUnit cırcırı (5 kural).** Mevcut ihlaller donduruldu, **yeni** ihlal build'i kırar; liste
  düzeltildikçe küçülür ve geri büyüyemez. Kuralların ikisi bytecode'da ifade edilemediği için
  (`javac` sabit katlaması, anotasyonsuz `package-info`'nun `.class` üretmemesi) `.java` kaynağını
  okuyor — kaynak kökü bulunamazsa **fırlatıyor**, "ihlal yok" demiyor.
- Test profilinde `hibernate.query.fail_on_pagination_over_collection_fetch` — ArchUnit'in
  göremediği `join fetch` + `Pageable` şeklini de kapatır.
- Hesap ekranları: şifremi unuttum/sıfırla, profil + şifre değiştirme, e-posta doğrulama,
  kiracı yönetimi.

### Bilinen kısıtlar

`RISK-REGISTER.md` → "Şablonu klonluyorsanız — devraldığınız açık kısıtlar".

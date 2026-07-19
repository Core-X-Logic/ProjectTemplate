# Üçüncü taraf bileşenler ve lisans notları

Bu şablonu klonlamadan önce okuyun. İki kalem, kendi lisansınızdan bağımsız olarak sizi bağlar.

## Metronic (ticari — ayrıca satın alınmalı)

Frontend'in görsel katmanı Metronic yönetim panosu temasından uyarlanmıştır.

- **Metronic ücretli bir üründür.** Bu depo onu **dağıtmaz**: `zero-spring/frontend/vendor/`
  dizini `.gitignore` ile hariç tutulmuştur ve klonunuzda **bulunmaz**
  (`git check-ignore -v zero-spring/frontend/vendor` ile doğrulayabilirsiniz).
- `zero-spring/frontend/app/` altındaki ürün kodu Metronic'ten **uyarlanmış** bileşenler
  içerebilir. Bu şablonu ticari bir üründe kullanacaksanız **kendi Metronic lisansınızı
  edinmeniz gerekir**.
- Alternatif: `app/` altındaki bileşenler Tailwind 4 + shadcn/ui üzerine kuruludur; Metronic'e
  özgü görsel varlıkları (logo, ikon setleri, tema CSS'i) kendi tasarımınızla değiştirerek
  bağımlılığı kaldırabilirsiniz. `frontend/app/public/media/app/` altındaki marka varlıklarının
  tamamı değiştirilmelidir — bunlar örnek amaçlıdır.

**Bu bir hukuki görüş değildir.** Ticari kullanımda lisans durumunuzu Metronic'in kendi
şartlarından doğrulayın.

## Açık kaynak bağımlılıklar

Backend ve frontend bağımlılıkları kendi lisanslarıyla gelir (çoğunlukla Apache-2.0 ve MIT).
Tam liste:

```bash
cd zero-spring/backend && ./mvnw license:add-third-party
cd zero-spring/frontend/app && npx license-checker --summary
```

Dikkat edilmesi gerekenler:

| Bileşen | Lisans | Not |
|---|---|---|
| Spring Boot, Spring Modulith | Apache-2.0 | — |
| Hibernate ORM | LGPL-2.1 / Apache-2.0 çift lisans | Kütüphane olarak kullanım serbest |
| PostgreSQL JDBC | BSD-2-Clause | — |
| Flyway (Community) | Apache-2.0 | Teamsürümü ayrı lisans ister |
| Bucket4j | Apache-2.0 | — |
| React, Vite, Tailwind | MIT | — |

## Bu şablonun kendisi

`LICENSE` dosyasına bakın. Varsayılan olarak **özel mülk (proprietary)** işaretlenmiştir; kendi
projeniz için değiştirmek isterseniz o dosyayı değiştirin — bu şablonun size verdiği haklar
sizin deponuzda geçerli olan lisansı belirlemez.

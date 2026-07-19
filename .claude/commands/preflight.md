---
description: Push etmeden önce yerel kapıları koştur (CI dakikası harcamadan)
---

Push etmeden önce yerel doğrulamayı koştur. Amaç: CI dakikası harcamadan, CI'ın yakalayacağı
şeyi burada yakalamak.

Bunun var olma sebebi ölçülmüş: bu deponun ilk üç CI koşusunun ikisi **CI-config hatasıyla**
düştü ve ikisi de burada, saniyeler içinde ve bedavaya yakalanabilirdi.

## Sırayla koş, ilk kırmızıda dur

1. **Bağımlılıklar ayakta mı**
   ```
   cd zero-spring/backend && docker compose up -d
   ```

2. **Backend — `clean` ZORUNLU**
   ```
   cd zero-spring/backend && ./mvnw -B -ntp clean verify
   ```
   `clean` olmadan Maven, geriye giden bir dosya zaman damgasında derlemeyi atlar ve **bayat
   bytecode** test eder; bu hem yanlış yeşil hem yanlış kırmızı üretir. Test sayısını raporla.

3. **Frontend**
   ```
   cd zero-spring/frontend/app && npm run build && npm run test
   ```

4. **Typed client drift** — backend `dev` profilinde ayaktayken:
   ```
   npm run gen:api && git diff --stat -- src/api/schema.d.ts
   ```
   Çıktı boş değilse: backend sözleşmesi değişmiş ama client yeniden üretilmemiş. Üretilen
   dosyayı **commit'e dahil et**. (Frontend bayat tiplere karşı sorunsuz derlenir; hata
   yalnızca üretimde, o uç çağrıldığında çıkar.)

5. **CI gate'lerinin yerel karşılığı**
   ```
   bash zero-spring/scripts/ci-local.sh
   ```
   Tek gate için: `readiness` | `smoke` | `secrets` | `migration`.

## Sonra

Değişiklik backend ya da frontend kodu içeriyorsa **`stack-reviewer`** ajanını çalıştır.
Yeni bir test ya da CI gate'i eklediysen/değiştirdiysen **`gate-auditor`** ajanını çalıştır —
gate'in gerçekten kırmızıya döndüğü kanıtlanmadan eklenmiş sayılmaz.

## Rapor

Şu formatta özetle, tahmin yok:
- her adım: geçti/kaldı + ölçülen sayı (test adedi, drift satırı, gate sonucu)
- kaldıysa: hangi adım, tam hata satırı, ve düzeltme önerisi
- kapanış: **push'a hazır** ya da **push etme** — muğlak bırakma

Bu akışın kanıtlayamadığı şeyi de söyle: runner Linux, burası Windows olabilir; ve bu akış
tam CI zincirinin yerine geçmez.

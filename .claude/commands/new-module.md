---
description: Yeni bir modül ekle — korumaları unutmadan (kiracılık, izin, i18n, test)
argument-hint: <modül-adı> (örn. invoices)
---

Yeni modül ekle: **$1**

Şablonun birincil kullanım senaryosu bu ve en çok hata buradan çıkar: modülün kendisi çalışır
görünürken **korumalardan biri unutulur** ve hiçbir şey bunu söylemez. Aşağıdaki her adım,
unutulduğunda sessiz kalan bir korumadır.

## Önce oku

`zero-spring/docs/ADDING-A-MODULE.md` — tam yordam orada, gerçek dosya yolları ve sınıf
adlarıyla. Bu komut o dokümanın **kontrol listesi**dir, kopyası değil. Çelişki varsa doküman
kazanır; ayrıca çelişkiyi bana bildir.

## Sıra — atlanan her madde sessiz bir açıktır

1. **Modül sınırı.** `zero-spring/backend/src/main/java/.../$1/` altında `package-info.java`
   yaz ve dışarı açılacak tipleri `@NamedInterface` ile işaretle.
   ⚠️ `ModularityTests.verify()` `package-info` **yazmayan** modülü yeşil geçirir
   (`allowedDependencies` varsayılanı OPEN). Yani bu adımı test zorlamıyor — **sen sorumlusun**.

2. **Şema.** Yeni bir `V<n>__$1.sql`. Var olan bir migration'ı **düzenleme** — mevcut
   kurulumlarda checksum hatası verir ve Flyway açılışta patlar.
   Çok kiracılıysa: `tenant_id` kolonu + uygun index.

3. **Entity.** `tenant_id` varsa Hibernate `@Filter(name="tenantFilter")` **uygula**. Unutmak
   kiracılar arası sızıntı üretir ve pozitif testler bunu yakalamaz.

4. **Repository.** `@EntityGraph` (ya da `join fetch`) ile `Pageable`/`Page<>` **aynı metotta
   kullanılmaz** — Hibernate tüm satırları çekip bellekte diler (`HHH90003004`). İki aşamalı
   sorgu ya da `@BatchSize` kullan.

5. **İzinler.** `AppPermissions` sabiti ekle → `PermissionDefinitions` ağacına doğru `Side`
   (HOST/TENANT/BOTH) ile kaydet. Ham string yazma.

6. **Yetkilendirme — üçlü kilit.** Backend `@PreAuthorize(AppPermissions.X)` **+** frontend
   `<Can permission=...>` **+** route guard. Üçünden biri eksikse kilit yoktur.

7. **i18n.** Backend `messages_en.properties` + `messages_tr.properties`, frontend
   `messages/en.ts` + `tr.ts`. Yaprak anahtarların çözüldüğünü doğrula.

8. **Frontend feature.** `frontend/app/src/features/$1/` — `api/ hooks/ types/ messages/ pages/
   components/ __tests__/`. Mevcut bir feature'ı örnek al (`editions` iyi bir örnektir).

9. **Testler.**
   - En az 1 backend IT: mutlu yol **+ negatif yetki testi** (yetkisiz çağıran 403).
   - Çok kiracılıysa: kiracılar arası erişim **negatif** testi. Bir izolasyon açığı 200 döner
     ve yalnızca negatif test yakalar.
   - En az 1 frontend davranış testi.

10. **Typed client.** Backend `dev` profilinde ayaktayken `npm run gen:api`; üretilen
    `schema.d.ts`'i commit'e dahil et.

## Bitirmeden önce

```
cd zero-spring/backend && ./mvnw -B -ntp clean verify
cd zero-spring/frontend/app && npm run build && npm run test
```

Sonra **`stack-reviewer`** ajanını çalıştır. Yeni bir test/gate eklediysen **`gate-auditor`**
ajanını da çalıştır.

## Kapanış raporu

Yukarıdaki 10 maddeyi tek tek işaretle: yapıldı / gerekmedi (**gerekçesiyle**) / atlandı.
"Gerekmedi" diyorsan sebebini yaz — en sık hata, çok kiracılı bir entity'yi tek kiracılı sanmaktır.
Kanıtsız "tamamlandı" yok: hangi test geçti, kaç test koştu.

---
name: tenant-isolation
description: Çok kiracılı veri izolasyonu kuralları — tenant filtresi, JWT claim otoritesi, host/tenant sınırı, negatif testler. Kiracıya ait veri okuyan/yazan her entity, repository veya uç eklendiğinde yükle.
---

# Kiracı izolasyonu

Bu, bu depodaki **en sessiz** hata sınıfı. Bir izolasyon açığı hata vermez — **200 döner** ve
yanlış kiracının verisini gösterir. Pozitif testler bunu asla yakalamaz, çünkü onlar da 200
bekler.

## Model

Paylaşımlı şema + `tenant_id` ayırıcı. `TenantContext` isteğe bağlı kiracıyı taşır; Hibernate
`@Filter` sorgulara `tenant_id = :tenantId` ekler.

**Otorite JWT claim'idir, header değil.** İki filtre sırayla çalışır:
1. `TenantResolverFilter` — `X-Tenant` header'ından bağlam kurar (kimliksiz login için gerekli)
2. `AuthenticatedTenantFilter` — kimlik doğrulanmışsa JWT `tenant` claim'i **otoriterdir**;
   header claim'le çelişirse **403**

Bu sıra pazarlık dışı. Yalnız header'a güvenmek, kullanıcının kendi kiracısını seçmesi demektir
— bu tam olarak Faz 1'de bulunan ve düzeltilen açıktı.

## Yeni entity eklerken

1. Tabloda `tenant_id` kolonu + uygun index.
2. Entity sınıfına Hibernate `@Filter(name = "tenantFilter", condition = "tenant_id = :tenantId")`.
3. Host tarafı da erişiyorsa `hostFilter` karşılığını değerlendir.

⚠️ **Uyarı:** bu depoda `tenant_id` taşıyan 17 entity'nin hepsinde `@Filter` **yok**
(`AuditLog`, `EntityChange`, `UserNotification`, `TenantFeature`, `Subscription` eksik —
`PROD-R21` civarı kayıtlı borç). Yani **"diğerleri nasıl yapmış" güvenilir bir örnek değil.**
Kuralı çoğunluktan değil `ADR-0003`'ten al.

## Filtreyi atlayan yollar — bilerek kontrol et

- `@Query` ile ham SQL / native query
- `EntityManager`'ı doğrudan kullanmak
- Host bağlamında koşan servisler (`tenantId = null`) — kasıtlı mı?
- Arka plan işleri ve scheduler'lar: hangi bağlamda koşuyorlar?
- `saas` modülü: burada `@Filter` **yok** ve bu bilinçli bir karar (`ADR-0015`). Karşılığında
  **her uç için negatif yetki testi zorunlu**.

## Negatif test — zorunlu

Her kiracıya-ait uç için, mutlu yolun yanında:

```
A kiracısının token'ı ile B kiracısının kaynağını iste → 403/404 olmalı, 200 OLMAMALI
Host token'ı + çelişen X-Tenant header'ı → 403
Kiracı token'ı ile host-only uç → 403
```

İyi örnekler: `TenantIsolationIT`, `TenantEscalationIT`.

Bir izolasyon testi yazdıysan **gate-auditor mantığını uygula**: filtreyi geçici kaldır,
testin gerçekten kırmızıya döndüğünü gör. Dönmüyorsa test yanlış şeyi ölçüyor.

## Host / tenant sınırı

- Host kullanıcısı `tenantId = null` taşır.
- Host-only izinler (`settings.host.manage`, `languages.manage`, `tenants.manage`,
  `editions.manage`) hiçbir kiracı rolüne verilemez.
- Yeni bir izin eklerken `PermissionDefinitions` içinde `Side` (HOST / TENANT / BOTH) doğru
  seçilmeli — yanlış seçim, kiracı adminine host yetkisi verir.

# Mimari Kurallar

Bu dosya, koddaki tasarım kararlarının **kalıcı gerekçelerini** tutar. Java yorumları bu
başlıklara ad ile atıf yapar (örn. "see ARCHITECTURE-RULES.md — Cache invalidation").

Kapsam ve okuma sırası:

- **Bu dosya** — "neden böyle yazıldı", ihlal edilirse ne kırılır. Şablonla birlikte taşınır.
- `governance/RISK-REGISTER.md` — açık/kapalı risk kayıtları (`PROD-R*` dahil), durumu olan
  canlı tablo. Bir kural burada, o kuralın *risk durumu* orada yaşar.
- `governance/ADR/` — tekil karar kayıtları. Kod yorumları ADR numaralarına doğrudan atıf
  yapmaya devam eder; ADR'ler şablonda kalır.

Kurallar bilinçli olarak **kod numarası içermez**. Bir kurala atıf yaparken başlığını yazın;
numara verirseniz numara taşındığında atıf ölür.

---

## 1. Modül bağımlılıkları döngü kurmaz

`identity` özellik (feature) sorgulamak için `saas`'a; `saas` ise izin sabitleri için
`identity`'ye ihtiyaç duyar. İkisi de doğrudan yapılırsa modulith döngüsü oluşur.

Çözüm, kodda uygulanan hali:

- `saas` yalnızca `saas :: api` **named interface**'i üzerinden tüketilir
  (`FeatureChecker`, `SubscriptionGuard`). Tüketiciler `edition` / `feature` /
  `subscription` iç paketlerine asla dokunmaz.
- SaaS izin sabitleri (`SaasPermissions`) `saas` modülünde durur, `identity`'de değil.
  İzin ağacına kayıt `identity` tarafında aynı string değerleri tekrarlayarak yapılır;
  iki listenin eşliğini `SaasPermissionsAlignmentTest` doğrular.
- `tenancy` **yaprak modüldür**. `tenancy -> saas` bağımlılığı yoktur; tenant oluşturma
  bir event (`TenantCreatedEvent`) ile duyurulur, `saas` dinler.

İhlal belirtisi: Spring Modulith doğrulama testi kırılır.

## 2. Feature ve abonelik cache'i yazmadan sonra bayat kalmamalı

Çözümlenmiş feature değeri ve tenant'ın abonelik geçerliliği cache'lenir. Bu cache'lerin
doğruluğu **TTL'den değil, yazma yolundaki açık eviction'dan** gelir; Redis TTL'i yalnızca
emniyet ağıdır.

Çözümlenmiş bir değeri değiştirebilen **her** yazma yolu cache'i komple düşürür:

1. Edition feature değişikliği (edition'ı miras alan tüm tenant'ları etkiler),
2. Tenant feature override'ı (yazma ve silme — override kaldırıldığında edition değeri
   yeniden görünür hale gelir),
3. Paket ataması / edition değişimi (hem miras alınan feature'ları hem abonelik durumunu
   değiştirir).

Eviction bilinçli olarak **kaba** (`allEntries`). Anahtar bazlı ince invalidation, bir
edition'a abone her tenant'ı ve ilgili her feature adını sayıp dökmeyi gerektirir; bu
sayımı yanlış yapmak **sessizce** bozar, cache'i düşürmek ise yalnızca yeniden hesaplama
maliyetidir.

İki cache birlikte düşürülmelidir; biri unutulduğunda tutarsızlık doğar. Bu yüzden çağrı
yerlerine iki ayrı `@CacheEvict` yazmak yerine tek bir bileşik anotasyon
(`@EvictsSaasCaches`) kullanılır.

Somut arıza senaryosu: host tenant'ın kullanıcı limitini yükseltir, ama tenant hâlâ eski
limite takılır — çünkü limit bayat cache'ten okunur.

## 3. Tenant kendi limitini yükseltemez

Tüm SaaS yazma uçları `Side.HOST`'tur. Tenant tarafı SaaS kaynaklarını yalnızca okur;
katalog (edition), paket ataması, tenant feature override'ı ve edition değişimi host
yetkisi ister.

İzin ağacındaki SaaS yaprakları HOST-only tanımlandığı için seeder bunları tenant `Admin`
rolüne **otomatik olarak vermez**; ayrıca elle engellemek gerekmez.

**SaaS entity'lerinde tenant `@Filter`'ı yoktur.** `User` / `Role` / `OrganizationUnit`
gibi identity entity'leri Hibernate `tenantFilter` ile satır bazında ayrılır; `Edition` ve
`Subscription` host'a aittir, böyle bir filtre taşımaz. Dolayısıyla bu uçlarda yanlışlıkla
açılan bir yetki **veri sızdırır** ve filtre onu yakalamaz.

Bu yüzden: **her SaaS ucu için negatif yetki testi zorunludur** — tenant token'ıyla
çağrının 403 aldığı kanıtlanmalıdır (bkz. `SaasAuthorizationIT`).

## 4. Para `BigDecimal`, kolon `numeric(19,4)`, para birimi zorunlu

Tutarlar hiçbir yerde `double`/`float` tutulmaz, JSON içine gömülmez. Fiyatlar ilişkisel
kolonlardır ve yanına para birimi alanı ister.

Abone, atandığı andaki fiyatı **snapshot** olarak taşır: katalogdaki fiyat sonradan
değişse de mevcut abonenin ödediği tutar değişmez (bkz. ADR-0012).

## 5. Tarih aritmetiği `java.time`, ay sonu clamp'lenir

Abonelik dönemleri sabit 30/365 gün sayımıyla **hesaplanmaz**; 30 gün bir ay değildir.
`java.time` (`Period`/`LocalDate.plusMonths`) kullanılır, böylece ay uzunluğu takvimin
söylediği kadardır ve ay sonu taşmaları clamp'lenir (31 Ocak + 1 ay = 28/29 Şubat).

Kodda ve testlerde `30` / `365` sabitleri bulunmamalıdır (bkz. ADR-0013).

## 6. Seeder kendi ürettiği şeyin varlığına bakar

Bir seed adımı, **başka** bir adımın idempotency kontrolüne bağlanmaz.

SaaS seed'i "host admin var mı" kontrolüne bağlansaydı, hâlihazırda kurulmuş her
veritabanında sessizce atlanır ve platform satılabilir paketsiz kalırdı. Bu yüzden SaaS
seed'i **edition'ın kendi varlığına** bakar.

Genel kural: yeni bir seed adımı eklerken idempotency anahtarı o adımın kendi çıktısı
olmalıdır.

## 7. Abonelik geçerlilik kapısı filtrede ve cache'li

Abonelik geçerliliği kontrolü `TenantResolverFilter` içinde yapılır, ama:

- yalnızca **tenant-scoped** isteklerde çalışır (host istekleri, `tenantId == null`,
  kapıya takılmaz),
- sonuç cache'lenir; her istekte DB'ye gidilmez,
- muaf yol listesi (auth, health vb.) kapının dışındadır.

Geçersiz abonelikte cevap 403 `SUBSCRIPTION_INVALID`'dir.

`tenancy` modülü `saas`'a bağımlı olamayacağı için (bkz. Kural 1) kapı doğrudan çağrı ile
değil, `tenancy` içinde tanımlı bir SPI (`TenantAccessCheck`) üzerinden bağlanır;
implementasyonu `saas` tarafında yaşar.

## 8. İzin uzlaştırması seed bayrağından bağımsızdır

Statik `Admin` rollerine yeni izinlerin eklenmesi ("permission reconciliation") **kendi
bayrağına** (`zero.seed.reconcile-permissions`, prod dahil default `true`) bağlıdır;
`zero.seed.enabled`'a **bağlanmaz**.

Gerekçe — gerçek bir prod arızası: uzlaştırma seed bayrağına bağlıydı, prod profilinde
seed kapalı olduğu için hiç çalışmadı. Yeni eklenen izinler mevcut kurulumun host admin
rolüne düşmedi; host admin eksik izinle kaldı ve yeni uçlar 403 döndü. Testler temiz
veritabanı kullandığı için **false-green** verdi.

Bu yüzden uzlaştırma, statik `Admin` rollerinin **içeriğine** bakar (izin kümesi
karşılaştırması), "kurulum yapılmış mı" sorusuna değil. `seed.enabled=false` iken de
çalıştığı testle kanıtlanır (`SeedHardeningIT`).

## 9. Zamanlanmış işler dağıtık kilit ister

Birden fazla instance çalışırken zamanlanmış abonelik işleri **aynı anda iki kez**
çalışabilir. Her zamanlanmış iş `@SchedulerLock` (ShedLock) ile korunur; kilit satırı
veritabanında tutulur.

Kaynak sistemde bu kilit yoktu ve çok-instance kurulumda işler çakışıyordu; şablona bu
kusur taşınmadı.

## 10. Durum geçişleri açık tablo, sessiz no-op yok

Abonelik durumu (`SubscriptionStatus`) örtük üç alandan türetilmez; **açık** bir enum ve
açık bir geçiş tablosuyla yönetilir (bkz. ADR-0009).

Geçersiz bir geçiş talebi **sessizce yok sayılmaz**, `DomainException(VALIDATION)` atar.
Sessiz no-op, çağıranın işlem başarılı sandığı ve durumun değişmediği en pahalı arıza
biçimidir.

Her yasal geçiş bir `subscription_events` satırı bırakır: abonelik geçmişi jenerik entity
history'ye değil, iş verisi olarak yazılır.

## 11. Ödeme aktivasyonu sunucu taraflıdır

Ücretli bir paket, istemcinin "ödedim" demesiyle aktifleşmez. Ödeme onaylanana kadar
abonelik `PENDING_PAYMENT` durumunda bekler; aktivasyon yalnızca sunucu tarafında
doğrulanmış ödeme ile yapılır (bkz. ADR-0014).

## 12. Downgrade hedefi silinemez

Bir edition, başka bir edition'ın downgrade hedefi olarak işaretliyse silinemez. Silinirse
süresi dolan abonelikler inecek bir yer bulamaz ve durumları belirsiz kalır. Downgrade
hedefi ayrıca **ücretsiz** olmak zorundadır.

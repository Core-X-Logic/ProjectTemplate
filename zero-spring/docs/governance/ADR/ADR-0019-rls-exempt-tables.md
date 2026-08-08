# ADR-0019: RLS muafiyeti kapalı bir kümedir — yalnız `saas` tabloları muaf

- **Durum:** Accepted · **Tarih:** 2026-08-08
- **İş kaydı:** RLS taban çizgisi (`feat/rls-baseline-and-identity-hardening`) · risk `R-08` ·
  muafiyetin gerekçesi **ADR-0015** · host kolu **ADR-0018**

## Bağlam

RLS taban çizgisiyle ilk gerçek politikalar yazıldı (`V12__rls_identity.sql`,
`V13__rls_audit_notification.sql`). Bundan sonra her tablo iki kümeden birine düşer:
**politikalı** ya da **muaf**. Bu ADR'in varlık sebebi, üçüncü bir kümenin — "kimsenin farkında
olmadığı, sadece atlanmış" tabloların — oluşmasını engellemektir.

Şu an `tenant_id` kolonu taşıyan **9** tablo var:

| Grup | Tablolar | Durum |
|---|---|---|
| identity | `users`, `roles`, `organization_units` | politikalı (V12) |
| audit/notification | `audit_logs`, `entity_changes`, `user_notifications` | politikalı (V13) |
| saas | `subscriptions`, `tenant_features`, `payments` | **muaf** (bu ADR) |

`editions`, `webhook_events`, `edition_features`, `subscription_events`, `settings`, `shedlock` ve
benzeri tablolar bu tartışmanın dışındadır: `tenant_id` kolonları yoktur, yani kiracıya ait veri
tutmazlar ve `tenant_isolation` şablonu onlarda **yazılamaz**. "Muaf" olmakla "aday olmamak" aynı
şey değildir; karıştırılırsa muafiyet listesi bir çöp kutusuna dönüşür.

## Karar

**RLS muafiyeti kapalı bir kümedir ve bugün tam olarak üç tablodan oluşur:**
`subscriptions`, `tenant_features`, `payments`.

1. Bu üçü **politika almaz.** Gerekçesi ADR-0015'te zaten kayıtlı ve burada tekrar edilmez, yalnız
   RLS'e çevrilir: host admin **tüm kiracıların** aboneliklerini, feature'larını ve ödemelerini
   yönetmek zorundadır; `saas` modülünde bu yüzden Hibernate `@Filter` de yoktur. İzolasyon orada
   üç katmanla sağlanır — host-only izinler (`Side.HOST`), tek tenant-facing uç
   (`GET /api/subscriptions/me`, tenant JWT claim'inden), ve servis katmanındaki explicit
   `tenantId` parametreli sorgular.
2. Muafiyetin bedeli ADR-0015'te "tasarım borcu" olarak yazılıdır ve **aynen geçerlidir**: yeni
   her `saas` ucu için `SaasAuthorizationIT`'ye negatif yetki testi eklemek zorunludur. RLS bu
   borcu kapatmaz, çünkü bu tablolarda RLS **yok**.
3. **"Politikası yok" ile "muaf" ayrı kümelerdir.** Yeni bir `tenant_id`'li tablo politikasız
   doğarsa bu bir karar değil bir **hatadır** ve kapsam guard'ı (`RlsCoverageIT`) onu kırmızıya
   çevirir. Bu ayrım yazılmazsa politikasız tablolar sessizce muafiyet devralır.
4. **Yeni bir muafiyet yeni bir ADR ister.** Bir tabloyu `tenant_id`'li yaratıp politikasız
   bırakmak, migration yorumuna "şimdilik" yazmak ya da muafiyet listesini bir test dosyasında
   güncellemek **yeterli değildir**. Gerekçe, alternatifi ve azaltımı bir ADR'de olmadan muafiyet
   yoktur.

## Gerekçe

Muafiyet listesi kod tarafından okunacak bir veri değil, **bir sözdür**. Sözler makineyle
zorlanmadıkça unutulur; "her kiracı tablosunda politika var mı" sorusunun bir daha hiç
sorulmaması, tam olarak böyle bir unutmanın sonucudur. Kapsam guard'ı `RlsCoverageIT` bu boşluğu
kapatır: `pg_class.relrowsecurity` + `relforcerowsecurity` + `pg_policies` tarayarak `tenant_id`
kolonu olan her tablonun politikalı olduğunu doğrular ve **yalnız bu ADR'deki üç tabloyu** kabul
eder. Bu ADR o testin girdisidir; testten önce yazılması bilinçlidir, çünkü liste testin içinde
doğarsa listeyi değiştirmek "testi düzeltmek" gibi görünür.

`saas` tablolarına politika yazmanın *teknik* olarak mümkün olduğunu not etmek gerekir:
ADR-0018'in host kolu (`OR current_setting('app.is_host', true) = 'on'`) host çapraz-kiracı
erişimini korurdu. Yine de yazılmıyor, iki sebeple: (a) o tablolarda kiracı bağlamında koşan
neredeyse hiçbir okuma yok — korunacak yüzey `/subscriptions/me` ile sınırlı ve o uç zaten JWT
claim'inden tenant alıyor; (b) zamanlanmış işlerin veritabanı bağlamı **hiç yazılmıyor** —
`HibernateTenantFilterAspect`'in pointcut'ı `within(@Service *)` ve `SubscriptionLifecycleProcessor`
bir `@Component`, yani onun `subscriptionRepository` üzerinden yaptığı okumalar aspect'e hiç
uğramaz ve tek bir GUC yazılmaz (yalnız içeriden çağırdığı `SubscriptionService` host olarak
işaretlenir). Politika eklenirse bu okumalar **0 satır** görür: abonelik yaşam döngüsü ve
mutabakat hiç iş yapmadan başarıyla döner — RLS'in fail-closed davranışının pahalıya geldiği tek
yer bu. `saas` tablolarını RLS'e sokmak, önce her zamanlanmış işin bağlamını açıkça host olarak
işaretlemeyi (ya da o okumaları `@Service`'e taşımayı) gerektirir; ayrı ve daha büyük bir dilim.

## Sonuçlar

- (+) Kapsam guard'ı (`RlsCoverageIT`) yazılabilir: kabul listesi üç satır, gerisi kırmızı.
- (+) `tenant_id` taşıyan yeni bir tablo politikasız doğarsa build kırılır — muafiyet artık
  sessiz kalamaz.
- (−) `saas` tarafındaki tasarım borcu (ADR-0015) **kapanmadı, sadece açıkça devralındı**. Ödeme
  ve abonelik verisi kiracıya aittir ve orada zemin yoktur; izolasyon tümüyle izin katmanına ve
  explicit sorgulara dayanır.
- (−) **Zamanlanmış işlerin veritabanı bağlamı bugün hiç yazılmıyor** (`@Component` sınıflardan
  yapılan repository çağrıları aspect'in pointcut'ının dışında). `saas` tabloları muaf olduğu için
  bu bugün zarar vermiyor; ama aynı desendeki bir arka plan işi politikalı bir tabloya dokunduğu
  anda sessizce 0 satır görür. Kural: **`@Component` bir işten politikalı bir tabloyu okumak
  yasaktır** — okuma bir `@Service`'e taşınır ya da bağlam açıkça yazılır
  (`DataSeeder.announceHostContextToDatabase()` deseni, ölçülmüş ilk örnek). Kayıt: bu ADR +
  RISK-REGISTER `R-46`.

## Makine-okunur kayıt

Kapsam guard'ı (`tenancy/RlsCoverageIT`) muafiyet listesini **bu dosyadaki aşağıdaki satırdan
ayrıştırır** ve kendi içindeki sabitle eşitliğini assert eder — iki liste birbirine referans
verir, tek başına hiçbiri otoriteyi sessizce genişletemez. Drift'te güncellenecek dosya
**budur**; ama önce 4. maddeyi oku: bu satıra bir tablo eklemek ancak yeni bir ADR ile mümkündür,
satırı düzenlemek o ADR'in yerine geçmez.

<!-- rls-exempt-tables: payments, subscriptions, tenant_features -->

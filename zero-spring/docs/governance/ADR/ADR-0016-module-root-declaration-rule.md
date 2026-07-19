# ADR-0016: ArchUnit Rule 4, entity'nin **kendi** paketini değil, **modül kökünün beyanını** ölçer

- **Durum:** Accepted · **Tarih:** 2026-07-19 · **İlgili:** R-37, W5-1

## Bağlam

Rule 4 şu gerekçeyle yazılmıştı: *"sınır beyan etmeyen bir modülde entity durmamalı"*. Uygulaması ise
başka bir şeyi ölçüyordu — entity'nin **kendi** paketinde bir `package-info.java` olup olmadığını.

İki ayrı şey oldukları ölçüldü:

- **Kural yanlış yerde kırmızıydı.** Donmuş 12 ihlalin **hiçbiri** modül kökü değil; hepsi
  `allowedDependencies` beyan eden 5 modülün *internal alt paketleri* (`audit.domain`,
  `saas.subscription`, `settings.domain`, …). Bunlar zaten kısıtlı.
- **Kural doğru yerde sessizdi.** Gerekçenin geçerli olduğu paketlerde (`config`, `seed`, `shared`)
  hiç entity yok, dolayısıyla kural oraya **hiç bakmıyordu**.
- **Kural ters işaretliydi.** `identity.domain` kuralı *geçiyordu* — çünkü `package-info.java`'sı
  `@NamedInterface` ile paketi **dışa açıyor**. Yani kuralı tatmin eden şey, gerekçenin kapatmak
  istediği şeyin tam tersiydi.

Belirleyici ölçüm (W5-1b): HEAD'in temiz bir klonunda `saas/package-info.java` **silindi** —
eski kural **6/6 yeşil, BUILD SUCCESS** verdi. Modül kökü beyanı ortadan kalktı, kural konuşmadı.

## Karar

Rule 4 yeniden formüle edildi: `entitiesLiveUnderADeclaredModuleRoot()`.

Entity'nin paketinden başlayıp `basePackage`'a kadar **yukarı yürünür**; bu zincirde
`@ApplicationModule` **beyan eden** bir paket aranır. Bulunursa geçer.

Üç ayrıntı bilinçli:

1. **`allowedDependencies` de `Type.OPEN` de kabul.** Kuralın konusu bağımlılıkların dar olması
   değil, **bir karar verilmiş olması**. `Type.OPEN` bilinçli bir muafiyettir; sessizlik değildir.
2. **Kaynak `withoutComments()` ile okunur.** `@ApplicationModule`'den bahseden bir javadoc kuralı
   tatmin **edemez**.
3. **Vacuity guard.** Sıfır entity görmek kuralın kendisi için bir **ihlaldir**. Paket yeniden
   adlandırıldığında kuralın sessizce "hiçbir şeyi kontrol etmeyerek" yeşile dönmesi bu depoda
   ölçülmüş bir sınıftır (`migration-drift` boş sette yeşil dönüyordu).

**Boş `package-info.java` eklemek reddedildi.** Kuralı susturur, Modulith'in zorladığı hiçbir şeyi
değiştirmez — yani ölçüyü tatmin edip riski yerinde bırakır.

## Sonuçlar

- (+) Kural artık gerekçesinin tarif ettiği şeyi ölçüyor. Negatif kanıt: `saas/package-info.java`
  silindiğinde **kırmızı, 5 ihlal**; aynı silme eski kuralda **yeşildi**.
- (+) Donmuş ihlal 12 → **0**. Tek satır `package-info.java` eklenmedi (dosya sayısı 15 → 15).
- (+) Hata mesajı doğru düzeltmeyi adlandırıyor ve yanlış olanı açıkça yasaklıyor:
  *"Declare the module root (allowedDependencies, or Type.OPEN if the waiver is deliberate); do NOT
  add an empty package-info.java, which silences this rule without changing anything Modulith
  enforces."*
- (−) Kural, entity **içermeyen** bir modül kökünün beyansız kalmasını yakalamaz. Kapsamı entity
  taşıyan ağaçtır. Modulith'in kendi `ApplicationModules.verify()`'ı o boşluğun bir kısmını tutuyor.
- (−) Kural **kaynak dosya okuyor**, bytecode değil. `@ApplicationModule` `CLASS` retention'a sahip
  olduğu ve `package-info` bytecode'u ArchUnit'in import'una girmediği için başka yolu yok. Bedeli:
  kural, derlenmiş sınıfların yanında **kaynak ağacının da mevcut olmasına** bağımlı.

## Ölçülen yan etki — kuralı yeniden adlandırmak store anahtarını değiştirir

`FreezingArchRule`, kuralı `.as(...)` metniyle anahtarlar. Ad değişince ArchUnit onu **yeni bir
kural** sayar. Mevcut 12 ihlalin yeni UUID altında **sessizce yeniden donup donmayacağı**
bilinmiyordu — donsaydı bu değişiklik başarı gibi görünürken 12 ihlali taze bir dosyanın arkasında
gizlemiş olurdu.

Ölçüm: yeni store dosyası **0 bayt**, eski UUID **silinmiş**, öksüz dosya **yok**, `stored.rules`
tam **5 kural**. `freeze.refreeze=false` beklendiği gibi davrandı.

Kural adı değiştiren herkes bunu **tekrar ölçmelidir**; teyit edilmeden geçilirse yeşil hiçbir şey
anlatmaz.

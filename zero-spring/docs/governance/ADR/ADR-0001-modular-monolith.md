# ADR-0001: Modüler monolit (Spring Modulith), mikroservis değil

- **Durum:** Accepted
- **Tarih:** 2026-07-17
- **Faz:** Genel

## Bağlam

ASP.NET Zero kaynak sistemi tek deployable monolittir. Hedef Spring sisteminin dağıtım topolojisi
seçilmelidir: modüler monolit mi, mikroservis mi?

## Karar

Spring Modulith tabanlı **modüler monolit**. Modül sınırları derleme/test zamanında
`ApplicationModules.verify()` ile zorlanır. Modüller arası asenkron akış için Modulith event registry
(transactional outbox) yolu açık bırakılır.

## Gerekçe

- Parite hedefi monolit; dağıtık sistem karmaşıklığı (network partition, dağıtık tx, sürümleme) sıfır değer katar.
- Modül sınırlarının statik zorlanması "big ball of mud" riskini yapısal olarak engeller.
- Ölçekleme önce yatay replikasyonla (stateless API + Redis + Postgres) karşılanır.
- Mikroservise geçiş gerektiğinde modüller zaten event sözleşmesi üzerinden konuştuğu için düşük maliyetlidir.

## Kırılma kriteri (mikroservise geçiş tetikleyicileri)

(a) Bir modülün deploy frekansı diğerlerini blokluyor; (b) CPU/IO profili asimetrik (örn. rapor üretimi);
(c) ekip > 2 takım ve kod sahipliği çakışıyor.

## Sonuçlar

- (+) Basit dağıtım, tek DB tx, hızlı geliştirme.
- (+) Sınır ihlali merge'i bloklar (Modulith test).
- (−) Tüm modüller aynı süreçte ölçeklenir (şimdilik kabul).

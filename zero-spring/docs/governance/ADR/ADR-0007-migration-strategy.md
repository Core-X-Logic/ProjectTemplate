# ADR-0007: Greenfield parity build + tek seferlik ETL + big-bang cutover

- **Durum:** Accepted
- **Tarih:** 2026-07-17
- **Faz:** Genel

## Bağlam

Mevcut .NET sistemi üretimde. Geçiş stratejisi seçilmeli: strangler/incremental, big-bang rewrite-in-place,
dual-write, veya greenfield paralel.

## Karar

**Greenfield parity build** — Spring sistemi paralel geliştirilir, .NET üretimde dokunulmaz; veri cutover
penceresinde SQL Server → PostgreSQL **tek seferlik ETL** ile taşınır (F6); **big-bang cutover** + 14 günlük
rollback penceresi (eski sistem read-only standby).

## Gerekçe (alternatiflerin elenmesi)

- **Strangler/incremental:** ortak DB şeması ABP'ye sıkı bağlı (iki framework aynı şemaya güvenli yazamaz);
  API sözleşmesi birebir korunmayacak (facade'ın şeffaf devretme ön koşulu yok); auth modelleri farklı.
- **Big-bang rewrite-in-place:** dil/runtime tamamen değişiyor, yerinde dönüştürülecek ortak kod yok;
  uzun feature-freeze iş riski.
- **Dual-write:** ABP'nin örtük davranışları (audit kolonları, tenant atama) çift-yazmada replike edilemez.

## Sonuçlar

- (+) Her faz çalışan, test edilebilir dikey dilim üretir; .NET referans (oracle) olarak kalır.
- (+) Parite testleri iki sistem karşılaştırılarak yazılabilir.
- (−) Veri migration + kullanıcı şifre hash köprüsü (Identity v3 PBKDF2 → BCrypt re-hash) gerekli (R-04).
- (−) Kısa bakım penceresi + cutover riski (rollback stratejisiyle azaltılır).

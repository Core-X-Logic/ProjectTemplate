# Arşiv — şablonun türetildiği göçün kaydı

**Bu dizin senin projeni bağlamaz.**

Buradaki dosyalar, bu şablonun türetildiği bir ASP.NET Zero → Java/Spring modernizasyonunun
çalışma kayıtlarıdır: faz sözleşmeleri, parite analizleri, ETL etki notları, faz teslim raporları.
Klonladığın projede o göç **hiç yaşanmadı**.

## Neden silinmedi

Mimari kararların **neden** öyle alındığı burada. Örneğin abonelik durumlarının neden açık bir
state machine olduğu, fiyatın neden atandığı anda snapshot alındığı, webhook idempotency
stratejisinin neden o şekilde kurulduğu — hepsinin gerekçesi kaynak sistemde gözlenmiş somut
kusurlara dayanıyor. Kararların özeti `../governance/ADR/` altında yaşıyor; buradaki dosyalar
o kararların ham gözlem tabanı.

## Neden ana `docs/` dizininde değil

Bu dosyalar yeni bir proje için **aktif olarak yanıltıcı**:

- "Faz 1/2/5", "Slice A/B/C", "F5/F6" gibi kavramların senin projende karşılığı yok.
- İçlerindeki durum tabloları, test sayıları ve GO/NO-GO kararları dondurulmuş anlardır;
  bugünkü kodu tarif etmiyorlar.
- Bazıları artık var olmayan bir kaynak ağaca (`Asp.NET Zero/`) yol veriyor — o dizin klonda
  yok, hiç track edilmedi.
- En az bir dosya doğrudan yanlış bilgi taşıyordu (`IMPLEMENTATION-PLAN.md` frontend'i "Angular"
  diye kilitliyor; depo React 19 ile kurulu ve karar `ADR-0008` ile alınmış).

## Nereye bakmalısın

| İhtiyacın | Dosya |
|---|---|
| Buradan başla | `../../../README.md` (repo kökü) |
| Yeni modül ekleme yordamı | `../ADDING-A-MODULE.md` |
| Mimari | `../ARCHITECTURE.md`, `../SAAS-ARCHITECTURE.md`, `../FRONTEND-ARCHITECTURE.md` |
| Uyulacak kurallar | `../ARCHITECTURE-RULES.md` |
| Kararların gerekçesi | `../governance/ADR/` |
| Yayına çıkarma | `../RELEASE-RUNBOOK.md` |
| Devralınan bilinen kısıtlar | `../governance/RISK-REGISTER.md` |

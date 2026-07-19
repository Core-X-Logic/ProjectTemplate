# ADR-0005: Entity history — custom Hibernate listener (Envers değil)

- **Durum:** Accepted
- **Tarih:** 2026-07-17

## Bağlam

Yönetim panelinde "bu kaydı kim, ne zaman, neyi değiştirdi" sorusunun cevabı gerekiyor. İki seçenek
var: Hibernate Envers (`@Audited`) veya kendi event listener'ımız.

## Karar

**Custom Hibernate event listener** (`PostInsert`/`PostUpdate`/`PostDelete`) → `entity_changes` +
`entity_property_changes` tabloları. İzlenen tipler config'te sabit liste (Role, OrganizationUnit,
User, Tenant).

## Gerekçe

- **Flyway + `ddl-auto=validate` uyumu (belirleyici gerekçe):** Envers kendi `_aud` + `revinfo`
  tablolarını üretir ve `validate` bunları da denetler; şemanın tek kaynağı SQL olduğu için (ADR-0002)
  bu tabloları elle Flyway'e yazmak gerekir — külfetli ve kırılgan. Custom şema bizim kontrolümüzde.
- Tenant/user bağlamını (`CurrentUser`) doğrudan enjekte edebiliriz; Envers'te ek `RevisionListener` gerekir.

## Sonuçlar

- (+) Flyway-dostu, `validate` ile çakışmaz.
- (+) Kayıt başına eski/yeni değer + kim + ne zaman.
- (−) Envers'in hazır sorgulama API'si yok; raporlama endpoint'i elle yazılır (kabul).
- (−) Listener'da sonsuz döngü (audit entity'lerinin kendilerini izlemesi) engellenmeli — `EntityHistoryIT` kapsamında.

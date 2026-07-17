# ADR-0005: Entity history — custom Hibernate listener (Envers değil)

- **Durum:** Accepted
- **Tarih:** 2026-07-17
- **Faz:** F2

## Bağlam

ABP EntityHistory (`AbpEntityChanges`/`AbpEntityPropertyChanges`) değişiklik geçmişi tutuyor (kaynakta
`IsEnabled=false` ama şema var). Spring'de iki seçenek: Hibernate Envers (`@Audited`) veya custom listener.

## Karar

**Custom Hibernate event listener** (`PostInsert`/`PostUpdate`/`PostDelete`) → `entity_changes` +
`entity_property_changes` tabloları. İzlenen tipler config'te sabit liste (Role, OrganizationUnit,
User, Tenant).

## Gerekçe

- **Flyway + `ddl-auto=validate` uyumu:** Envers `_aud` + `revinfo` tablolarını da validate eder;
  bunları elle Flyway'e yazmak külfetli ve kırılgan. Custom şema bizim kontrolümüzde, ABP paritesine yakın.
- ABP'nin `AbpEntityChanges` şemasına birebir yakın veri modeli → migration ve raporlama kolay.
- Tenant/user bağlamını (`CurrentUser`) doğrudan enjekte edebiliriz; Envers'te ek `RevisionListener` gerekir.

## Sonuçlar

- (+) Flyway-dostu, validate ile çakışmaz.
- (+) Parity: eski/yeni değer + kim + ne zaman, ABP alan yapısına yakın.
- (−) Envers'in hazır sorgulama API'si yok; raporlama endpoint'i elle yazılır (kabul).
- (−) Listener'da sonsuz döngü (audit entity'lerini izleme) engeli gerekir — test kapsamında.

# Risk Register + Mitigasyon Takvimi

Skala: Olasılık (L/M/H) × Etki (L/M/H) → Seviye. Durum: `Open` · `Mitigating` · `Closed`.
Kaynak analiz: ANALYSIS §3.1. `PHASE-2-REPORT.md` §F ile birebir hizalı (2026-07-18).

| ID | Risk | Olas. | Etki | Seviye | Durum | Mitigasyon | Ne zaman |
|---|---|---|---|---|---|---|---|
| R-01 | ABP örtük tenant filtresi Spring'de kurulmazsa tenant veri sızıntısı | M | H | **Kritik** | **Closed** | Hibernate @Filter + AOP + JWT-claim otoriter (ADR-0003); TenantIsolationIT/TenantEscalationIT + canlı 403 smoke | F1 ✅ |
| R-02 | Zayıf token varsayılanları (HS256, 1g/365g) | H | H | **Kritik** | **Closed** | 15dk access + rotate refresh + reuse kaskadı (ADR-0004) | F1 ✅ |
| R-03 | `SimpleStringCipher` sabit passphrase (enc_auth_token/connstr/settings) | M | H | Yüksek | Open | ETL'de decrypt/re-encrypt; WS auth yeniden tasarım | F6 (ETL) / F3 (WS) |
| R-04 | Çift şifre hash formatı (ABP + Identity v3 PBKDF2) | H | M | Yüksek | Open | Köprü PasswordEncoder → ilk login'de BCrypt re-hash | F6 |
| R-05 | SQL Server→PG tip + Windows→IANA timezone eşleme | H | M | Yüksek | Open | ETL kolon eşleme + TZ dönüşüm scripti + doğrulama raporu | F6 |
| R-06 | Access token TTL boyunca (≤15dk) iptal edilemez | M | M | Orta | Mitigating | Kısa TTL; gerekirse Redis jti denylist | F4 (koşullu) |
| R-07 | ABP API zarfı ile uyumsuzluk → frontend veri katmanı köprüsü | M | M | Orta | **Closed** | Yeni backend RFC9457 ProblemDetail + düz JSON; openapi-typescript typed client (42 path) çalışıyor, contract-gate geçti | F2 ✅ |
| R-08 | Hibernate @Filter findById/lazy-collection'a uygulanmıyor (ikincil savunma boşluğu) | M | M | Orta | Mitigating | Birincil savunma explicit tenant sorguları (kanıtlı); @FilterJoinTable + ArchUnit iyileştirme | F3 |
| R-09 | Tüm admin ekranları React'te sıfırdan yazılıyor (efor) | M | M | Orta | Mitigating | Metronic starter layout/shadcn taşındı; ilk vertical slice uçtan uca kapandı; slice C (impersonation/audit/settings UI) kaldı | F2 (devam) / slice C |
| R-10 | Kaynak kod anomalileri (HSTS yalnız Dev, global 2FA cache, System.Random şifre, GraphQL playground açık) | — | — | Bilgi | **Closed** (taşınmıyor) | Anomaliler bilinçli porte edilmez; secret rotasyonu cutover'da | F6 |
| R-11 | Permission grant hiyerarşik semantiği (parent→child, Host/Tenant side) düz authority'ye çevrilemez | M | M | Orta | Mitigating | PermissionDefinitions ağacı + side modeli DONE (PermissionTreeIT); grant verisi ETL eşlemesi kaldı | F2 (model ✅) / F6 (veri) |
| R-12 | Impersonation act-claim güvenliği (cascade, actor audit) | M | H | Yüksek | **Closed** | Tek kullanımlık token + cascade yasağı + audit; ImpersonationIT geçti | F2 ✅ |
| R-13 | Setting fallback zinciri (isInherited istisnaları, client-visibility whitelist) yanlış kopyalanırsa yanlış değer/secret sızıntısı | M | M | Orta | **Closed** | SettingDefinition.visibleToClient + scope zinciri; SettingsIT geçti | F2 ✅ |
| R-14 | Faz 2 kapsam büyük — tek codegen'de verify-yeşil riski | M | M | Orta | **Closed** | 6 ayrık yazıcı + düzeltme turları + adversaryal review; verify yeşil (52 test) | F2 ✅ |
| R-15 | SaaS/ödeme entegrasyon borçları (Stripe legacy Plans API, Customer.Description eşleşmesi) | M | M | Orta | Open (kapsamda F5) | Prices API + metadata eşleşmesi + webhook idempotency | F5 |
| R-16 | Metronic starter'da çift/çakışan bağımlılık (react-query v3+v5, formik+rhf, Windi+Tailwind) | M | M | Orta | **Closed** | app/'te tekilleştirildi: @tanstack/react-query v5 + rhf+zod + Tailwind4; formik/rq3/windicss/notistack atıldı; vendor ham commit edilmedi (ADR-0008) | F2 ✅ |
| R-17 | Frontend-backend API sözleşme kayması (manuel tip) → runtime hata | M | M | Orta | **Closed** | OpenAPI'den typed client codegen (`gen:api`, build adımı) çalışıyor; openapi-diff CI iyileştirmesi F4 | F2 ✅ |
| R-18 | Notifications ilk slice'ta uçtan uca isteniyor ama backend Faz2 sözleşmesinde yoktu | H | M | Orta | **Closed** | Inbox backend (V3 + service + 4 endpoint + welcome publish); NotificationInboxIT sahiplik izolasyonu; WebSocket F3 | F2 ✅ |
| R-19 | **False-green:** verify yeşil ama boşluk test edilmediği için yeşil | H | H | **Yüksek** | **Closed** | Adversaryal inceleme her faz zorunlu; 11 boşluk için pozitif parity testi; mutasyon testi F3 adayı | F2 ✅ |
| R-20 | change-password şifre karmaşıklık politikasını atlıyor (`aaaaaaaa` kabul); reset ile tutarsız | H | M | **Yüksek** | **Closed** | ProfileService+AccountService tek yol: PasswordPolicyValidator+history; PasswordPolicyIT kanıtlı | F2 ✅ |
| R-21 | Frontend starter config deprecation'ları (tsconfig baseUrl, import.meta.env tipi) | M | L | Düşük | **Closed** | vite-env.d.ts + tsconfig düzeltmeleri; `tsc -b` strict + build yeşil. (Vendor CSS `@media (max-width: var(...))` uyarısı kozmetik, build'i kırmıyor — R-23'e taşındı) | F2 ✅ |
| R-22 | `mvnw` (POSIX) CRLF nedeniyle bash'te kırık — ubuntu CI'da build patlar | H | M | **Yüksek** | Mitigating | mvnw LF'e çevrildi (LF-only doğrulandı) + `.gitattributes` (eol=lf mvnw/*.sh, crlf *.cmd); repo autocrlf=true idi. **Commit + CI koşusu doğrulaması bekliyor** | F2 kapanış (commit) |
| R-23 | Düşük artıklar: `read-all` endpoint'i testsiz; user_notifications.tenant_id dekoratif (user_id izolasyonu); vendor CSS media-query uyarısı | L | L | Düşük | Open | markAllRead testi + tenant_id semantiği + CSS düzeltme F3 | F3 |
| R-24 | Soft-delete + unique(tenant_id, username): silinen kullanıcının username'i tekrar kullanılamıyor (409); ABP'de silinen username yeniden kullanılabilir | L | L | Düşük | Open | Unique index'e `deleted` dahil et (partial unique where deleted=false) veya silmede username'i mühürle; parity kararı | F3 |
| R-25 | Impersonation cascade yasağı frontend'te yalnız UI-block (component); auth.impersonate() programatik çağrı client'ta re-check etmiyor — backend cascade kuralı otoriter (403 canlı kanıtlı) | L | M | Düşük | Mitigating | Backend authoritative (ImpersonationService + smoke 403); istenirse auth.impersonate guard eklenir | F3 (koşullu) |

## Mitigasyon takvimi (özet)

- **F1 ✅:** R-01, R-02 Closed; R-10 taşınmama kararı.
- **F2 ✅ (Closed):** R-07, R-12, R-13, R-14, R-16, R-17, R-18, R-19, R-20, R-21.
- **F2 kısmi — R-11:** permission model kapandı (PermissionTreeIT); genel durum **Mitigating** (grant verisi ETL → F6).
- **F2 kapanış (commit):** R-22 (mvnw LF — commit + CI koşusuyla doğrula).
- **F2 devam / slice C:** R-09 (React ekranları — impersonation/audit/settings UI), R-08 (@FilterJoinTable/ArchUnit), R-23 (düşük artıklar).
- **F3:** R-03 (WS auth), R-06 koşullu jti denylist, mutasyon testi.
- **F5:** R-15 (SaaS ticari katman).
- **F6:** R-03/R-04/R-05/R-11 veri tarafı (ETL); secret rotasyonu (R-10) cutover.

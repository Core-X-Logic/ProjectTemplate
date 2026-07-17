# E) KALİTE KAPILARI — zero-platform

## 1. Definition of Done (her PR / her özellik)

- [ ] Kod + test aynı PR'da; `mvnw verify` yeşil (unit + IT)
- [ ] `ApplicationModules.verify()` geçiyor (modül sınırı ihlali = merge engeli)
- [ ] Yeni endpoint: OpenAPI'de görünür + `@PreAuthorize` izni tanımlı + ProblemDetail hata gövdesi
- [ ] Yeni tablo/kolon: Flyway versioned migration + `ddl-auto=validate` ile doğrulanmış
- [ ] Tenant'a dokunan her sorgu: izolasyon testi (pozitif + negatif senaryo)
- [ ] Log'da PII/secret yok; yeni config anahtarı README + application.yml'de belgeli
- [ ] Kırıcı API değişikliği yok (openapi-diff CI kontrolü) veya sürümleme notu var

## 2. Test coverage hedefleri (JaCoCo, CI'da zorlanır)

| Kapsam | Eşik | Not |
|---|---|---|
| Satır coverage (proje geneli) | ≥ %75 | `jacoco-maven-plugin` check goal |
| Branch coverage — `identity.auth`, `tenancy` | ≥ %90 | güvenlik-kritik paketlere paket bazlı kural |
| Mutasyon testi (F3'ten itibaren, PIT) | ≥ %60 auth paketinde | opsiyonel ama önerilir |
| IT senaryoları | Her public endpoint ≥ 1 happy + 1 authz-negatif | permission matrix testi F2'de parametrize |

## 3. Security checklist (her release)

- [ ] Secrets yalnız env/vault; repo taramasında sızıntı yok (gitleaks CI adımı)
- [ ] `JWT_SECRET` prod'da ≥ 64 byte rastgele; F4 sonrası RS256 + key rotasyon planı
- [ ] Refresh rotation aktif; çalınan refresh yeniden kullanımı 401 + tüm aile revoke (F2)
- [ ] Lockout ve rate limit (login/refresh) aktif ve test edilmiş
- [ ] OWASP Top 10 gözden geçirme: IDOR (tenant + id kontrolleri), mass assignment (record DTO'lar, entity asla bind edilmez), SQLi (yalnız JPA/parametrik), SSRF (dış çağrı allowlist)
- [ ] Bağımlılık taraması temiz: Dependabot/`dependency-check` — High/Critical = release engeli
- [ ] ZAP baseline taraması (F4+): High bulgu yok
- [ ] Audit: auth olayları (login başarılı/başarısız, impersonation, refresh) audit_log'da

## 4. Performance checklist

- [ ] p95 login < 300 ms, p95 listeleme < 200 ms @ 100 RPS (lokal referans; Gatling senaryosu repo'da)
- [ ] N+1 yok: kritik listelemelerde Hypersistence `assertSelectCount` testleri
- [ ] Hikari pool: max ≤ (DB max_connections/instance sayısı); bağlantı sızıntı testi
- [ ] Cache hit oranı izleniyor (permission/settings cache F2+); cache'siz doğruluk testi var
- [ ] Index gözden geçirme: her FK + sık WHERE kolonu; `EXPLAIN ANALYZE` kanıtı büyük tablolar için
- [ ] JVM: container-aware (`-XX:MaxRAMPercentage`), GC log açık prod'da

## 5. Production readiness checklist

- [ ] Health probe'ları (liveness/readiness) ayrık; graceful shutdown doğrulandı
- [ ] JSON log + trace_id/tenant_id/user_id alanları; log retention politikası
- [ ] Prometheus metrikleri + temel alarmlar (5xx oranı, p95, Hikari doygunluk, JVM heap)
- [ ] Migration runbook: Flyway forward-only; başarısız migration prosedürü yazılı
- [ ] Yedekleme + restore provası (Postgres PITR); RPO/RTO tanımlı
- [ ] Konfig üç ortam profili (dev/staging/prod) ile; prod'da seed kapalı (`zero.seed.enabled=false`) veya şifre zorunlu env
- [ ] Container: non-root, read-only FS uyumlu, image taraması (Trivy) temiz
- [ ] Kapasite notu: tek instance referans yükü + yatay ölçekleme talimatı

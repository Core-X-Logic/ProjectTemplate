# İlk Gün Cheat Sheet (1 sayfa)

Yazdır / açık tut. Detay: `QUICKSTART.md` · `WORKING-WITH-AI.md` · `EVIDENCE-AND-GATES.md`.

## Çalıştır (dev profili ZORUNLU)
```bash
cd zero-spring/backend && docker compose up -d          # pg 5433 · redis 6380 · mailpit 1025/8025
./mvnw spring-boot:run -Dspring-boot.run.profiles=dev    # profilsiz = AÇILMAZ (doğru davranış)
# ayrı terminal:
cd zero-spring/frontend/app && cp .env.example .env && npm ci && npm run dev
```
UI :5173 · API :8080 · Swagger /swagger-ui.html · Mail :8025 · giriş `admin` / `Admin123!` · tenant boş = host

## Çalışıyor kanıtı (4/4 yeşil olmalı)
```bash
curl -s localhost:8080/actuator/health/readiness                    # {"status":"UP"}
# login → token → /me 200 → anonim /me 401   (negatif kapı ŞART)
```

## Push öncesi (CI dakikası harcama)
```bash
bash zero-spring/scripts/ci-local.sh          # readiness · smoke · secrets · migration
cd zero-spring/backend && ./mvnw -B -ntp clean verify   # clean ZORUNLU (bayat .class tuzağı)
cd zero-spring/frontend/app && npm run build && npm run test
npm run gen:api                               # backend API değiştiyse — client'la ATOMİK
```

## Karar noktaları
| Durum | Yap |
|---|---|
| Çok katmanlı iş (be+fe+izin+i18n+test) | `tech-lead` çağır → dilimlere böler |
| Tek backend / tek frontend | doğrudan `backend-engineer` / `frontend-engineer` |
| Kod yazıldı, commit öncesi | `stack-reviewer` |
| Yeni test/gate eklendi | `gate-auditor` (kırmızıya döndüğünü kanıtla) |
| İki ajan aynı ortak dosyaya (ci.yml, izin, i18n, schema.d.ts) | **serileştir**, paralel salma |

## Kanıtsız "tamamlandı" YOK — done =
derleniyor + ilgili test geçti (ad+sayı) + `clean verify` SUCCESS + Modulith verify + governance güncel.
**Negatif kanıt:** düzeltmeden önce testi yaz, eski kodda DÜŞTÜĞÜNÜ gör. Düşmüyorsa test yanlış şeyi ölçüyor.
**Yeşil ≠ doğruladı:** kapı boş sette yeşil dönebilir — log'dan teyit et.

## Yeni uç eklerken 3 refleks
1. **Üçlü kilit:** `@PreAuthorize` + `<Can>` + route guard (üçü birden, string literal yazma)
2. **Kiracılık:** yeni entity → `tenant_id` + Hibernate `@Filter` (unutmak = sessiz sızıntı)
3. **Migration:** daima **yeni** `V<n>__` dosyası (uygulanmışı düzenleme = checksum hatası)

## Tuzaklar (canlı yaşandı)
- Trafik kapısı `/actuator/health/readiness` — aggregate `/health` tali bağımlılık düşünce **503**
- forgot-password payload: `usernameOrEmail` (❌ `email`)
- `@EntityGraph` + `Pageable` **birlikte kullanılmaz** (Hibernate belleğe çeker, `HHH90003004`)
- Secret **repoya asla** — sadece env / Actions Secrets / secret store
- CORS: dev backend `:5173` bekler; Vite `:5174`'e kayarsa login "network error"

## Nereye bak
neyin hazır DEĞİL → `../governance/RISK-REGISTER.md` · prompt şablonu → `PROMPT-CATALOG.md` ·
go-live → `OPERATOR-HANDOFF.md` · modül ekle → `../ADDING-A-MODULE.md` · 7 günlük plan → `FIRST-7-DAYS.md`

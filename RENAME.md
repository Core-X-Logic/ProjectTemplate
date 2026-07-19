# Şablonu kendi projenize çevirme

```bash
git clone <bu-repo> benim-projem && cd benim-projem
./scripts/rename-project.sh com.acme.crm acme-crm "Acme CRM"
```

Script çalışma ağacının **temiz** olmasını ister ve genelde 293 dosyaya dokunur. Geri almak
tek komuttur: `git checkout . && git clean -fd`.

## Neden bir script

Yeniden adlandırma yüzeyi 293 dosyada 722 geçiş. Elle yapılabilecek bir iş değil — ve asıl
sorun bu değil: **yarım yapıldığında çoğu yerde hata vermez.**

## Script'in bilerek DOKUNMADIKLARI

Üç şey kasıtlı olarak dışarıda. Değiştirmek isterseniz bilerek yapın.

### 1. `zero.*` config prefix'i — en tehlikelisi

`@ConfigurationProperties(prefix = "zero.jwt")` ve `application.yml` içindeki `zero.jwt.*`
anahtarları **birlikte** değişmek zorundadır. Yarısı değişirse Spring **hata vermez**: binding
sessizce başarısız olur ve alanlar default değerlerine düşer.

Somut sonuç: `zero.seed.enabled` default'u `false`, `zero.ratelimit.enabled` default'u `true`.
Yani yarım bir prefix değişikliği, seed'i kapatıp rate limit'i açar — hiçbir uyarı vermeden,
davranışı değiştirerek.

Kazanç kozmetik, risk gerçek. Değiştirecekseniz beş `@ConfigurationProperties` sınıfını
(`zero.audit`, `zero.cors`, `zero.jwt`, `zero.ratelimit`, `zero.request`) ve dört yml dosyasını
**tek commit'te** değiştirin, sonra `./mvnw clean verify` koşun.

### 2. Veritabanı adı ve kullanıcısı (`zero`)

`docker-compose.yml`, `application.yml` ve CI'da geçer. Değiştirmek güvenli ama üç yerde
birden yapılmalı; ayrıca yerel hacminizi (`docker compose down -v`) yeniden kurmanız gerekir.
Şablon bunu size bırakır çünkü çoğu kurulumda gereksizdir.

### 3. `docs/history/`

Şablonun türetildiği göçün arşivi. Oradaki metinler tarihsel kayıttır; yeniden adlandırmak
onları **yanlış** yapar — o göç gerçekten `zero-platform` üzerinde yaşandı.

## Script'in yaptıkları

| # | Ne | Not |
|---|---|---|
| 1 | Java paket ağacı taşınır + tüm referanslar | Hem `com.mycompanyname.zero` hem `com/mycompanyname/zero` biçimi |
| 2 | Maven `groupId` / `artifactId` | Jar adı **etkilenmez**: `<finalName>app</finalName>` ile sabitlendi |
| 3 | Görünen ad | `index.html`, i18n `app.name`, e-posta şablonları, admin layout |
| 4 | Dev/test JWT anahtarları yenilenir | Aşağıdaki nota bakın — bu, güvenlik açısından **kritik** bir adım |
| 5 | Kalıntı taraması | Kalan geçişleri listeler; çoğu yorum olabilir, gözden geçirin |

### 4. adım neden kritik

Bir şablona commit'lenen dev anahtarı **tanımı gereği herkese açıktır**; başka bir commit'li
değere döndürmek tek başına hiçbir şey kazandırmaz. Asıl koruma şudur: `JwtSecretValidator`
repoda commit'li anahtarları **`prod` profilinde reddeder**.

Bu yüzden yeni anahtar üretilirken o **reddetme listesi de birlikte güncellenmelidir**. Yarım
kalırsa koruma **fail-open** olur: yeni commit'li anahtar prod'da artık engellenmez, ve bunu
söyleyen hiçbir hata mesajı yoktur. Script dört dosyayı birlikte hareket ettirir:
`application-dev.yml`, `application-test.yml`, `JwtSecretValidator`, `.gitleaks.toml`.

## Sonra — doğrulama kapısı

Yeniden adlandırmanın sessiz kırılmaları **yalnızca burada** görünür:

```bash
cd zero-spring/backend && ./mvnw -B -ntp clean verify
```

`BUILD SUCCESS` yetmez; şu dört testin **adıyla** yeşil olduğunu görün:

| Test | Neyi kanıtlar |
|---|---|
| `EntityHistoryIT` | Entity history hâlâ kayıt tutuyor. Bu, yeniden adlandırmanın en sessiz kurbanıydı — izlenen tipler bir zamanlar FQN **string** listesiydi ve paket adı değişince tracking exception vermeden dururdu |
| `ModularityTests` | Modül sınırları bozulmadı. Dikkat: bu test `package-info.java` **yazmayan** bir modülü yeşil geçirir; yokluğu değil, varlığı doğrular |
| `EmailDispatchIT` | E-posta şablonları ve gönderen kimliği |
| `TenantIsolationIT` | Kiracı izolasyonu |

```bash
docker build -t acme-crm zero-spring/backend/
cd zero-spring/frontend/app && npm ci && npm run build && npm run test
```

## Elle yapılacaklar

Script bunları **yapmaz**, sizin yapmanız gerekir:

- [ ] `LICENSE` — varsayılan "özel mülk"tür. Önce `NOTICE.md`'yi okuyun (frontend teması
      **ticari** bir ürüne dayanır ve ayrıca lisanslanmalıdır).
- [ ] `README.md` — proje açıklamasını kendi ürününüzle değiştirin.
- [ ] `.github/CODEOWNERS` — `@OWNER` yer tutucuları.
- [ ] `frontend/app/public/media/app/` — logo, favicon ve marka varlıkları örnek amaçlıdır.
- [ ] `zero-spring/` dizin adı — script bunu değiştirmez. Değiştirirseniz `.github/workflows/ci.yml`
      içinde **43 yol** kırılır (`defaults.run.working-directory` dahil). Kazancı düşük,
      riski yüksek; değiştirmemenizi öneririm.
- [ ] `docs/governance/RISK-REGISTER.md` — devraldığınız bilinen kısıtları okuyun ve kabul edip
      etmediğinize karar verin.
- [ ] Yeni repo kurulumu: `zero-spring/docs/SETUP-NEW-PROJECT.md`.

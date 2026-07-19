## Ne değişti

<!-- Tek paragraf: ne yapıldı ve neden. "Şunu düzelttim" değil, "şu davranış şöyleydi, şimdi böyle". -->

## Kanıt

<!-- Kanıtsız "tamamlandı" yok. Sayı yazın, "geçti" yazmayın. -->

- [ ] `cd zero-spring/backend && ./mvnw -B -ntp clean verify` → ____ test
      <!-- `clean` zorunlu: Maven, geriye giden dosya zaman damgasında derlemeyi atlar ve
           BAYAT bytecode test eder. Bu depoda hem yanlış yeşil hem yanlış kırmızı üretti. -->
- [ ] `cd zero-spring/frontend/app && npm run build && npm run test` → ____ test
- [ ] `bash zero-spring/scripts/ci-local.sh` → geçti / koşulmadı (neden: ____)

**Hata düzeltiyorsanız — negatif kanıt:**

- [ ] Testi önce yazdım ve **eski kodda düştüğünü gördüm**. Çıktı:
      <!-- Düşmüyorsa test yanlış şeyi ölçüyor. Bu satırı atlamak, düzeltmenin
           doğrulanmadığı anlamına gelir. -->

## Kontrol listesi (uygulanabilir olanlar)

- [ ] **Yetki:** yeni uçta `@PreAuthorize` var ve `AppPermissions` sabiti kullanıyor
      (ham string değil — `'users.raed'` derlenir, test geçer, sonsuza dek 403 döner)
- [ ] **Yetki testi:** yetkisiz çağıranın 403 aldığını doğrulayan test var
- [ ] **Kiracılık:** yeni entity `tenant_id` + `@Filter` taşıyor; kiracılar arası **negatif**
      test var (izolasyon açığı 200 döner, pozitif testten kaçar)
- [ ] **Migration:** uygulanmış bir `V<n>__` dosyası **düzenlenmedi**, yeni dosya açıldı
- [ ] **Sayfalama:** `@EntityGraph` ile `Pageable` **aynı metotta değil** (`HHH90003004`)
- [ ] **i18n:** en **ve** tr
- [ ] **Typed client:** backend sözleşmesi değiştiyse `npm run gen:api` koşuldu ve
      `schema.d.ts` bu PR'a dahil
- [ ] **Yeni test/gate eklendiyse:** `gate-auditor` ile korumayı bozup kırmızıya döndüğü
      kanıtlandı

## Kalan risk

<!-- "Yok" yazın ya da yazın — boş bırakmayın. Kapsam dışı bulduğunuz şeyleri
     RISK-REGISTER.md'ye ekleyin, burada düzeltmeyin. -->

# Kalite kapıları — sonuç kaydı

Eşikler `../QUALITY-GATES.md`'de. Bu dosya **ölçümleri** tutar.

> Şablonun kendi inşa sürecinin kayıtları `../history/QUALITY-GATES-RESULTS-template-build.md`
> altında arşivlendi. Devraldığınız temel: backend **349 test** (241 IT + 108 unit),
> frontend **93 test**, CI zinciri 8 kapı.

## Kayıt şablonu

```markdown
## <ne teslim edildi> — <YYYY-AA-GG>

| Kapı | Eşik | Sonuç | Durum |
|---|---|---|---|
| Backend `clean verify` | yeşil | ___ test (___ IT + ___ unit), 0 fail | |
| Frontend build + test | yeşil | ___ test | |
| Typed client senkron | drift yok | `gen:api` sonrası `git diff` boş | |
| Negatif yetki testi | her yeni uçta | | |
| Kiracılar arası negatif test | çok kiracılı her uçta | | |
| Canlı smoke | gerekiyorsa | | |
| Açık kritik/yüksek güvenlik | 0 | | |

**Kalan risk:** (yoksa "yok" yazın — boş bırakmayın)
```

## Kurallar

**"Geçti" bir ölçüm değildir.** Sayı yazın: kaç test koştu, kaç bulgu çıktı, kaç istek atıldı.
Sayı olmadan bir sonraki tur neyin değiştiğini bilemez.

**`clean` zorunludur.** Maven, bir dosyanın zaman damgası geriye gittiğinde (stash pop, dosya
kopyalama, bazı checkout'lar) derlemeyi atlar ve **bayat bytecode** test eder. Bu depoda hem
yanlış yeşil hem yanlış kırmızı üretti.

**Yeşil ≠ doğrulandı.** Bir kapının geçmesi, bir şeyi kontrol ettiği anlamına gelmez. Bu
depoda beş kontrol yeşilken hiçbir şey doğrulamıyordu: modül sınırı testi `package-info`
yazmayan modülü geçiriyordu, migration kapısı boş bir sette yeşil dönüyordu, secret taraması
`.git` içermeyen bir dizini tarıyordu, workflow dosyası repo kökünde olmadığı için hiç
kaydedilmemişti, ve bir test var olmayan bir uca `403` assert ediyordu.

Şüphelendiğinizde: **korumayı bozun ve kapının kırmızıya döndüğünü görün.** `gate-auditor`
ajanı bunun içindir.

**Şema, izin ya da seed değişikliğinde canlı smoke zorunludur.** Temiz veritabanıyla koşan
testler "mevcut kurulum" hatalarını göremez — bu bir kez gerçekleşti: tüm suite yeşilken
çalışan kurulumda host admin 22 iznin 17'sine sahipti ve yeni uçlar 403 dönüyordu.

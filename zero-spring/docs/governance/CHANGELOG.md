# Değişiklik Günlüğü

[Keep a Changelog](https://keepachangelog.com/tr/1.1.0/) formatı ·
[Semantic Versioning](https://semver.org/lang/tr/)

> Bu dosya **sizin projenizin** günlüğüdür. Şablonun kendi inşa süreci
> `../history/CHANGELOG-template-build.md` altında arşivlendi — orada gördüğünüz faz/slice
> kavramları sizi bağlamaz.

## [Yayınlanmamış]

### Eklendi
### Değişti
### Kaldırıldı
### Düzeltildi
### Güvenlik

---

## Nasıl doldurulur

**Kullanıcının gördüğü değişikliği** yazın, commit'i değil. "`UserService` refactor edildi"
bir günlük kaydı değildir; "kullanıcı listesi artık e-postaya göre aranabiliyor" öyledir.

`Güvenlik` başlığı ayrı durur çünkü ayrı okunur: bir sürümü **yükseltme kararı** çoğu zaman
tek başına o başlığa bakılarak verilir. Bir güvenlik düzeltmesi buraya yazılırken, ne
yapılması gerektiği de yazılmalıdır — örneğin "secret rotasyonu şart" ya da "reverse proxy
kuralı eklenmeli".

Kırıcı değişiklikleri `**KIRICI:**` ile işaretleyin ve **göç adımını** yazın. Kırıcı olduğunu
söyleyip nasıl geçileceğini söylememek, okuyucuyu kod okumaya mahkûm eder.

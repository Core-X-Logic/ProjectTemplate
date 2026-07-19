---
name: migration-safety
description: Flyway migration yazarken veya değiştirirken uyulacak kurallar — değişmezlik, geri alınabilirlik, rolling deploy güvenliği. Bir V__*.sql dosyası eklendiğinde/düzenlendiğinde ya da şema değişikliği planlanırken yükle.
---

# Migration güvenliği

Şema değişikliği bu depodaki **geri alınması en pahalı** iş türü. Kod geri alınır; uygulanmış
bir migration geri alınmaz.

## Değişmezlik — tek kural

**Uygulanmış bir `V<n>__*.sql` dosyası asla düzenlenmez.** Flyway her migration'ın checksum'ını
`flyway_schema_history` tablosunda tutar. Dosyayı düzenlersen mevcut kurulumlarda checksum
uyuşmaz ve uygulama **açılışta patlar** — üretimde, deploy sırasında, geri dönüşü elle müdahale.

Değişiklik daima **yeni** bir `V<n+1>__` dosyası.

Tek istisna: şablonun kendisi gibi **hiç deploy edilmemiş** bir depo. O pencere ilk klon prod'a
çıktığında kapanır. Yerinde düzenleme yapıyorsan bunu commit mesajında gerekçelendir ve yerel
DB hacmini yeniden kur (`docker compose down -v`).

CI'daki `migration-drift` gate'i bunu yakalar: önceki sürümün setini uygular, bu commit'in
setini üstüne koyar, `validate` ile checksum drift'i arar.

## Rolling deploy güvenliği

Deploy sırasında **eski ve yeni kod aynı anda** çalışır. Migration ikisiyle de uyumlu olmalı:

| Yapma | Neden | Yerine |
|---|---|---|
| `NOT NULL` kolonu **default'suz** ekleme | Eski kod o kolonu doldurmaz → insert patlar | Önce nullable ekle → doldur → sonraki sürümde `NOT NULL` |
| `DROP COLUMN` / `RENAME` | Eski instance hâlâ o kolonu okuyor/yazıyor | Önce kullanımdan kaldır, bir sürüm bekle, sonra düşür |
| Büyük tabloda kilitleyen `ALTER` | Tablo kilidi = kesinti | `CREATE INDEX CONCURRENTLY`, parça parça backfill |
| Aynı migration'da şema + veri dönüşümü | Uzun sürer, kilit tutar, yarıda kalırsa belirsiz durum | Ayır: şema hızlı, veri ayrı adım |

## Bu depoya özgü

- `ddl-auto=validate` — entity ile şema **birebir** uyuşmalı. Kolon ekleyip entity'ye
  eklememek (ya da tersi) açılışta hata verir. Bu iyi: sessiz sapma yok.
- Zaman kolonları `timestamptz`. Tek belgelenmiş istisna: ShedLock kolonları `timestamp`
  (`usingDbTime()` tz'siz UTC yazar; `timestamptz` kolona yazmak farklı zaman dilimlerindeki
  node'ların **sessizce** farklı instant görmesine yol açıyordu — V6'da düzeltildi).
- PostgreSQL 15+ gerekiyor (`unique nulls not distinct`). `PostgresVersionGuard` açılışta
  kontrol eder ve **fail-closed**'dır (probe başarısız olursa geçirmez).
- Soft delete varsa unique index **partial** olmalı: `where deleted = false`. Aksi hâlde
  silinmiş bir kullanıcı adı yeniden kullanılamaz ve seed açılışta kilitlenebilir.

## Yazmadan önce sor

1. Bu migration daha önce uygulandı mı? (Uygulandıysa yeni dosya aç.)
2. Eski kod bu şemayla çalışır mı? (Rolling deploy.)
3. Geri alma planı ne? (Tersini yapan bir `V<n+1>` yazılabilir mi?)
4. Çok kiracılıysa `tenant_id` + index var mı?
5. Büyük tabloda mı çalışacak? Kilit süresi ne kadar?

## Bitirmeden önce

```
cd zero-spring/backend && ./mvnw -B -ntp clean verify
bash zero-spring/scripts/ci-local.sh migration
```
İkincisi CI'daki drift senaryosunu yerelde koşturur: önceki set → bu set → checksum → idempotency.

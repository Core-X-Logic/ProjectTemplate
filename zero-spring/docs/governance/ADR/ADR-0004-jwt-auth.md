# ADR-0004: Kendi JWT auth (Nimbus) + rotate-eden refresh, kısa access

- **Durum:** Accepted
- **Tarih:** 2026-07-17

## Bağlam

Auth katmanı ya hazır bir kimlik sunucusundan (Keycloak/Auth0) devralınır ya da uygulama içinde
kurulur. Uygulama içinde kurulacaksa asıl karar token ömürleridir: uzun ömürlü refresh token
(yaygın varsayılan: aylar) ile devasa bir saldırı penceresi açılır, ve her istekte token geçerliliğini
veritabanından doğrulamak stateless ölçeklemeyi bitirir. Bu iki tuzağın nerede durulacağı seçilmeli.

## Karar

- Nimbus (`NimbusJwtEncoder` + `ImmutableSecret`) ile **HS512** access token, TTL **15 dk**.
- Refresh token: `SecureRandom` 256-bit raw → client'a; DB'de **SHA-256 hash**; TTL **7 gün**;
  kullanımda **rotate** (eskiyi revoke, yenisini yaz).
- Refresh reuse-detection: revoked token tekrar sunulursa kullanıcının **tüm** refresh'leri iptal (kaskad).
- Lockout: 5 hatalı deneme → 5 dk kilit; sayaç yalnız başarılı login/süre dolumunda sıfırlanır.
- **Sonraki adım (henüz kurulu değil):** RS256 + JWKS endpoint (asimetrik, key rotation).

## Gerekçe

- Kısa access + rotate eden refresh, çalınmış bir token'ın kullanılabilir ömrünü dakikalara indirir.
- Stateful doğrulamadan (her istek DB) vazgeçilerek ölçeklenebilirlik kazanılır; anlık iptal gerekirse
  Redis denylist eklenebilir — bilinçli tradeoff.

## Sonuçlar

- (+) Standart Bearer resource-server; harici IdP (Keycloak) eklemek maliyetsiz.
- (−) Access token TTL'i (≤15 dk) boyunca iptal edilemez (jti denylist henüz yok).
- (+) Refresh rotasyonu atomik (`revokeIfActive` koşullu update) — eşzamanlı iki refresh isteğindeki yarış kapalı.

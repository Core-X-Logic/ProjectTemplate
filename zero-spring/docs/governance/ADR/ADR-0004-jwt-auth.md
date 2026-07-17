# ADR-0004: Kendi JWT auth (Nimbus) + rotate-eden refresh, kısa access

- **Durum:** Accepted
- **Tarih:** 2026-07-17
- **Faz:** F1

## Bağlam

Kaynak sistem gömülü JWT auth kullanıyor ama zayıf varsayılanlarla: HS256, access **1 gün**, refresh
**365 gün** (`AppConsts.cs`). Ayrıca her istekte `TokenValidityKey` (DB/cache) + SecurityStamp doğruluyor
(stateless değil).

## Karar

- Nimbus (`NimbusJwtEncoder` + `ImmutableSecret`) ile **HS512** access token, TTL **15 dk**.
- Refresh token: `SecureRandom` 256-bit raw → client'a; DB'de **SHA-256 hash**; TTL **7 gün**;
  kullanımda **rotate** (eskiyi revoke, yenisini yaz).
- Refresh reuse-detection: revoked token tekrar sunulursa kullanıcının **tüm** refresh'leri iptal (kaskad).
- Lockout: 5 hatalı deneme → 5 dk kilit; sayaç yalnız başarılı login/süre dolumunda sıfırlanır.
- **F4:** RS256 + JWKS endpoint (asimetrik, key rotation).

## Gerekçe

- Kısa access + rotate refresh, 365-günlük refresh gibi devasa saldırı penceresini kapatır.
- Stateful doğrulamadan (her istek DB) vazgeçilerek ölçeklenebilirlik kazanılır; anlık iptal gerekirse
  Redis denylist (F2+) eklenebilir — bilinçli tradeoff.

## Sonuçlar

- (+) Standart Bearer resource-server; harici IdP (Keycloak) eklemek maliyetsiz.
- (−) Access token TTL'i (≤15 dk) boyunca iptal edilemez (jti blacklist F2+ — R-06).
- (+) Refresh rotasyonu atomik (`revokeIfActive` koşullu update) — yarış kapatıldı (F1 güvenlik fix).

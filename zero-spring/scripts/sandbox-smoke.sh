#!/usr/bin/env bash
# TR ödeme sağlayıcıları sandbox smoke'u — PROD-R44 / PROD-R47 kapanış koşusu.
#
# ÖN KOŞULLAR (yalnız operatör sağlayabilir):
#   PayTR : gerçek mağaza hesabı (merchant_id / merchant_key / merchant_salt) — test_mode=1 ile
#           canlı mağazada test işlem yapılır; ayrı sandbox hostu YOKTUR (dev.paytr.com).
#           Mağaza Paneli > Ayarlar > Bildirim URL, aşağıdaki PUBLIC_BASE'e işaret etmeli.
#   iyzico: sandbox merchant kaydı (sandbox-merchant.iyzipay.com) → sandbox- önekli api/secret key.
#           Panel > Ayarlar > İşyeri Bildirimleri, PUBLIC_BASE'e işaret etmeli (HTTPS zorunlu).
#   Tünel : lokal backend'e sağlayıcı webhook'u ulaşabilmeli — ör. `cloudflared tunnel --url
#           http://localhost:8080` çıktısındaki https adresi PUBLIC_BASE olur.
#
# KULLANIM:
#   export ZERO_BILLING_PAYTR_ENABLED=true ZERO_BILLING_PAYTR_MERCHANT_ID=... \
#          ZERO_BILLING_PAYTR_MERCHANT_KEY=... ZERO_BILLING_PAYTR_MERCHANT_SALT=... \
#          ZERO_BILLING_PAYTR_TEST_MODE=true \
#          ZERO_BILLING_IYZICO_ENABLED=true ZERO_BILLING_IYZICO_API_KEY=sandbox-... \
#          ZERO_BILLING_IYZICO_SECRET_KEY=sandbox-... \
#          ZERO_BILLING_IYZICO_BASE_URL=https://sandbox-api.iyzipay.com \
#          PUBLIC_BASE=https://<tunnel-host>
#   # backend'i dev profil + bu env ile AYRI terminalde başlat, readiness bekle, sonra:
#   bash zero-spring/scripts/sandbox-smoke.sh paytr   # veya: iyzico
#
# Script API tarafını otomatik koşar; kart girme adımı doğası gereği İNTERAKTİFTİR
# (3DS/checkout formu tarayıcıda). Test kartları: PayTR 4355 0843 5508 4358 (CVV 000),
# iyzico sandbox kart listesi docs.iyzico.com/on-hazirliklar/sandbox.
set -euo pipefail

BASE="${BASE:-http://localhost:8080}"
PROVIDER="${1:?kullanım: sandbox-smoke.sh paytr|iyzico}"
HOST_USER="${HOST_USER:-admin}"
HOST_PASS="${HOST_PASS:-Admin123!}"

say()  { printf '\n== %s\n' "$*"; }
fail() { printf 'SMOKE FAILED: %s\n' "$*" >&2; exit 1; }

say "1/6 host login"
TOKEN=$(curl -sf -X POST "$BASE/api/auth/login" -H 'Content-Type: application/json' \
  -d "{\"username\":\"$HOST_USER\",\"password\":\"$HOST_PASS\"}" | python -c 'import sys,json;print(json.load(sys.stdin)["accessToken"])') \
  || fail "host login"
AUTH="Authorization: Bearer $TOKEN"

say "2/6 hedef tenant + edition seç"
TENANT_ID="${TENANT_ID:?TENANT_ID env ver (subscriptions listesinden)}"
EDITION_ID="${EDITION_ID:?EDITION_ID env ver}"

say "3/6 checkout başlat ($PROVIDER)"
CHECKOUT=$(curl -sf -X POST "$BASE/api/billing/checkout" -H "$AUTH" -H 'Content-Type: application/json' \
  -d "{\"tenantId\":$TENANT_ID,\"editionId\":$EDITION_ID,\"billingPeriod\":\"MONTHLY\",
       \"successUrl\":\"${PUBLIC_BASE:-$BASE}/payment/result/success\",
       \"cancelUrl\":\"${PUBLIC_BASE:-$BASE}/payment/result/cancel\",\"provider\":\"$PROVIDER\"}") \
  || fail "checkout çağrısı (sağlayıcı enabled mı? kimlik bilgileri doğru mu?)"
PAYMENT_ID=$(echo "$CHECKOUT" | python -c 'import sys,json;print(json.load(sys.stdin)["paymentId"])')
URL=$(echo "$CHECKOUT" | python -c 'import sys,json;print(json.load(sys.stdin)["url"])')
echo "payment=$PAYMENT_ID"
echo ">>> TARAYICIDA AÇ ve test kartıyla öde: $URL"

say "4/6 NEGATİF: bozuk imzalı webhook -> 400 beklenir, hiçbir şey saklanmaz"
CODE=$(curl -s -o /dev/null -w '%{http_code}' -X POST "$BASE/api/billing/webhook/$PROVIDER" \
  -H 'Content-Type: application/json' -H 'X-IYZ-SIGNATURE-V3: deadbeef' \
  --data-urlencode 'merchant_oid=BOGUS123' --data-urlencode 'status=success' \
  --data-urlencode 'total_amount=100' --data-urlencode 'hash=Qm9ndXM=')
[ "$CODE" = "400" ] || fail "bozuk imza $CODE döndü (400 beklenir)"
echo "OK: 400"

say "5/6 ödeme PAID olana dek bekle (webhook/mutabakat; 10 dk tavan)"
for i in $(seq 1 60); do
  STATUS=$(curl -sf "$BASE/api/subscriptions/$TENANT_ID" -H "$AUTH" \
    | python -c 'import sys,json;d=json.load(sys.stdin);print(d.get("status",""))' || true)
  echo "  t+$((i*10))s subscription=$STATUS"
  [ "$STATUS" = "ACTIVE" ] && break
  sleep 10
done
[ "$STATUS" = "ACTIVE" ] || fail "10 dk içinde ACTIVE olmadı — tünel/Bildirim URL'ini ve panelin failed-webhooks listesini kontrol et (runbook 3.9)"

say "6/6 NEGATİF: callback-only yolun aktive ETMEDİĞİNİ panel + audit ile teyit et"
cat <<'EOF'
  - Sağlayıcı panelinde bildirimin TESLİM EDİLDİĞİNİ gör (retry listesinde olmamalı).
  - subscription_events'te tek ACTIVE geçişi olmalı (duplicate teslimat ikinci satır üretmemeli):
      docker compose exec postgres psql -U zero -d zero -c \
        "select count(*) from subscription_events where tenant_id=<TENANT_ID> and to_status='ACTIVE';"
  - iyzico için: panelden aynı bildirimi RESEND et -> uygulama 200 döner, sayaç ARTMAMALI.
EOF
echo "SMOKE PASSED (interaktif adımlar dahil) — sonucu QUALITY-GATES-RESULTS'a işle, PROD-R44/R47 kapat."

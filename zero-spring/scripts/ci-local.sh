#!/usr/bin/env bash
#
# CI gate'lerini LOKALDE koşturur — push etmeden önce.
#
# NEDEN VAR: bu depo private ve Actions dakikası sınırlı; zincir 8 job açıyor, üçü Postgres
# ayağa kaldırıp jar boot ediyor. İlk üç CI koşusunun ikisi CI-config hatasıyla düştü
# (hazır-olma kontrolü aggregate `/actuator/health`'a bakıyordu, oysa o job'larda Redis yok)
# — ikisi de burada, bedava ve saniyeler içinde yakalanabilirdi. Push-fix-push döngüsü
# hem pahalı hem yavaş; asıl geri bildirim döngüsü bu olmalı.
#
# NE KAPSAR: jar'ı gerçekten boot eden ve HTTP üzerinden assert eden gate'ler —
# typed-client-drift'in hazır-olma adımı, live-smoke'un kritik akışları, security-checks'in
# desen taraması, ve migration-drift'in "mevcut kurulum üstüne migrate" senaryosu.
#
# NE KAPSAMAZ (ve neden): `mvnw clean verify` ve `npm test` burada TEKRAR koşulmaz — onları
# zaten doğrudan koşuyorsunuz ve iki kez koşturmak bu script'i kimsenin beklemek istemeyeceği
# kadar yavaşlatır. Bu script "CI'a özgü" kısmı doğrular: boot, uçlar, yollar, sıralama.
#
# KULLANIM:
#   bash zero-spring/scripts/ci-local.sh            # hepsi
#   bash zero-spring/scripts/ci-local.sh smoke      # tek gate: readiness | smoke | secrets | migration
#
# ÖN KOŞUL: docker, ve `zero-spring/backend/target/app.jar` (yoksa `./mvnw -DskipTests package`).

set -uo pipefail

# Windows/Git Bash: `docker -v` argümanlarında MSYS yol çevirisini kapat, yoksa
# /repo -> C:/Program Files/Git/repo olur ve mount sessizce yanlış yere bağlanır.
export MSYS_NO_PATHCONV=1

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
BACKEND="${REPO_ROOT}/zero-spring/backend"

# Bu sablondan turetilmis IKI klon ayni makinede olabilir. Konteyner adlari Docker'da
# GLOBALDIR ve bu script basta `docker rm -f` ediyor — sabit bir ad kullanilsaydi, ikinci
# klonda ci-local.sh calistirmak BIRINCININ konteynerini oldururdu. Sessiz ve kafa karistirici
# bir mod: "testim neden yarida dustu?" Ad ve log dizini bu yuzden depo yoluna baglaniyor.
PROJECT_ID="$(printf '%s' "${REPO_ROOT}" | cksum | cut -d' ' -f1)"
LOGDIR="${TMPDIR:-/tmp}/ci-local-${PROJECT_ID}"
mkdir -p "${LOGDIR}"

PG_CONTAINER="ci-local-pg-${PROJECT_ID}"
# Portlar ise makine genelinde tekil olmak zorunda ve otomatik turetilemez (cakisma
# ihtimalini hesaplamaktansa acikca override edilebilir olmasi daha durust). Iki klonu
# ayni anda kosturacaksan ikincisine farkli deger ver:
#   CI_LOCAL_PG_PORT=5545 CI_LOCAL_APP_PORT=8081 bash zero-spring/scripts/ci-local.sh
PG_PORT="${CI_LOCAL_PG_PORT:-5544}"
APP_PORT="${CI_LOCAL_APP_PORT:-8080}"

DB_URL="jdbc:postgresql://localhost:${PG_PORT}/zero"
DB_USER=zero
DB_PASSWORD=zero
SEED_ADMIN_PASSWORD='Ci-Smoke-Passw0rd!'
APP_PID=""

FAILURES=0
pass() { printf '  \033[32mPASS\033[0m  %s\n' "$1"; }
fail() { printf '  \033[31mFAIL\033[0m  %s\n' "$1"; FAILURES=$((FAILURES + 1)); }
step() { printf '\n\033[1m== %s\033[0m\n' "$1"; }

cleanup() {
  [ -n "${APP_PID}" ] && kill "${APP_PID}" 2>/dev/null
  docker rm -f "${PG_CONTAINER}" >/dev/null 2>&1
}
trap cleanup EXIT

# R-01b: jar adı artifactId'den TÜRETİLMEZ — `pom.xml`'de <finalName>app</finalName> sabit.
# Eski hâl `ls target/zero-platform-*.jar` globuydu: bu depo bir şablon ve klonlayan
# artifactId'yi değiştirdiğinde glob eşleşmeyi keser, fonksiyon sessizce boş döner.
# Boş dönüş burada zararsız (start_app aşağıda açık mesajla düşüyor), ama aynı desen
# CI'da gerçekten sessizdi. Sabit ad bağı tamamen koparır.
jar_path() {
  local jar="${BACKEND}/target/app.jar"
  [ -f "${jar}" ] && printf '%s\n' "${jar}"
}

start_pg() {
  docker rm -f "${PG_CONTAINER}" >/dev/null 2>&1
  docker run -d --name "${PG_CONTAINER}" \
    -e POSTGRES_DB=zero -e POSTGRES_USER=zero -e POSTGRES_PASSWORD=zero \
    -p "${PG_PORT}:5432" postgres:16 >/dev/null
  for _ in $(seq 1 40); do
    docker exec "${PG_CONTAINER}" pg_isready -U zero >/dev/null 2>&1 && return 0
    sleep 1
  done
  fail "Postgres ${PG_PORT} portunda hazır olmadı"
  return 1
}

start_app() {
  local jar; jar="$(jar_path)"
  if [ -z "${jar}" ]; then
    fail "jar yok — önce: cd zero-spring/backend && ./mvnw -B -ntp -DskipTests package"
    return 1
  fi
  JWT_SECRET="$(head -c 64 /dev/urandom | base64 | tr -d '\n')" \
  SPRING_PROFILES_ACTIVE=dev \
  DB_URL="${DB_URL}" DB_USER="${DB_USER}" DB_PASSWORD="${DB_PASSWORD}" \
  SEED_ADMIN_PASSWORD="${SEED_ADMIN_PASSWORD}" \
  REDIS_PORT=1 \
    java -jar "${jar}" > "${LOGDIR}/backend.log" 2>&1 &
  APP_PID=$!
}

# --- GATE: hazır-olma -------------------------------------------------------
# Bu, CI'ı iki kez düşüren tam senaryodur ve bilerek REDIS_PORT=1 ile koşulur:
# CI job'larında Redis servisi YOK. Aggregate /actuator/health redis'i aktif olarak
# yokladığı için 503 döner; readiness (readinessState + db) UP kalmalıdır. Yani bu
# adım aynı anda PROD-R29'un canlı kanıtıdır — Redis düştüğünde trafik kesilmez.
gate_readiness() {
  step "GATE: readiness (Redis KASITLI olarak erişilemez)"
  start_pg || return
  start_app || return

  local ready=0
  for _ in $(seq 1 90); do
    if curl -sf "http://localhost:${APP_PORT}/actuator/health/readiness" >/dev/null 2>&1; then
      ready=1; break
    fi
    sleep 1
  done
  if [ "${ready}" = 1 ]; then
    pass "Redis erişilemezken /actuator/health/readiness UP"
  else
    fail "readiness 90 sn içinde UP olmadı"
    tail -n 40 "${LOGDIR}/backend.log"
    return
  fi

  local agg; agg=$(curl -s -o /dev/null -w '%{http_code}' "http://localhost:${APP_PORT}/actuator/health")
  if [ "${agg}" = "503" ]; then
    pass "aggregate /actuator/health 503 (redis DOWN) — CI'ın buna BAKMAMASININ sebebi"
  else
    printf '  \033[33mNOT\033[0m   aggregate /actuator/health = %s (503 bekleniyordu)\n' "${agg}"
  fi
}

# --- GATE: live-smoke -------------------------------------------------------
gate_smoke() {
  step "GATE: live-smoke (kritik akışlar)"
  local base="http://localhost:${APP_PORT}"
  local code token tenant_token

  code=$(curl -s -o "${LOGDIR}/login.json" -w '%{http_code}' -X POST "${base}/api/auth/login" \
    -H 'Content-Type: application/json' \
    -d "{\"usernameOrEmail\":\"admin\",\"password\":\"${SEED_ADMIN_PASSWORD}\"}")
  [ "${code}" = "200" ] && pass "host login 200" || { fail "host login ${code}"; cat "${LOGDIR}/login.json"; return; }
  token=$(sed -n 's/.*"accessToken":"\([^"]*\)".*/\1/p' "${LOGDIR}/login.json")

  code=$(curl -s -o "${LOGDIR}/me.json" -w '%{http_code}' "${base}/api/auth/me" -H "Authorization: Bearer ${token}")
  [ "${code}" = "200" ] && pass "host /api/auth/me 200" || fail "host /me ${code}"
  grep -q '"tenantId":null' "${LOGDIR}/me.json" \
    && pass "host tenantId null" || fail "host tenantId null değil"

  code=$(curl -s -o /dev/null -w '%{http_code}' "${base}/api/editions" -H "Authorization: Bearer ${token}")
  [ "${code}" = "200" ] && pass "host GET /api/editions 200" || fail "editions ${code}"

  # NEGATİF: host token + X-Tenant uyuşmazlığı 403 olmalı. Bir izolasyon açığı 200 döner
  # ve pozitif testlerden kaçar — bu yüzden burada.
  code=$(curl -s -o /dev/null -w '%{http_code}' "${base}/api/editions" \
    -H "Authorization: Bearer ${token}" -H 'X-Tenant: default')
  [ "${code}" = "403" ] && pass "NEGATİF host token + X-Tenant → 403" || fail "tenant mismatch ${code} (403 bekleniyordu)"

  code=$(curl -s -o "${LOGDIR}/tl.json" -w '%{http_code}' -X POST "${base}/api/auth/login" \
    -H 'Content-Type: application/json' -H 'X-Tenant: default' \
    -d "{\"usernameOrEmail\":\"admin\",\"password\":\"${SEED_ADMIN_PASSWORD}\"}")
  [ "${code}" = "200" ] && pass "tenant login 200" || fail "tenant login ${code}"
  tenant_token=$(sed -n 's/.*"accessToken":"\([^"]*\)".*/\1/p' "${LOGDIR}/tl.json")

  code=$(curl -s -o /dev/null -w '%{http_code}' "${base}/api/editions" \
    -H "Authorization: Bearer ${tenant_token}" -H 'X-Tenant: default')
  [ "${code}" = "403" ] && pass "NEGATİF tenant → host-only uç 403" || fail "tenant escalation ${code}"

  # PROD-R17: kimlikli ama yetkisiz principal actuator okuyamaz.
  code=$(curl -s -o /dev/null -w '%{http_code}' "${base}/actuator/prometheus" \
    -H "Authorization: Bearer ${tenant_token}" -H 'X-Tenant: default')
  [ "${code}" = "403" ] && pass "NEGATİF tenant → /actuator/prometheus 403" || fail "actuator ${code} (403 bekleniyordu)"

  # F1: kimlikli uçta sınır üstü gövde.
  code=$(python -c "print('{\"username\":\"pad\",\"pad\":\"' + 'A'*1500000 + '\"}')" 2>/dev/null \
    | curl -s -o /dev/null -w '%{http_code}' -X POST "${base}/api/users" \
      -H "Authorization: Bearer ${token}" -H 'Content-Type: application/json' --data-binary @-)
  [ "${code}" = "413" ] && pass "F1 1.5MB gövde → 413" || fail "oversized body ${code} (413 bekleniyordu)"
}

# --- GATE: security-checks (desen taraması) ---------------------------------
gate_secrets() {
  step "GATE: security-checks (bloklayıcı desen taraması)"
  cd "${REPO_ROOT}/zero-spring" || return
  local hit=0
  for p in '-----BEGIN (RSA|EC|DSA|OPENSSH|PGP)? ?PRIVATE KEY-----' \
           'A(KIA|SIA|ROA)[0-9A-Z]{16}' \
           'xox[baprs]-[0-9A-Za-z-]{10,}' \
           'gh[pousr]_[0-9A-Za-z]{36}' \
           'AIza[0-9A-Za-z_-]{35}'; do
    if grep -rInE --exclude-dir=.git --exclude-dir=node_modules --exclude-dir=target \
         --exclude='*.lock' --exclude='package-lock.json' \
         -- "${p}" . "${REPO_ROOT}/.github" >/dev/null 2>&1; then
      fail "secret deseni eşleşti: ${p}"; hit=1
    fi
  done
  [ "${hit}" = 0 ] && pass "secret desenleri temiz (kök .github dahil)"

  local prod_yml=backend/src/main/resources/application-prod.yml
  if [ -f "${prod_yml}" ]; then
    if grep -InE '^[[:space:]]*(secret|password|host-admin-password|token|api-key):[[:space:]]*[^$[:space:]]' "${prod_yml}" >/dev/null; then
      fail "${prod_yml} içinde literal secret"
    else
      pass "application-prod.yml secret'ları env referanslı"
    fi
  else
    fail "${prod_yml} bulunamadı — kontrol koşamadı"
  fi
  cd "${REPO_ROOT}" || return
}

# --- GATE: migration-drift --------------------------------------------------
# CI'daki ile aynı senaryo: önceki sürümün seti uygulanır (= mevcut kurulum), sonra
# bu commit'in seti üstüne konur. Checksum drift burada düşer.
gate_migration() {
  step "GATE: migration-drift (mevcut kurulum üstüne migrate)"
  start_pg || return
  local migdir="zero-spring/backend/src/main/resources/db/migration"
  local oldset="${LOGDIR}/oldset"
  rm -rf "${oldset}"; mkdir -p "${oldset}"

  cd "${REPO_ROOT}" || return
  if git archive HEAD^ "${migdir}" 2>/dev/null | tar -x -C "${oldset}" --strip-components=6; then
    pass "önceki set çıkarıldı ($(ls -1 "${oldset}" | wc -l) dosya)"
  else
    printf '  \033[33mNOT\033[0m   HEAD^ içinde migration yok; yalnız temiz kurulum denenecek\n'
  fi

  local flyway="docker run --rm --network host -v"
  if [ -n "$(ls -A "${oldset}" 2>/dev/null)" ]; then
    $flyway "${oldset}:/flyway/sql:ro" flyway/flyway:11-alpine \
      -url="${DB_URL}" -user="${DB_USER}" -password="${DB_PASSWORD}" -connectRetries=10 migrate \
      >"${LOGDIR}/fw1.log" 2>&1 \
      && pass "önceki set uygulandı" || { fail "önceki set uygulanamadı"; tail -5 "${LOGDIR}/fw1.log"; }

    $flyway "${REPO_ROOT}/${migdir}:/flyway/sql:ro" flyway/flyway:11-alpine \
      -url="${DB_URL}" -user="${DB_USER}" -password="${DB_PASSWORD}" -connectRetries=10 \
      -ignoreMigrationPatterns='*:pending' validate \
      >"${LOGDIR}/fw2.log" 2>&1 \
      && pass "checksum drift yok" || { fail "CHECKSUM DRIFT — yayınlanmış bir migration değiştirilmiş"; tail -10 "${LOGDIR}/fw2.log"; }
  fi

  $flyway "${REPO_ROOT}/${migdir}:/flyway/sql:ro" flyway/flyway:11-alpine \
    -url="${DB_URL}" -user="${DB_USER}" -password="${DB_PASSWORD}" -connectRetries=10 migrate \
    >"${LOGDIR}/fw3.log" 2>&1 \
    && pass "dolu şema üstüne migrate" || { fail "migrate düştü"; tail -10 "${LOGDIR}/fw3.log"; }

  $flyway "${REPO_ROOT}/${migdir}:/flyway/sql:ro" flyway/flyway:11-alpine \
    -url="${DB_URL}" -user="${DB_USER}" -password="${DB_PASSWORD}" -connectRetries=10 migrate \
    >"${LOGDIR}/fw4.log" 2>&1
  if grep -qE "already up to date|No migration necessary|is up to date" "${LOGDIR}/fw4.log"; then
    pass "ikinci migrate no-op (idempotent)"
  else
    fail "ikinci migrate no-op değil"; tail -10 "${LOGDIR}/fw4.log"
  fi

  start_app || return
  local ok=0
  for _ in $(seq 1 90); do
    curl -sf "http://localhost:${APP_PORT}/actuator/health/readiness" >/dev/null 2>&1 && { ok=1; break; }
    sleep 1
  done
  [ "${ok}" = 1 ] && pass "jar yükseltilmiş şemaya karşı boot etti (ddl-auto=validate)" \
                  || { fail "boot edemedi — ddl-auto=validate uyumsuzluğu olabilir"; tail -n 40 "${LOGDIR}/backend.log"; }
}

case "${1:-all}" in
  readiness) gate_readiness ;;
  smoke)     gate_readiness; gate_smoke ;;
  secrets)   gate_secrets ;;
  migration) gate_migration ;;
  all)       gate_secrets; gate_migration; cleanup; APP_PID=""; gate_readiness; gate_smoke ;;
  *) echo "kullanım: $0 [all|readiness|smoke|secrets|migration]"; exit 2 ;;
esac

printf '\n'
if [ "${FAILURES}" -ne 0 ]; then
  printf '\033[31m%s gate adımı düştü — push ETMEYİN\033[0m\n' "${FAILURES}"
  exit 1
fi
printf '\033[32mLokal gate kontrolleri temiz.\033[0m Bu, CI'\''nin yeşil olacağını GARANTİ ETMEZ:\n'
printf 'runner Linux, burası Windows; ve `mvnw verify` ile `npm test` bu script'\''in kapsamı dışında.\n'

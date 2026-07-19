#!/usr/bin/env bash
#
# Sablonu kendi projenize cevirir.
#
#   ./scripts/rename-project.sh <groupId> <artifactId> ["Gorunen Ad"]
#   ornek: ./scripts/rename-project.sh com.acme.crm acme-crm "Acme CRM"
#
# NEDEN BIR SCRIPT: yeniden adlandirma yuzeyi 293 dosyada 722 gecis. Elle yapilacak bir is
# degil; ve daha kotusu, YARIM yapildiginda cogu yerde HATA VERMEZ (bkz. asagidaki not).
#
# NEDEN "zero" KELIMESINI TOPLU DEGISTIRMIYOR: `zero` bu depoda UC ayri ad uzayinda geciyor
# ve toplu degistirme ucunu birden bozar:
#   1. Java paketi     com.mycompanyname.zero   -> DEGISTIRILIR
#   2. Config prefix   zero.jwt / zero.cors ...  -> ASLA DOKUNULMAZ
#   3. DB adi/kullanici zero                     -> ayri karar, bu script degistirmez
# (2) en tehlikelisi: `@ConfigurationProperties(prefix="zero.jwt")` ile yml anahtari birlikte
# degismezse Spring HATA VERMEZ — binding sessizce default'lara duser. `zero.seed.enabled`
# default'u false, `zero.ratelimit.enabled` default'u true; yani sessiz DAVRANIS degisimi.
# Bu yuzden script yalnizca TAM DIZGELERI hedefler, kelimeleri degil.

set -euo pipefail

die() { printf '\033[31mHATA\033[0m %s\n' "$1" >&2; exit 1; }
info() { printf '\033[36m»\033[0m %s\n' "$1"; }
ok() { printf '  \033[32m✓\033[0m %s\n' "$1"; }

# --- girdi ------------------------------------------------------------------

[ $# -ge 2 ] || die "kullanim: $0 <groupId> <artifactId> [\"Gorunen Ad\"]
  ornek: $0 com.acme.crm acme-crm \"Acme CRM\""

NEW_GROUP="$1"
NEW_ARTIFACT="$2"
NEW_DISPLAY="${3:-$2}"

printf '%s' "${NEW_GROUP}" | grep -qE '^[a-z][a-z0-9_]*(\.[a-z][a-z0-9_]*)+$' \
  || die "groupId gecersiz: '${NEW_GROUP}'. Kucuk harf, nokta ayrik, en az iki parca. Ornek: com.acme.crm"
printf '%s' "${NEW_ARTIFACT}" | grep -qE '^[a-z][a-z0-9-]*$' \
  || die "artifactId gecersiz: '${NEW_ARTIFACT}'. Kucuk harf, rakam ve tire. Ornek: acme-crm"

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "${REPO_ROOT}"

command -v git >/dev/null || die "git bulunamadi"
[ -d .git ] || die "burasi bir git deposu degil: ${REPO_ROOT}"
[ -z "$(git status --porcelain)" ] || die "calisma agaci temiz degil. Once commit ya da stash edin —
bu script agaci genis capta degistirir ve geri almanin tek makul yolu 'git checkout .'"

OLD_GROUP="com.mycompanyname.zero"
OLD_GROUP_PATH="com/mycompanyname/zero"
NEW_GROUP_PATH="${NEW_GROUP//.//}"
OLD_ARTIFACT="zero-platform"
OLD_DISPLAY="Zero Platform"

info "Yeniden adlandirma"
printf '   paket    : %s -> %s\n' "${OLD_GROUP}" "${NEW_GROUP}"
printf '   artifact : %s -> %s\n' "${OLD_ARTIFACT}" "${NEW_ARTIFACT}"
printf '   gorunen  : %s -> %s\n\n' "${OLD_DISPLAY}" "${NEW_DISPLAY}"

# `history/` DISARIDA: sablonun turetildigi gocun arsivi. Oradaki metinler tarihsel kayittir;
# yeniden adlandirmak onlari yanlis yapar (o goc gercekten `zero-platform` uzerinde yasandi).
# Script kendini de haric tutar: kendi aciklamalarinda eski adlar geciyor (neyin neden
# degistigini anlatabilmek icin) ve bunlar kalinti degil, belge.
EXCLUDES=(':!zero-spring/docs/history' ':!scripts/rename-project.sh' ':!RENAME.md'
          ':!*/node_modules/*' ':!*/target/*')

# Degistirilecek degerler perl programinin METNINE gomulmez, ortam degiskeniyle gecirilir.
# Gerekcesi olculdu: ilk surum `perl -pi -e "s/\Q${old}\E/${new}/g"` kullaniyordu ve YOL
# bicimindeki degerde (com/mycompanyname/zero) egik cizgiler s/// sinirlayicisiyla catisip
# programi derlenemez hale getiriyordu. Degerler ENV'den gelince program metni sabit kalir
# ve icerikteki /, $, \ gibi karakterler sorun cikarmaz.
#
# `while ... <<<` (boru DEGIL): boru bir alt kabuk acar ve icerideki hata `set -e`'ye
# yayilmaz — ilk surumde perl her dosyada patlarken script "basarili" devam ediyordu.
sed_all() { # sed_all <eski> <yeni>
  local files f
  export SED_OLD="$1" SED_NEW="$2"
  files="$(git grep -lF "${SED_OLD}" -- . "${EXCLUDES[@]}" || true)"
  [ -n "${files}" ] || return 0
  while IFS= read -r f; do
    [ -f "$f" ] || continue
    perl -pi -e 'BEGIN { $o = quotemeta($ENV{SED_OLD}); $n = $ENV{SED_NEW} } s/$o/$n/g' "$f" \
      || die "degistirme basarisiz: $f  ('${SED_OLD}' -> '${SED_NEW}')"
  done <<< "${files}"
}

# --- 1) Java paket agaci ----------------------------------------------------

info "1/8  Java paket agaci tasiniyor"
for src in zero-spring/backend/src/main/java zero-spring/backend/src/test/java; do
  [ -d "${src}/${OLD_GROUP_PATH}" ] || continue
  mkdir -p "${src}/$(dirname "${NEW_GROUP_PATH}")"
  git mv "${src}/${OLD_GROUP_PATH}" "${src}/${NEW_GROUP_PATH}"
  # Eski agacta bos kalan ust dizinleri temizle (com/mycompanyname gibi).
  find "${src}" -type d -empty -delete 2>/dev/null || true
done
ok "dizinler tasindi"

sed_all "${OLD_GROUP}" "${NEW_GROUP}"
sed_all "${OLD_GROUP_PATH}" "${NEW_GROUP_PATH}"
ok "paket referanslari guncellendi ($(git grep -cF "${NEW_GROUP}" -- '*.java' 2>/dev/null | wc -l) java dosyasi)"

# --- 2) Maven + jar ---------------------------------------------------------

info "2/8  Maven koordinatlari"
perl -pi -e "s{<groupId>com\.mycompanyname</groupId>}{<groupId>${NEW_GROUP%.*}</groupId>}" \
  zero-spring/backend/pom.xml
sed_all "${OLD_ARTIFACT}" "${NEW_ARTIFACT}"
ok "groupId/artifactId guncellendi"
# Jar adi zaten <finalName>app</finalName> ile sabitlendigi icin Dockerfile/CI'a dokunmak
# gerekmiyor — bu, R-01b'de bilerek yapildi.

# --- 3) Ana sinif adi -------------------------------------------------------

# artifactId'den PascalCase turet: acme-crm -> AcmeCrm -> AcmeCrmApplication.
# Bu adim klon testinde bulundu: paket adi degistikten SONRA bile Spring Boot giris sinifi
# `com.acme.crm.ZeroPlatformApplication` olarak kaliyordu. Derlemeyi kirmaz, ama sablonun
# adini projenin en gorunur sinifinda birakir.
info "3/8  Ana sinif adi"
NEW_CLASS_BASE="$(printf '%s' "${NEW_ARTIFACT}" | perl -pe 's/(^|-)([a-z0-9])/\u$2/g')"
NEW_MAIN_CLASS="${NEW_CLASS_BASE}Application"
OLD_MAIN_CLASS="ZeroPlatformApplication"

MAIN_SRC="zero-spring/backend/src/main/java/${NEW_GROUP_PATH}/${OLD_MAIN_CLASS}.java"
if [ -f "${MAIN_SRC}" ]; then
  git mv "${MAIN_SRC}" "zero-spring/backend/src/main/java/${NEW_GROUP_PATH}/${NEW_MAIN_CLASS}.java"
  sed_all "${OLD_MAIN_CLASS}" "${NEW_MAIN_CLASS}"
  ok "${OLD_MAIN_CLASS} -> ${NEW_MAIN_CLASS}"
else
  printf '  \033[33m!\033[0m %s bulunamadi, ana sinif adi degistirilmedi\n' "${OLD_MAIN_CLASS}"
fi

# --- 4) Gorunen ad ----------------------------------------------------------

info "4/8  Gorunen ad"
sed_all "${OLD_DISPLAY}" "${NEW_DISPLAY}"
ok "gorunen ad guncellendi (index.html, i18n, e-posta sablonlari, admin layout)"

# --- 4) Taze gelistirme sirlari ---------------------------------------------

info "5/8  Dev/test JWT anahtarlari yenileniyor"
# Sablona commit'li bir anahtar, tanimi geregi HERKESE aciktir. Onemli olan rotasyon degil,
# bu anahtarlarin prod'da REDDEDILMESI: JwtSecretValidator commit'li anahtarlari 'prod'
# profilinde reddeder. Yeni anahtar uretiliyorsa o listenin de birlikte guncellenmesi SART —
# yoksa koruma sessizce FAIL-OPEN olur: yeni commit'li anahtar prod'da artik engellenmez.
# Uc dosya (dev yml, test yml, validator) ve .gitleaks.toml allowlist'i birlikte hareket eder.
gen_secret() { head -c 64 /dev/urandom | base64 | tr -d '\n'; }
NEW_DEV_SECRET="$(gen_secret)"
NEW_TEST_SECRET="$(gen_secret)"

OLD_DEV_SECRET="$(grep -oE '[A-Za-z0-9+/=]{80,}' zero-spring/backend/src/main/resources/application-dev.yml | head -1 || true)"
OLD_TEST_SECRET="$(grep -oE '[A-Za-z0-9+/=]{80,}' zero-spring/backend/src/test/resources/application-test.yml | head -1 || true)"

if [ -n "${OLD_DEV_SECRET}" ] && [ -n "${OLD_TEST_SECRET}" ]; then
  sed_all "${OLD_DEV_SECRET}" "${NEW_DEV_SECRET}"
  sed_all "${OLD_TEST_SECRET}" "${NEW_TEST_SECRET}"
  ok "dev/test anahtarlari yenilendi; validator ve gitleaks allowlist'i birlikte guncellendi"
else
  printf '  \033[33m!\033[0m dev/test anahtari bulunamadi — ELLE kontrol edin:\n'
  printf '      application-dev.yml, application-test.yml, JwtSecretValidator, .gitleaks.toml\n'
fi

# --- 5) Dokumanlar ----------------------------------------------------------

info "6/8  Dokumanlar"
ok "docs/history/ DISARIDA birakildi (tarihsel kayit; yeniden adlandirmak onu yanlis yapar)"

# --- 6) Kalinti taramasi ----------------------------------------------------

info "7/8  Kalinti taramasi"
LEFTOVER="$(git grep -nE "mycompanyname|${OLD_ARTIFACT}|${OLD_DISPLAY}" -- . "${EXCLUDES[@]}" || true)"
if [ -n "${LEFTOVER}" ]; then
  printf '  \033[33m!\033[0m Kalan gecisler (cogu yorum/aciklama olabilir, gozden gecirin):\n'
  printf '%s\n' "${LEFTOVER}" | head -30 | sed 's/^/      /'
else
  ok "kalinti yok"
fi

printf '  \033[36mi\033[0m DOKUNULMADI (bilerek): `zero.*` config prefix, DB adi/kullanici `zero`,\n'
printf '      Hikari pool adi. Gerekcesi bu dosyanin basinda; degistirmek isterseniz\n'
printf '      RENAME.md "Elle yapilacaklar" bolumunu okuyun.\n'

# --- 7) Dogrulama kapisi ----------------------------------------------------

info "8/8  Dogrulama"
printf '  Simdi sunlari kosun — yeniden adlandirmanin SESSIZ kirilmalari ancak burada gorunur:\n\n'
cat <<'GATE'
    cd zero-spring/backend && ./mvnw -B -ntp clean verify
      -> BUILD SUCCESS ve su dort test ADIYLA yesil olmali:
         EntityHistoryIT     (entity history hala kayit tutuyor mu)
         ModularityTests     (modul sinirlari bozulmadi mi)
         EmailDispatchIT     (e-posta sablonlari ve gonderen kimligi)
         TenantIsolationIT   (kiraci izolasyonu)

    docker build -t <yeni-ad> zero-spring/backend/
      -> jar adi <finalName>app</finalName> ile sabit; COPY kirilmamali

    cd zero-spring/frontend/app && npm ci && npm run build && npm run test

GATE
printf '  Sonra: \033[1mgit diff --stat\033[0m ile degisikligi gozden gecirip commit edin.\n'
printf '  Geri almak icin: \033[1mgit checkout . && git clean -fd\033[0m\n'

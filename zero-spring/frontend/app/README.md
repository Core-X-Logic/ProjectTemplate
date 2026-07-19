# Admin arayüzü

React 19 · Vite 7 · TypeScript (strict) · Tailwind 4 · shadcn/ui · TanStack Query v5 ·
react-router 7 · react-hook-form + zod · react-intl

```bash
cp .env.example .env      # VITE_API_BASE_URL zorunlu
npm ci
npm run dev               # http://localhost:5173
```

Backend'in ayakta olması gerekir (`cd ../../backend && ./mvnw spring-boot:run -Dspring-boot.run.profiles=dev`).

## Ortam değişkenleri

| Değişken | Zorunlu | Not |
|---|---|---|
| `VITE_API_BASE_URL` | **dev'de evet** | Boşsa uygulama dev modunda **açık bir hatayla düşer**. Üretimde boş bırakmak **geçerlidir**: reverse proxy arkasında tek origin kurulumunda API göreli yoldan servis edilir |
| `VITE_DEFAULT_LOCALE` | hayır | `en` \| `tr` |

Vite değişkenleri **build-time**'dır: imaj/`dist` üretilirken gömülür, çalışma anında değişmez.

## Komutlar

```bash
npm run dev      # geliştirme sunucusu
npm run build    # tsc -b + vite build (typecheck DAHIL)
npm run test     # vitest
npm run gen:api  # backend'den typed client uret (backend dev profilde ayakta olmali)
```

`npm run test` **tip hatasını yakalamaz** — typecheck `build` içindedir. Push etmeden önce ikisini de koşun.

## Typed API sözleşmesi

`src/api/schema.d.ts` **üretilmiş** bir dosyadır; elle düzenlenmez. Backend'in
`/v3/api-docs` çıktısından `npm run gen:api` ile üretilir ve commit'lenir.

Backend sözleşmesi değişip client yeniden üretilmezse frontend **sorunsuz derlenir** —
yanlış tiplere karşı. Hata yalnızca üretimde, o uç çağrıldığında ortaya çıkar. CI'daki
`typed-client-drift` kapısı tam olarak bunu yakalar: şemayı yeniden üretip commit'lenmişle
byte-byte karşılaştırır.

## Yerleşim

```
src/
├── api/          apiFetch (Bearer, X-Tenant, Accept-Language, RFC 9457 → ApiError,
│                 401'de tek-uçuşlu refresh), üretilmiş schema.d.ts
├── auth/         oturum, izin kontrolü (<Can>), route guard
├── features/     <ad>/{api,hooks,types,messages,pages,components,__tests__}
├── i18n/         en + tr
├── layouts/      admin kabuğu
├── providers/    query client, i18n, tema
└── shared/       ortak bileşenler ve yardımcılar
```

Yeni bir feature eklerken `features/editions/` iyi bir örnektir. Tam yordam:
`../../docs/ADDING-A-MODULE.md`.

## Uyulması gerekenler

- **Üçlü kilit:** route guard + `<Can permission={...}>` + backend `@PreAuthorize`. Frontend
  kontrolü **güvenlik değildir**, kullanıcı deneyimidir; gerçek kapı backend'dedir.
- **i18n eksiksiz:** her metin `en` **ve** `tr`. Eksik anahtar kullanıcıya ham anahtar gösterir.
- **Dört durum:** loading · error · **empty** · dolu. Empty en sık atlanan ve yeni bir
  kurulumda ilk açılan ekran her zaman boştur.
- **Vendor:** `../vendor/` altındaki Metronic dosyaları ham olarak kopyalanmaz; uyarlanır.
  Bu dizin `.gitignore`'ludur ve **klonunuzda bulunmaz** — Metronic ticari bir üründür,
  bkz. repo kökündeki `NOTICE.md`.

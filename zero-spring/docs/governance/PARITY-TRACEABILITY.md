# Parity-Traceability Matrisi (modül bazlı)

Her satır: bir parite yeteneği → kaynak (ASP.NET Zero kanıtı) → Spring gerçekleşmesi → test kanıtı → durum.
Durum ancak **test kanıtıyla** "Done (evidenced)" olur (governance kuralı: kanıtsız done yok).

**Status legend:** `Not started` · `In progress` · `Backend done` · `Done (evidenced)`
**Test Evidence:** geçen IT sınıfı adı (yeşil verify koşusundan).

## Faz 1 (kapandı — GO)

| Module | Backend Status | Frontend Status | Permission/I18n Status | Test Evidence | Risk Level |
|---|---|---|---|---|---|
| Auth (login/refresh/logout/me) | Done (evidenced) | Done (slice A: login) | perm: n/a · i18n: en/tr | AuthFlowIT 6/6 · login.test 3 | Low |
| Tenant çözümleme + izolasyon | Done (evidenced) | Done (slice A: X-Tenant, RBAC) | perm: tenants.manage | TenantIsolationIT 4/4, TenantEscalationIT 3/3 | Low |
| User CRUD (temel) | Done (evidenced) | Done (slice B: users ekranı) | perm: users.* | UserCrudIT 1/1 | Low |
| Tenant CRUD (temel) | Done (evidenced) | slice C (host paneli) | perm: tenants.manage | TenantIsolationIT (acme create) | Low |
| Modül sınırları | Done (evidenced) | — | — | ModularityTests 1/1 | Low |

## Faz 2 — İcra sonucu (bkz. `PHASE-2-REPORT.md` ile birebir hizalı)

Kaynak sütunundaki yollar `Asp.NET Zero/aspnet-core/src` köküne göredir (kısaltmalar ANALYSIS §0).
İcra tamamlandı: backend **52 test yeşil**, frontend **44 test yeşil**, uçtan uca smoke **9/9**, açık kritik/yüksek **0**.
Frontend Foundation (slice A: iskele + shadcn/admin-shell + auth/tenant/i18n providers + typed api-client +
RBAC guard + login) aşağıdaki "İlk vertical slice" tablosunun son satırında **Closed** olarak yer alır.

### İlk vertical slice — KAPANDI ✅ (backend+frontend+permission+i18n+test tam; uçtan uca smoke 9/9)

| Module | Kaynak (ASP.NET Zero) | Backend | Frontend (React) | Permission/i18n | Test Evidence | Risk |
|---|---|---|---|---|---|---|
| **Users** (list+search, CRUD, unlock, activate, roles/OU ata, soft-delete, Excel) | `[App]/Authorization/Users/UserAppService.cs` | **Done** | **Done** (users-list + form, data-grid, search) | users.* + `<Can>` · en/tr ✅ | UserManagementIT 8, UserCrudIT 1 · users-list.test 9 | **Closed** |
| **Roles + Permission tree** (CRUD, clone, default, izin ağacı) | `[App]/Authorization/Roles/RoleAppService.cs` | **Done** | **Done** (roles-list + form + permission-tree) | roles.* + `<Can>` · en/tr ✅ | RoleManagementIT 4, PermissionTreeIT 3 · roles-list 7 + role-form 2 + permission-tree 4 | **Closed** |
| **Organization Units** (ağaç, move, CRUD) | `[App]/Organizations/OrganizationUnitAppService.cs` | **Done** | **Done** (ou-tree + form) | organizationunits.manage + `<Can>` · en/tr ✅ | OrganizationUnitIT 4 · ou-tree 3 | **Closed** |
| **Notifications** (inbox, unread, mark-read, bell, polling) | `[App]/Notifications/NotificationAppService.cs` | **Done** | **Done** (inbox + bell badge) | isAuthenticated · en/tr ✅ | NotificationInboxIT 1 · notifications-inbox 5 | **Closed** |
| Frontend foundation (auth/rbac/i18n/api-client/shell) | Angular `AppSessionService`+`abp.js` | n/a | **Done** | RBAC guard + RequireAuth · en/tr | login 3, rbac 7, require-auth 4 | **Closed** |

### Slice C — KAPANDI ✅ (backend+frontend+permission+i18n+test tam; uçtan uca smoke)

| Module | Backend | Frontend (React) | Permission/i18n | Test Evidence | Durum |
|---|---|---|---|---|---|
| **Impersonation** (start, act-claim, cascade-block, back-to-impersonator, banner) | **Done** | **Done** (banner + users satır aksiyonu + auth-provider swap) | users.impersonate + `<Can>` · en/tr ✅ | ImpersonationIT 1 · impersonation FE 7 (cascade-disable) · smoke: authenticate→403→back | **Closed** |
| **Audit log + Entity history** (filtre/sıralama/sayfa/export, property diff) | **Done** | **Done** (audit-logs + entity-history, server-pagination, xlsx export) | auditlogs.read · en/tr ✅ | AuditLogIT 2, EntityHistoryIT 2 · audit FE 9 · smoke: 9 log/5 change | **Closed** |
| **Settings** (host/tenant tab, batch update, defaultValue ipucu, fallback) | **Done** (+SettingDto.defaultValue) | **Done** (tabs + rhf batch, any-permission guard, host double-lock) | settings.tenant/host.manage + `<Can>` · en/tr ✅ | SettingsIT 4 · settings FE 5 · smoke: defaultValue canlı | **Closed** |

Backend-servis modülleri (frontend ekranı gerektirmeyen): Localization (i18n altyapı FE'de aktif; dil-CRUD UI ileride),
Email (welcome/confirm/forgot/reset — LocalizationIT 2, EmailDispatchIT 2), Şifre politikası (PasswordPolicyIT 4) — **Backend done**.

> **KAPANIŞ:** İlk vertical slice + Slice C ile **tüm Faz 2 modülleri** (Users/Roles/OU/Notifications +
> Impersonation/Audit/Settings) beş sütunda tam ve uçtan uca kanıtlı → **KAPANDI**. Faz dışı (SaaS F5,
> veri migration F6, chat/realtime) kapsam dışında.

## F5 — SaaS ticari katman (Slice A kapandı)

Kaynak envanteri: `F5-SAAS-INVENTORY.md` · gap: `F5-GAP-ANALYSIS.md` · sözleşme: `CONTRACT-phase5.md`.

| Module | Kaynak (ASP.NET Zero) | Backend | Frontend (React) | Permission/i18n | Test Evidence | Durum |
|---|---|---|---|---|---|---|
| **Editions (paket katalogu)** | `SubscribableEdition` + `EditionAppService` | **Done** (CRUD, fiyat düzenlenebilir ADR-0012, silme 409 kuralları) | **Done** (liste + form + feature editörü) | editions.read/manage `Side.HOST` · en/tr ✅ | EditionCrudIT 6 · editions-list + feature-values-editor FE testleri · smoke | **Closed** |
| **Feature definitions + values** | `AppFeatures` + `EditionFeatureSetting`/`TenantFeatureSetting` | **Done** (registry, edition+tenant değerleri, çözümleme zinciri) | **Done** (tipe göre editör: BOOLEAN/NUMBER/STRING) | editions.read / tenantfeatures.manage · en/tr ✅ | FeatureResolutionIT 4 · smoke (edition 25 → tenant override 50) | **Closed** |
| **Tenant package assignment** | `TenantManager` + `AbpTenants.EditionId` | **Done** (fiyat snapshot, trial kuralları, event-driven provisioning) | **Done** (paket atama dialogu) | subscriptions.manage · en/tr ✅ | SubscriptionAssignmentIT 8 · assign-edition-dialog FE · smoke (TRIALING) | **Closed** |
| **Subscription status model** | implicit (IsActive+EndDate+InTrial) | **Done** (explicit status ADR-0009, geçiş guard'ı, `subscription_events`) | **Done** (status badge, activate/cancel) | subscriptions.read/manage · en/tr ✅ | SubscriptionStateMachineIT 4 · subscriptions-list FE · smoke | **Closed** |
| **SaaS yetkilendirme** | `Pages.Editions.*` / `Pages.Tenants.*` (host-only) | **Done** (5 izin `Side.HOST`, ADR-0015 izolasyon) | **Done** (`<Can>` + RequireAuth) | 5 izin · en/tr ✅ | SaasAuthorizationIT 8 + SaasPermissionsAlignmentTest 2 · RolePermissionReconciliationIT 4 · smoke (403×2) | **Closed** |
| Billing provider (SPI) | ortak arayüz **yok** | **Done (SPI + Manual)** | n/a | — | (Stripe Slice C) | Slice C |
| Subscription lifecycle (job) | 3 worker, lock yok | Not started | Not started | — | — | Slice B |
| Feature enforcement (`@RequiresFeature`) | imperatif `IFeatureChecker` | Not started (okuma hazır) | n/a | — | — | Slice B |
| Proration / upgrade | `GetUpgradePrice` + min eşik | Not started | Not started | — | — | Slice B |
| Payment / Invoice / Webhook | Stripe+PayPal, idempotency yok | Not started | Not started | — | — | Slice C |
| Tenant self-registration | Free/Trial/Paid | Not started | Not started | — | — | Slice C (ops.) |

> **Slice A kapanışı:** 5 modül beş sütunda tam + uçtan uca canlı kanıtlı. Slice B (lifecycle/enforcement/
> proration) ve Slice C (billing/webhook/invoice) sözleşmede tanımlı, henüz başlamadı.

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

### Backend-only (parite tam, Frontend UI slice C bekliyor)

| Module | Backend | Frontend | Test Evidence | Not |
|---|---|---|---|---|
| Impersonation (act claim) | **Done (evidenced)** | slice C | ImpersonationIT 1 (cascade yasak + audit) | UI: "login as" akışı slice C |
| Audit log (HTTP) + Entity history | **Done (evidenced)** | slice C | AuditLogIT 2, EntityHistoryIT 2 (Role+OU) | UI: audit görüntüleme slice C |
| Settings (hiyerarşik) | **Done (evidenced)** | slice C | SettingsIT 3 | UI: settings ekranı slice C |
| Localization (en/tr) | **Done (evidenced)** | (i18n altyapı frontend'te aktif) | LocalizationIT 2 | Dil CRUD UI slice C |
| Email (welcome/confirm/forgot/reset) | **Done (evidenced)** | n/a (backend servis) | EmailDispatchIT 2, PasswordPolicyIT | — |
| Şifre politikası + history | **Done (evidenced)** | slice C (profil ekranı) | PasswordPolicyIT 4 | — |

> **KAPANIŞ:** Kullanıcının tanımladığı **ilk vertical slice (Users + Roles/Permissions + OU + Notifications)**
> beş sütunda da tam ve uçtan uca kanıtlı → **KAPANDI**. Backend paritesi tüm Faz2 modüllerinde tam;
> Impersonation/Audit/Settings için React ekranları **slice C** kapsamına ertelendi (kapsam kilidi: ilk slice).

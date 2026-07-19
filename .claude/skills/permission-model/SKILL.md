---
name: permission-model
description: İzin tanımlama, kaydetme ve zorlama kuralları — AppPermissions sabitleri, PermissionDefinitions ağacı, üçlü kilit (backend + Can + route guard). Yeni bir yetki gerektiren uç ya da ekran eklendiğinde yükle.
---

# İzin modeli

## Üçlü kilit

Bir yetki üç yerde birden zorlanır. Biri eksikse **kilit yoktur**:

| Katman | Ne yapar | Eksikse ne olur |
|---|---|---|
| Backend `@PreAuthorize` | **Gerçek kapı** | Yetkisiz kullanıcı veriyi alır — güvenlik açığı |
| Frontend `<Can permission={...}>` | Butonu/alanı gizler | Kullanıcı tıklar, 403 yer — kötü deneyim |
| Route guard (`require-auth`) | Sayfayı korur | Boş/hatalı ekran açılır |

Frontend kontrolü **güvenlik değildir**. İkisini de yap, ama hangisinin gerçek kapı olduğunu
unutma.

## Ham string yazma — somut hata

```java
@PreAuthorize("hasAuthority('users.raed')")   // YANLIŞ: derlenir, test geçer, sonsuza dek 403
@PreAuthorize("hasAuthority('" + AppPermissions.USERS_READ + "')")   // doğru
```

Yazım hatası **hiçbir yerde** yakalanmaz: derleyici string'i doğrulamaz, test de aynı yanlış
string'i kullanırsa yeşil kalır, ve endpoint sessizce erişilemez olur.

⚠️ Bu depoda **15 ham literal** var (`identity`, `audit`, `settings` modüllerinde). Bunlar
düzeltilecek borç, kopyalanacak örnek **değil**. Doğru örnek: `saas` modülü
(`SaasPermissions` + `SaasPermissionsAlignmentTest`).

## Yeni izin ekleme

1. **Sabit:** `AppPermissions` içinde `public static final String X = "modül.eylem";`
2. **Kayıt:** `PermissionDefinitions` ağacına ekle — **doğru `Side` ile**:
   - `HOST` — yalnız host yöneticisi (örn. `editions.manage`, `settings.host.manage`)
   - `TENANT` — yalnız kiracı içi
   - `BOTH` — ikisi de
   Yanlış `Side`, kiracı adminine host yetkisi verir.
3. **Zorlama:** ilgili uçta `@PreAuthorize`.
4. **Frontend:** `<Can>` + route guard.
5. **i18n:** izin adının görünen karşılığı en **ve** tr.
6. **Seed uzlaştırması:** mevcut kurulumlarda statik rollere yeni izin eklenmesi
   `zero.seed.reconcile-permissions` bayrağıyla açılışta yapılır (prod dahil default açık).
   Bu, F5-R9'da öğrenildi: yeni izinler mevcut kurulumda rollere eklenmiyordu, host admin
   17/22 izinle kalıyor ve yeni uçlar 403 dönüyordu — **temiz DB testleri yeşilken**.

## Test

- Mutlu yol: izinli kullanıcı 200.
- **Negatif yetki:** izinsiz kullanıcı 403. Bu zorunlu; olmadan izin eklenmiş sayılmaz.
- Host-only bir izinse: kiracı admininin **de** 403 aldığını doğrula. Kiracının en yetkili
  hesabı reddedilmeli — sınır host/tenant, yetkili/yetkisiz değil.
- İzin sayısına dayanan test yazma (örn. "22 izin olmalı") — her yeni izinde kırılır ve
  kimse gerçek bir şey öğrenmez.

## Doğrulama

`PermissionDefinitions` ile `AppPermissions` arasındaki hizalanma test edilebilir
(`SaasPermissionsAlignmentTest` deseni): tanımlı her sabit ağaçta kayıtlı mı, ağaçtaki her
düğümün sabiti var mı. Yeni modülde bu testi genişlet.

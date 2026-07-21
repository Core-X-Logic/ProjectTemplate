/**
 * Users feature message catalogues (flat, dot-keyed — FRONTEND-ARCHITECTURE.md §6).
 *
 * The integration step merges these into `i18n/messages/en.ts` / `tr.ts`
 * (spread into the root catalogue). Keys are kept 1:1 between both languages.
 */

export const usersMessagesEn: Record<string, string> = {
  'users.title': 'Users',
  'users.subtitle': 'Manage the user accounts of the active tenant.',
  'users.searchPlaceholder': 'Search users…',
  'users.empty': 'No users found.',
  'users.emptyDescription':
    'Add the first user account for this tenant to get started.',
  'users.error': 'The operation could not be completed.',
  'users.loadError': 'Users could not be loaded.',

  // Columns
  'users.column.username': 'Username',
  'users.column.email': 'Email',
  'users.column.name': 'Name',
  'users.column.roles': 'Roles',
  'users.column.active': 'Status',
  'users.column.actions': 'Actions',

  // Row / toolbar actions
  'users.action.create': 'Create',
  'users.action.edit': 'Edit',
  'users.action.delete': 'Delete',
  'users.action.unlock': 'Unlock',
  'users.action.activate': 'Activate',
  'users.action.deactivate': 'Deactivate',
  'users.action.export': 'Export',

  // Status badges
  'users.status.active': 'Active',
  'users.status.inactive': 'Inactive',

  // Form
  'users.form.createTitle': 'Create user',
  'users.form.editTitle': 'Edit user',
  'users.form.username': 'Username',
  'users.form.email': 'Email',
  'users.form.name': 'First name',
  'users.form.surname': 'Last name',
  'users.form.phoneNumber': 'Phone number',
  'users.form.password': 'Password',
  'users.form.passwordEditHint': 'Leave empty to keep the current password.',
  'users.form.roles': 'Roles',
  'users.form.rolesPlaceholder': 'Select roles',
  'users.form.organizationUnits': 'Organization units',
  'users.form.organizationUnitsPlaceholder': 'Select organization units',
  'users.form.identityReadonly':
    'Identity fields can only be changed by the user on their profile.',
  'users.form.submitCreate': 'Create',
  'users.form.submitEdit': 'Save',
  'users.form.cancel': 'Cancel',
  'users.form.emailInvalid': 'Enter a valid email address.',
  'users.form.passwordMin': 'Password must be at least 8 characters.',

  // Delete confirmation
  'users.deleteConfirm.title': 'Delete user',
  'users.deleteConfirm.description':
    'The user "{username}" will be deleted. This action can be undone by an administrator (soft delete).',
  'users.deleteConfirm.cancel': 'Cancel',
  'users.deleteConfirm.confirm': 'Delete',

  // Mutation results (toasts)
  'users.created': 'User created.',
  'users.updated': 'User updated.',
  'users.deleted': 'User deleted.',
  'users.unlocked': 'User unlocked.',
  'users.activated': 'User activated.',
  'users.deactivated': 'User deactivated.',
  'users.exported': 'User list exported.',
};

export const usersMessagesTr: Record<string, string> = {
  'users.title': 'Kullanıcılar',
  'users.subtitle': 'Aktif kiracının kullanıcı hesaplarını yönetin.',
  'users.searchPlaceholder': 'Kullanıcı ara…',
  'users.empty': 'Kullanıcı bulunamadı.',
  'users.emptyDescription':
    'Başlamak için bu kiracıya ilk kullanıcı hesabını ekleyin.',
  'users.error': 'İşlem tamamlanamadı.',
  'users.loadError': 'Kullanıcılar yüklenemedi.',

  // Columns
  'users.column.username': 'Kullanıcı adı',
  'users.column.email': 'E-posta',
  'users.column.name': 'Ad',
  'users.column.roles': 'Roller',
  'users.column.active': 'Durum',
  'users.column.actions': 'İşlemler',

  // Row / toolbar actions
  'users.action.create': 'Oluştur',
  'users.action.edit': 'Düzenle',
  'users.action.delete': 'Sil',
  'users.action.unlock': 'Kilidi aç',
  'users.action.activate': 'Aktifleştir',
  'users.action.deactivate': 'Pasifleştir',
  'users.action.export': 'Dışa aktar',

  // Status badges
  'users.status.active': 'Aktif',
  'users.status.inactive': 'Pasif',

  // Form
  'users.form.createTitle': 'Kullanıcı oluştur',
  'users.form.editTitle': 'Kullanıcı düzenle',
  'users.form.username': 'Kullanıcı adı',
  'users.form.email': 'E-posta',
  'users.form.name': 'Ad',
  'users.form.surname': 'Soyad',
  'users.form.phoneNumber': 'Telefon numarası',
  'users.form.password': 'Parola',
  'users.form.passwordEditHint': 'Mevcut parolayı korumak için boş bırakın.',
  'users.form.roles': 'Roller',
  'users.form.rolesPlaceholder': 'Rol seçin',
  'users.form.organizationUnits': 'Organizasyon birimleri',
  'users.form.organizationUnitsPlaceholder': 'Organizasyon birimi seçin',
  'users.form.identityReadonly':
    'Kimlik alanları yalnızca kullanıcının kendi profilinden değiştirilebilir.',
  'users.form.submitCreate': 'Oluştur',
  'users.form.submitEdit': 'Kaydet',
  'users.form.cancel': 'Vazgeç',
  'users.form.emailInvalid': 'Geçerli bir e-posta adresi girin.',
  'users.form.passwordMin': 'Parola en az 8 karakter olmalıdır.',

  // Delete confirmation
  'users.deleteConfirm.title': 'Kullanıcıyı sil',
  'users.deleteConfirm.description':
    '"{username}" kullanıcısı silinecek. Bu işlem bir yönetici tarafından geri alınabilir (soft delete).',
  'users.deleteConfirm.cancel': 'Vazgeç',
  'users.deleteConfirm.confirm': 'Sil',

  // Mutation results (toasts)
  'users.created': 'Kullanıcı oluşturuldu.',
  'users.updated': 'Kullanıcı güncellendi.',
  'users.deleted': 'Kullanıcı silindi.',
  'users.unlocked': 'Kullanıcının kilidi açıldı.',
  'users.activated': 'Kullanıcı aktifleştirildi.',
  'users.deactivated': 'Kullanıcı pasifleştirildi.',
  'users.exported': 'Kullanıcı listesi dışa aktarıldı.',
};

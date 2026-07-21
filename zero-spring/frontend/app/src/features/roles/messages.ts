/**
 * Roles feature message catalogue (flat, dot-keyed; en + tr, keys 1:1).
 *
 * These keys are merged into the global `i18n/messages/{en,tr}.ts` catalogues
 * (namespaced, no collisions) and served by the app-level `I18nProvider`, so
 * consumers read them straight from the ambient intl context. When the backend
 * `/api/localization/{culture}` sync (§6) later drives the catalogue, the same
 * keys are served from there.
 */

const en: Record<string, string> = {
  // List page
  'roles.list.title': 'Roles',
  'roles.list.description': 'Manage roles and their permissions.',
  'roles.list.create': 'New role',
  'roles.list.empty': 'No roles found.',
  'roles.list.emptyDescription':
    'Create a role to group permissions and assign them to users.',
  'roles.list.error': 'Roles could not be loaded.',

  // Data-grid columns
  'roles.columns.name': 'Name',
  'roles.columns.displayName': 'Display name',
  'roles.columns.type': 'Type',
  'roles.columns.default': 'Default',
  'roles.columns.members': 'Members',

  // Badges
  'roles.badge.static': 'Static',
  'roles.badge.custom': 'Custom',
  'roles.badge.default': 'Default',

  // Row actions
  'roles.actions.menu': 'Open role actions',
  'roles.actions.edit': 'Edit',
  'roles.actions.clone': 'Clone',
  'roles.actions.delete': 'Delete',

  // Delete confirmation
  'roles.delete.title': 'Delete role',
  'roles.delete.description':
    'The role "{name}" will be permanently deleted. This action cannot be undone.',
  'roles.delete.confirm': 'Delete',
  'roles.delete.cancel': 'Cancel',

  // Form page
  'roles.form.createTitle': 'Create role',
  'roles.form.editTitle': 'Edit role',
  'roles.form.sectionDetails': 'Role details',
  'roles.form.sectionPermissions': 'Permissions',
  'roles.form.name': 'Name',
  'roles.form.namePlaceholder': 'e.g. content-editor',
  'roles.form.nameHint': 'Unique technical name; it cannot be changed later.',
  'roles.form.displayName': 'Display name',
  'roles.form.displayNamePlaceholder': 'e.g. Content Editor',
  'roles.form.isDefault': 'Default role',
  'roles.form.isDefaultHint': 'Assigned automatically to newly created users.',
  'roles.form.permissions': 'Permissions',
  'roles.form.permissionsHint':
    'Selecting a parent toggles all of its children.',
  'roles.form.submitCreate': 'Create',
  'roles.form.submitUpdate': 'Save',
  'roles.form.saving': 'Saving…',
  'roles.form.cancel': 'Cancel',
  'roles.form.loadError': 'Role could not be loaded.',

  // Mutation toasts
  'roles.toast.created': 'Role created.',
  'roles.toast.updated': 'Role updated.',
  'roles.toast.deleted': 'Role deleted.',
  'roles.toast.cloned': 'Role cloned.',
  'roles.toast.error': 'Operation failed. Please try again.',

  // Permission tree
  'permission.tree.empty': 'No permissions available.',
  'permission.tree.error': 'Permissions could not be loaded.',
  'permission.tree.selectedCount':
    '{count, plural, one {# permission selected} other {# permissions selected}}',
  'permission.tree.toggle': 'Toggle {name}',
};

const tr: Record<string, string> = {
  // List page
  'roles.list.title': 'Roller',
  'roles.list.description': 'Rolleri ve izinlerini yönetin.',
  'roles.list.create': 'Yeni rol',
  'roles.list.empty': 'Rol bulunamadı.',
  'roles.list.emptyDescription':
    'İzinleri gruplamak ve kullanıcılara atamak için bir rol oluşturun.',
  'roles.list.error': 'Roller yüklenemedi.',

  // Data-grid columns
  'roles.columns.name': 'Ad',
  'roles.columns.displayName': 'Görünen ad',
  'roles.columns.type': 'Tür',
  'roles.columns.default': 'Varsayılan',
  'roles.columns.members': 'Üyeler',

  // Badges
  'roles.badge.static': 'Statik',
  'roles.badge.custom': 'Özel',
  'roles.badge.default': 'Varsayılan',

  // Row actions
  'roles.actions.menu': 'Rol işlemlerini aç',
  'roles.actions.edit': 'Düzenle',
  'roles.actions.clone': 'Kopyala',
  'roles.actions.delete': 'Sil',

  // Delete confirmation
  'roles.delete.title': 'Rolü sil',
  'roles.delete.description':
    '"{name}" rolü kalıcı olarak silinecek. Bu işlem geri alınamaz.',
  'roles.delete.confirm': 'Sil',
  'roles.delete.cancel': 'İptal',

  // Form page
  'roles.form.createTitle': 'Rol oluştur',
  'roles.form.editTitle': 'Rolü düzenle',
  'roles.form.sectionDetails': 'Rol bilgileri',
  'roles.form.sectionPermissions': 'İzinler',
  'roles.form.name': 'Ad',
  'roles.form.namePlaceholder': 'örn. icerik-editoru',
  'roles.form.nameHint': 'Benzersiz teknik ad; sonradan değiştirilemez.',
  'roles.form.displayName': 'Görünen ad',
  'roles.form.displayNamePlaceholder': 'örn. İçerik Editörü',
  'roles.form.isDefault': 'Varsayılan rol',
  'roles.form.isDefaultHint':
    'Yeni oluşturulan kullanıcılara otomatik olarak atanır.',
  'roles.form.permissions': 'İzinler',
  'roles.form.permissionsHint':
    'Üst öğe seçildiğinde tüm alt öğeleri birlikte değişir.',
  'roles.form.submitCreate': 'Oluştur',
  'roles.form.submitUpdate': 'Kaydet',
  'roles.form.saving': 'Kaydediliyor…',
  'roles.form.cancel': 'İptal',
  'roles.form.loadError': 'Rol yüklenemedi.',

  // Mutation toasts
  'roles.toast.created': 'Rol oluşturuldu.',
  'roles.toast.updated': 'Rol güncellendi.',
  'roles.toast.deleted': 'Rol silindi.',
  'roles.toast.cloned': 'Rol kopyalandı.',
  'roles.toast.error': 'İşlem başarısız. Lütfen tekrar deneyin.',

  // Permission tree
  'permission.tree.empty': 'Kullanılabilir izin yok.',
  'permission.tree.error': 'İzinler yüklenemedi.',
  'permission.tree.selectedCount': '{count} izin seçildi',
  'permission.tree.toggle': '{name} aç/kapat',
};

export const rolesMessages: Record<'en' | 'tr', Record<string, string>> = {
  en,
  tr,
};

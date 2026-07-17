import type { Locale } from '@/providers/i18n-provider';

/**
 * Feature-scoped message catalogue (`organizationUnits.*`), both locales.
 *
 * The global catalogues (`i18n/messages/{en,tr}.ts`) stay untouched: the page
 * merges these keys into the ambient `IntlProvider` via a nested provider
 * (see `pages/ou-tree.tsx`). Keys are kept 1:1 between `en` and `tr`
 * (FRONTEND-ARCHITECTURE.md §6).
 */

const en: Record<string, string> = {
  'organizationUnits.title': 'Organization Units',
  'organizationUnits.description':
    'Manage the organization hierarchy: create, rename, move and delete units.',
  'organizationUnits.empty': 'No organization units yet.',
  'organizationUnits.loadError': 'Organization units could not be loaded.',
  'organizationUnits.retry': 'Retry',
  'organizationUnits.actions': 'Actions',
  'organizationUnits.newRoot': 'New root unit',
  'organizationUnits.addChild': 'Add sub-unit',
  'organizationUnits.edit': 'Edit',
  'organizationUnits.move': 'Move',
  'organizationUnits.delete': 'Delete',
  'organizationUnits.members': '{count, plural, one {# member} other {# members}}',

  'organizationUnits.form.createTitle': 'New organization unit',
  'organizationUnits.form.editTitle': 'Edit organization unit',
  'organizationUnits.form.displayName': 'Display name',
  'organizationUnits.form.displayNamePlaceholder': 'e.g. Human Resources',
  'organizationUnits.form.parent': 'Parent unit',
  'organizationUnits.form.parentRoot': '(root — no parent)',

  'organizationUnits.moveDialog.title': 'Move unit',
  'organizationUnits.moveDialog.description':
    'Select the new parent for "{name}". The unit cannot be moved under itself or one of its descendants.',
  'organizationUnits.moveDialog.submit': 'Move',

  'organizationUnits.deleteDialog.title': 'Delete unit',
  'organizationUnits.deleteDialog.description':
    '"{name}" will be deleted. This action cannot be undone.',

  'organizationUnits.toast.created': 'Organization unit created.',
  'organizationUnits.toast.updated': 'Organization unit updated.',
  'organizationUnits.toast.moved': 'Organization unit moved.',
  'organizationUnits.toast.deleted': 'Organization unit deleted.',
  'organizationUnits.toast.error': 'Operation failed. Please try again.',
};

const tr: Record<string, string> = {
  'organizationUnits.title': 'Organizasyon Birimleri',
  'organizationUnits.description':
    'Organizasyon hiyerarşisini yönetin: birim oluşturun, yeniden adlandırın, taşıyın ve silin.',
  'organizationUnits.empty': 'Henüz organizasyon birimi yok.',
  'organizationUnits.loadError': 'Organizasyon birimleri yüklenemedi.',
  'organizationUnits.retry': 'Tekrar dene',
  'organizationUnits.actions': 'İşlemler',
  'organizationUnits.newRoot': 'Yeni kök birim',
  'organizationUnits.addChild': 'Alt birim ekle',
  'organizationUnits.edit': 'Düzenle',
  'organizationUnits.move': 'Taşı',
  'organizationUnits.delete': 'Sil',
  'organizationUnits.members': '{count, plural, one {# üye} other {# üye}}',

  'organizationUnits.form.createTitle': 'Yeni organizasyon birimi',
  'organizationUnits.form.editTitle': 'Organizasyon birimini düzenle',
  'organizationUnits.form.displayName': 'Görünen ad',
  'organizationUnits.form.displayNamePlaceholder': 'örn. İnsan Kaynakları',
  'organizationUnits.form.parent': 'Üst birim',
  'organizationUnits.form.parentRoot': '(kök — üst birim yok)',

  'organizationUnits.moveDialog.title': 'Birimi taşı',
  'organizationUnits.moveDialog.description':
    '"{name}" için yeni üst birimi seçin. Birim kendisinin veya alt birimlerinin altına taşınamaz.',
  'organizationUnits.moveDialog.submit': 'Taşı',

  'organizationUnits.deleteDialog.title': 'Birimi sil',
  'organizationUnits.deleteDialog.description':
    '"{name}" silinecek. Bu işlem geri alınamaz.',

  'organizationUnits.toast.created': 'Organizasyon birimi oluşturuldu.',
  'organizationUnits.toast.updated': 'Organizasyon birimi güncellendi.',
  'organizationUnits.toast.moved': 'Organizasyon birimi taşındı.',
  'organizationUnits.toast.deleted': 'Organizasyon birimi silindi.',
  'organizationUnits.toast.error': 'İşlem başarısız oldu. Lütfen tekrar deneyin.',
};

export const organizationUnitsMessages: Record<Locale, Record<string, string>> =
  { en, tr };

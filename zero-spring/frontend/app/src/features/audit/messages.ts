/**
 * Audit feature message catalogues (flat, dot-keyed — FRONTEND-ARCHITECTURE.md §6).
 *
 * The integration step merges these into `i18n/messages/en.ts` / `tr.ts`
 * (spread into the root catalogue). Keys are kept 1:1 between both languages so
 * the two catalogues cannot drift.
 */

export const auditMessagesEn: Record<string, string> = {
  // Shell / tabs
  'audit.title': 'Audit',
  'audit.tab.logs': 'Audit Logs',
  'audit.tab.entityHistory': 'Entity History',

  // Audit logs — page chrome
  'audit.logs.title': 'Audit Logs',
  'audit.logs.subtitle': 'Every server-side action recorded for this tenant.',
  'audit.empty': 'No records found.',
  'audit.error': 'The audit log could not be loaded.',

  // Audit logs — columns
  'audit.column.executionTime': 'Time',
  'audit.column.username': 'User',
  'audit.column.serviceName': 'Service',
  'audit.column.methodName': 'Method',
  'audit.column.httpMethod': 'HTTP',
  'audit.column.httpStatus': 'Status',
  'audit.column.duration': 'Duration',

  // Audit logs — filters
  'audit.filter.title': 'Filters',
  'audit.filter.userName': 'User',
  'audit.filter.userNamePlaceholder': 'Filter by user…',
  'audit.filter.startDate': 'Start date',
  'audit.filter.endDate': 'End date',
  'audit.filter.pickDate': 'Pick a date',
  'audit.filter.httpStatus': 'HTTP status',
  'audit.filter.httpStatusPlaceholder': 'e.g. 500',
  'audit.filter.clear': 'Clear filters',

  // Audit logs — values / actions
  'audit.duration.ms': '{ms} ms',
  'audit.action.export': 'Export',
  'audit.exported': 'Audit log exported.',

  // Entity history — page chrome
  'audit.entityHistory.title': 'Entity History',
  'audit.entityHistory.subtitle':
    'Property-level change history for tracked entities.',
  'audit.entityHistory.empty': 'No changes found.',
  'audit.entityHistory.error': 'The change history could not be loaded.',

  // Entity history — columns
  'audit.entityHistory.column.entityType': 'Entity',
  'audit.entityHistory.column.entityId': 'Entity ID',
  'audit.entityHistory.column.changeType': 'Change',
  'audit.entityHistory.column.changeTime': 'Time',
  'audit.entityHistory.column.userId': 'User ID',

  // Entity history — filters
  'audit.entityHistory.filter.entityType': 'Entity type',
  'audit.entityHistory.filter.entityTypePlaceholder': 'e.g. User',
  'audit.entityHistory.filter.entityId': 'Entity ID',
  'audit.entityHistory.filter.entityIdPlaceholder': 'e.g. 42',

  // Entity history — change type badges
  'audit.entityHistory.changeType.created': 'Created',
  'audit.entityHistory.changeType.updated': 'Updated',
  'audit.entityHistory.changeType.deleted': 'Deleted',

  // Entity history — property change detail
  'audit.entityHistory.expand': 'Toggle change detail',
  'audit.entityHistory.property.name': 'Property',
  'audit.entityHistory.property.original': 'Original value',
  'audit.entityHistory.property.new': 'New value',
  'audit.entityHistory.noPropertyChanges':
    'No property-level changes were recorded.',
};

export const auditMessagesTr: Record<string, string> = {
  // Shell / tabs
  'audit.title': 'Denetim',
  'audit.tab.logs': 'Denetim Kayıtları',
  'audit.tab.entityHistory': 'Varlık Geçmişi',

  // Audit logs — page chrome
  'audit.logs.title': 'Denetim Kayıtları',
  'audit.logs.subtitle': 'Bu kiracı için kaydedilen tüm sunucu işlemleri.',
  'audit.empty': 'Kayıt bulunamadı.',
  'audit.error': 'Denetim kaydı yüklenemedi.',

  // Audit logs — columns
  'audit.column.executionTime': 'Zaman',
  'audit.column.username': 'Kullanıcı',
  'audit.column.serviceName': 'Servis',
  'audit.column.methodName': 'Metot',
  'audit.column.httpMethod': 'HTTP',
  'audit.column.httpStatus': 'Durum',
  'audit.column.duration': 'Süre',

  // Audit logs — filters
  'audit.filter.title': 'Filtreler',
  'audit.filter.userName': 'Kullanıcı',
  'audit.filter.userNamePlaceholder': 'Kullanıcıya göre filtrele…',
  'audit.filter.startDate': 'Başlangıç tarihi',
  'audit.filter.endDate': 'Bitiş tarihi',
  'audit.filter.pickDate': 'Tarih seçin',
  'audit.filter.httpStatus': 'HTTP durumu',
  'audit.filter.httpStatusPlaceholder': 'örn. 500',
  'audit.filter.clear': 'Filtreleri temizle',

  // Audit logs — values / actions
  'audit.duration.ms': '{ms} ms',
  'audit.action.export': 'Dışa aktar',
  'audit.exported': 'Denetim kaydı dışa aktarıldı.',

  // Entity history — page chrome
  'audit.entityHistory.title': 'Varlık Geçmişi',
  'audit.entityHistory.subtitle':
    'İzlenen varlıklar için özellik bazlı değişiklik geçmişi.',
  'audit.entityHistory.empty': 'Değişiklik bulunamadı.',
  'audit.entityHistory.error': 'Değişiklik geçmişi yüklenemedi.',

  // Entity history — columns
  'audit.entityHistory.column.entityType': 'Varlık',
  'audit.entityHistory.column.entityId': 'Varlık No',
  'audit.entityHistory.column.changeType': 'Değişiklik',
  'audit.entityHistory.column.changeTime': 'Zaman',
  'audit.entityHistory.column.userId': 'Kullanıcı No',

  // Entity history — filters
  'audit.entityHistory.filter.entityType': 'Varlık tipi',
  'audit.entityHistory.filter.entityTypePlaceholder': 'örn. User',
  'audit.entityHistory.filter.entityId': 'Varlık No',
  'audit.entityHistory.filter.entityIdPlaceholder': 'örn. 42',

  // Entity history — change type badges
  'audit.entityHistory.changeType.created': 'Oluşturuldu',
  'audit.entityHistory.changeType.updated': 'Güncellendi',
  'audit.entityHistory.changeType.deleted': 'Silindi',

  // Entity history — property change detail
  'audit.entityHistory.expand': 'Değişiklik detayını aç/kapat',
  'audit.entityHistory.property.name': 'Özellik',
  'audit.entityHistory.property.original': 'Eski değer',
  'audit.entityHistory.property.new': 'Yeni değer',
  'audit.entityHistory.noPropertyChanges':
    'Özellik bazlı değişiklik kaydedilmemiş.',
};

/**
 * Dashboard widget catalogue (namespaced `dashboard.*`, no collisions with the
 * page-level `dashboard.*` keys that live in the central catalogues). Merged
 * into `src/i18n/messages/{en,tr}.ts` — both languages, always.
 */
export const dashboardMessages = {
  en: {
    // KPI band
    'dashboard.kpi.users': 'Users',
    'dashboard.kpi.roles': 'Roles',
    'dashboard.kpi.tenants': 'Tenants',
    'dashboard.kpi.unread': 'Unread notifications',
    'dashboard.kpi.error': 'Could not load',

    // Activity trend (audit-log volume)
    'dashboard.trend.title': 'Activity trend',
    'dashboard.trend.description': 'Audit-log volume, last 14 days',
    'dashboard.trend.aria':
      'Area chart of daily audit-log volume over the last 14 days',
    'dashboard.trend.summary':
      '{total, plural, one {# audit entry} other {# audit entries}} in the last 14 days.',
    'dashboard.trend.tooltip':
      '{count, plural, one {# entry} other {# entries}}',
    'dashboard.trend.empty': 'No activity in the last 14 days.',
    'dashboard.trend.error': 'The activity trend could not be loaded.',
    'dashboard.trend.sampled':
      'Based on the latest {sample} of {total} entries in this window.',

    // Recent users
    'dashboard.recentUsers.title': 'Recent users',
    'dashboard.recentUsers.description': 'Newest accounts',
    'dashboard.recentUsers.empty': 'No users yet.',
    'dashboard.recentUsers.error': 'Recent users could not be loaded.',
    'dashboard.recentUsers.viewAll': 'View all users',

    // Recent activity timeline
    'dashboard.recentActivity.title': 'Recent activity',
    'dashboard.recentActivity.description': 'Latest audit entries',
    'dashboard.recentActivity.empty': 'No recent activity.',
    'dashboard.recentActivity.error': 'Recent activity could not be loaded.',
    'dashboard.recentActivity.duration': '{ms} ms',

    // Subscription (tenant context only)
    'dashboard.subscription.title': 'Subscription',
    'dashboard.subscription.description': 'Your current plan',
    'dashboard.subscription.plan': 'Plan',
    'dashboard.subscription.periodEnd': 'Period ends',
    'dashboard.subscription.empty': 'No subscription yet.',
    'dashboard.subscription.emptyDescription':
      'This tenant has no subscription assigned.',
    'dashboard.subscription.error': 'Your subscription could not be loaded.',
    'dashboard.subscription.manage': 'Go to subscriptions',
  },
  tr: {
    // KPI bandı
    'dashboard.kpi.users': 'Kullanıcılar',
    'dashboard.kpi.roles': 'Roller',
    'dashboard.kpi.tenants': 'Kiracılar',
    'dashboard.kpi.unread': 'Okunmamış bildirimler',
    'dashboard.kpi.error': 'Yüklenemedi',

    // Aktivite eğilimi (denetim kaydı hacmi)
    'dashboard.trend.title': 'Aktivite eğilimi',
    'dashboard.trend.description': 'Denetim kaydı hacmi, son 14 gün',
    'dashboard.trend.aria':
      'Son 14 günün günlük denetim kaydı hacmini gösteren alan grafiği',
    'dashboard.trend.summary': 'Son 14 günde {total} denetim kaydı.',
    'dashboard.trend.tooltip': '{count} kayıt',
    'dashboard.trend.empty': 'Son 14 günde aktivite yok.',
    'dashboard.trend.error': 'Aktivite eğilimi yüklenemedi.',
    'dashboard.trend.sampled':
      'Bu penceredeki {total} kaydın en yeni {sample} tanesi bazlıdır.',

    // Son kullanıcılar
    'dashboard.recentUsers.title': 'Son kullanıcılar',
    'dashboard.recentUsers.description': 'En yeni hesaplar',
    'dashboard.recentUsers.empty': 'Henüz kullanıcı yok.',
    'dashboard.recentUsers.error': 'Son kullanıcılar yüklenemedi.',
    'dashboard.recentUsers.viewAll': 'Tüm kullanıcıları gör',

    // Son aktivite zaman çizelgesi
    'dashboard.recentActivity.title': 'Son aktivite',
    'dashboard.recentActivity.description': 'En son denetim kayıtları',
    'dashboard.recentActivity.empty': 'Yakın zamanda aktivite yok.',
    'dashboard.recentActivity.error': 'Son aktivite yüklenemedi.',
    'dashboard.recentActivity.duration': '{ms} ms',

    // Abonelik (yalnız kiracı bağlamı)
    'dashboard.subscription.title': 'Abonelik',
    'dashboard.subscription.description': 'Mevcut planınız',
    'dashboard.subscription.plan': 'Plan',
    'dashboard.subscription.periodEnd': 'Dönem bitişi',
    'dashboard.subscription.empty': 'Henüz abonelik yok.',
    'dashboard.subscription.emptyDescription':
      'Bu kiracıya atanmış bir abonelik bulunmuyor.',
    'dashboard.subscription.error': 'Aboneliğiniz yüklenemedi.',
    'dashboard.subscription.manage': 'Aboneliklere git',
  },
} as const;

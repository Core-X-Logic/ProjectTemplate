/**
 * Profile feature message catalogue (flat, dot-keyed; en + tr, keys 1:1).
 *
 * Parity is enforced at COMPILE TIME: `profileEn` is the key source of truth
 * (`as const satisfies`), and `profileTr` is typed `Record<ProfileMessageId,
 * string>` — a missing or extra Turkish key fails `tsc -b`.
 */

export const profileEn = {
  // Page
  'profile.title': 'My profile',
  'profile.description': 'Your account details and password.',
  'profile.loading': 'Loading your profile…',
  'profile.loadError': 'Your profile could not be loaded.',

  // Details card
  'profile.details.title': 'Details',
  'profile.details.description': 'Update the name and contact details on your account.',
  'profile.details.name': 'First name',
  'profile.details.surname': 'Last name',
  'profile.details.email': 'Email',
  'profile.details.phoneNumber': 'Phone number',
  'profile.details.username': 'Username',
  'profile.details.usernameHint': 'Your username cannot be changed here.',
  'profile.details.roles': 'Roles',
  'profile.details.noRoles': 'No roles assigned.',
  'profile.details.emailConfirmed': 'Email confirmed',
  'profile.details.emailUnconfirmed': 'Email not confirmed',
  'profile.details.save': 'Save changes',
  'profile.details.saving': 'Saving…',
  'profile.details.invalidEmail': 'Enter a valid email address.',
  'profile.details.tooLong': 'Use at most {max} characters.',

  // Change password card
  'profile.password.title': 'Change password',
  'profile.password.description':
    'Choose a new password. You will stay signed in on this device.',
  'profile.password.current': 'Current password',
  'profile.password.new': 'New password',
  'profile.password.confirm': 'Confirm new password',
  'profile.password.hint':
    'At least {min} characters. Your tenant may enforce a stricter policy.',
  'profile.password.mismatch': 'The passwords do not match.',
  'profile.password.tooShort': 'Use at least {min} characters.',
  'profile.password.sameAsCurrent':
    'The new password must differ from the current one.',
  'profile.password.submit': 'Change password',
  'profile.password.submitting': 'Saving…',
  'profile.password.success': 'Your password has been changed.',
  'profile.password.error': 'The password could not be changed.',

  // Toasts
  'profile.toast.updated': 'Profile updated.',
  'profile.toast.error': 'Your profile could not be saved.',
  'profile.toast.passwordChanged': 'Password changed.',
  'profile.toast.passwordError': 'The password could not be changed.',
} as const satisfies Record<string, string>;

export type ProfileMessageId = keyof typeof profileEn;

// Typed against the English key set so the two catalogues cannot drift (1:1).
export const profileTr: Record<ProfileMessageId, string> = {
  // Page
  'profile.title': 'Profilim',
  'profile.description': 'Hesap bilgileriniz ve parolanız.',
  'profile.loading': 'Profiliniz yükleniyor…',
  'profile.loadError': 'Profiliniz yüklenemedi.',

  // Details card
  'profile.details.title': 'Bilgiler',
  'profile.details.description':
    'Hesabınızdaki ad ve iletişim bilgilerini güncelleyin.',
  'profile.details.name': 'Ad',
  'profile.details.surname': 'Soyad',
  'profile.details.email': 'E-posta',
  'profile.details.phoneNumber': 'Telefon',
  'profile.details.username': 'Kullanıcı adı',
  'profile.details.usernameHint': 'Kullanıcı adınız buradan değiştirilemez.',
  'profile.details.roles': 'Roller',
  'profile.details.noRoles': 'Atanmış rol yok.',
  'profile.details.emailConfirmed': 'E-posta doğrulandı',
  'profile.details.emailUnconfirmed': 'E-posta doğrulanmadı',
  'profile.details.save': 'Değişiklikleri kaydet',
  'profile.details.saving': 'Kaydediliyor…',
  'profile.details.invalidEmail': 'Geçerli bir e-posta adresi girin.',
  'profile.details.tooLong': 'En fazla {max} karakter kullanın.',

  // Change password card
  'profile.password.title': 'Parola değiştir',
  'profile.password.description':
    'Yeni bir parola seçin. Bu cihazdaki oturumunuz açık kalır.',
  'profile.password.current': 'Mevcut parola',
  'profile.password.new': 'Yeni parola',
  'profile.password.confirm': 'Yeni parola (tekrar)',
  'profile.password.hint':
    'En az {min} karakter. Kiracınız daha katı bir politika uyguluyor olabilir.',
  'profile.password.mismatch': 'Parolalar eşleşmiyor.',
  'profile.password.tooShort': 'En az {min} karakter kullanın.',
  'profile.password.sameAsCurrent':
    'Yeni parola mevcut paroladan farklı olmalıdır.',
  'profile.password.submit': 'Parolayı değiştir',
  'profile.password.submitting': 'Kaydediliyor…',
  'profile.password.success': 'Parolanız değiştirildi.',
  'profile.password.error': 'Parola değiştirilemedi.',

  // Toasts
  'profile.toast.updated': 'Profil güncellendi.',
  'profile.toast.error': 'Profiliniz kaydedilemedi.',
  'profile.toast.passwordChanged': 'Parola değiştirildi.',
  'profile.toast.passwordError': 'Parola değiştirilemedi.',
};

export const profileMessages: Record<'en' | 'tr', Record<string, string>> = {
  en: profileEn,
  tr: profileTr,
};

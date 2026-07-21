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

  // Two-factor card
  'profile.twoFactor.title': 'Two-factor authentication',
  'profile.twoFactor.description':
    'Add a second step to sign-in using an authenticator app.',
  'profile.twoFactor.idleHint':
    'This device cannot tell whether two-factor is already on for your account. Enable it below, or manage an existing setup.',
  'profile.twoFactor.enableButton': 'Enable two-factor authentication',
  'profile.twoFactor.manageExisting': 'Already set up? Manage it',
  'profile.twoFactor.scanInstruction':
    'Scan this QR code with your authenticator app (Google Authenticator, 1Password, and similar).',
  'profile.twoFactor.qrAlt': 'Two-factor setup QR code',
  'profile.twoFactor.manualInstruction':
    'Cannot scan? Enter this setup key manually instead:',
  'profile.twoFactor.copySecret': 'Copy key',
  'profile.twoFactor.confirmLabel': 'Enter the 6-digit code to confirm',
  'profile.twoFactor.confirmButton': 'Confirm and enable',
  'profile.twoFactor.confirming': 'Enabling…',
  'profile.twoFactor.cancel': 'Cancel',
  'profile.twoFactor.recoveryTitle': 'Save your recovery codes',
  'profile.twoFactor.recoveryWarning':
    'Store these somewhere safe. Each code can be used once, and they will not be shown again.',
  'profile.twoFactor.copyAll': 'Copy all',
  'profile.twoFactor.copied': 'Copied',
  'profile.twoFactor.recoveryDone': 'I have saved my codes',
  'profile.twoFactor.manageDescription':
    'You can turn two-factor off or replace your recovery codes.',
  'profile.twoFactor.disableButton': 'Disable two-factor',
  'profile.twoFactor.regenerateButton': 'Regenerate recovery codes',
  'profile.twoFactor.passwordLabel': 'Current password',
  'profile.twoFactor.disableDialogTitle': 'Disable two-factor authentication',
  'profile.twoFactor.disableDialogDescription':
    'Enter your current password to turn two-factor off. Your recovery codes will be discarded.',
  'profile.twoFactor.disableConfirm': 'Disable',
  'profile.twoFactor.regenerateDialogTitle': 'Regenerate recovery codes',
  'profile.twoFactor.regenerateDialogDescription':
    'Enter your current password. Your existing recovery codes will stop working immediately.',
  'profile.twoFactor.regenerateConfirm': 'Regenerate',
  'profile.twoFactor.setupError': 'Two-factor setup could not be started.',
  'profile.twoFactor.enabledToast': 'Two-factor authentication is on.',
  'profile.twoFactor.enableError': 'That code did not match. Please try again.',
  'profile.twoFactor.disabledToast': 'Two-factor authentication is off.',
  'profile.twoFactor.disableError': 'Two-factor could not be disabled.',
  'profile.twoFactor.regeneratedToast': 'New recovery codes generated.',
  'profile.twoFactor.regenerateError':
    'Recovery codes could not be regenerated.',

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

  // Two-factor card
  'profile.twoFactor.title': 'İki adımlı doğrulama',
  'profile.twoFactor.description':
    'Kimlik doğrulayıcı uygulamayla girişe ikinci bir adım ekleyin.',
  'profile.twoFactor.idleHint':
    'Bu cihaz, hesabınızda iki adımlı doğrulamanın açık olup olmadığını bilemez. Aşağıdan etkinleştirin veya mevcut kurulumu yönetin.',
  'profile.twoFactor.enableButton': 'İki adımlı doğrulamayı etkinleştir',
  'profile.twoFactor.manageExisting': 'Zaten kurdunuz mu? Yönetin',
  'profile.twoFactor.scanInstruction':
    'Bu QR kodunu kimlik doğrulayıcı uygulamanızla (Google Authenticator, 1Password ve benzeri) tarayın.',
  'profile.twoFactor.qrAlt': 'İki adımlı doğrulama kurulum QR kodu',
  'profile.twoFactor.manualInstruction':
    'Tarayamıyor musunuz? Bunun yerine bu kurulum anahtarını elle girin:',
  'profile.twoFactor.copySecret': 'Anahtarı kopyala',
  'profile.twoFactor.confirmLabel': 'Onaylamak için 6 haneli kodu girin',
  'profile.twoFactor.confirmButton': 'Onayla ve etkinleştir',
  'profile.twoFactor.confirming': 'Etkinleştiriliyor…',
  'profile.twoFactor.cancel': 'İptal',
  'profile.twoFactor.recoveryTitle': 'Kurtarma kodlarınızı kaydedin',
  'profile.twoFactor.recoveryWarning':
    'Bunları güvenli bir yerde saklayın. Her kod yalnızca bir kez kullanılabilir ve tekrar gösterilmeyecektir.',
  'profile.twoFactor.copyAll': 'Tümünü kopyala',
  'profile.twoFactor.copied': 'Kopyalandı',
  'profile.twoFactor.recoveryDone': 'Kodlarımı kaydettim',
  'profile.twoFactor.manageDescription':
    'İki adımlı doğrulamayı kapatabilir veya kurtarma kodlarınızı yenileyebilirsiniz.',
  'profile.twoFactor.disableButton': 'İki adımlı doğrulamayı kapat',
  'profile.twoFactor.regenerateButton': 'Kurtarma kodlarını yenile',
  'profile.twoFactor.passwordLabel': 'Mevcut parola',
  'profile.twoFactor.disableDialogTitle': 'İki adımlı doğrulamayı kapat',
  'profile.twoFactor.disableDialogDescription':
    'İki adımlı doğrulamayı kapatmak için mevcut parolanızı girin. Kurtarma kodlarınız silinecektir.',
  'profile.twoFactor.disableConfirm': 'Kapat',
  'profile.twoFactor.regenerateDialogTitle': 'Kurtarma kodlarını yenile',
  'profile.twoFactor.regenerateDialogDescription':
    'Mevcut parolanızı girin. Var olan kurtarma kodlarınız anında geçersiz olacaktır.',
  'profile.twoFactor.regenerateConfirm': 'Yenile',
  'profile.twoFactor.setupError': 'İki adımlı doğrulama kurulumu başlatılamadı.',
  'profile.twoFactor.enabledToast': 'İki adımlı doğrulama açık.',
  'profile.twoFactor.enableError': 'Kod eşleşmedi. Lütfen tekrar deneyin.',
  'profile.twoFactor.disabledToast': 'İki adımlı doğrulama kapalı.',
  'profile.twoFactor.disableError': 'İki adımlı doğrulama kapatılamadı.',
  'profile.twoFactor.regeneratedToast': 'Yeni kurtarma kodları oluşturuldu.',
  'profile.twoFactor.regenerateError': 'Kurtarma kodları yenilenemedi.',

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

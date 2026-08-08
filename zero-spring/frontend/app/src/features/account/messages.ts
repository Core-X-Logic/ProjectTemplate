/**
 * Account feature message catalogue (flat, dot-keyed; en + tr, keys 1:1).
 *
 * Parity is enforced at COMPILE TIME: `accountEn` is the key source of truth
 * (`as const satisfies`), and `accountTr` is typed `Record<AccountMessageId,
 * string>` — a missing or extra Turkish key fails `tsc -b`.
 */

export const accountEn = {
  // Forgot password
  'account.forgot.title': 'Reset your password',
  'account.forgot.subtitle':
    'Enter your username or email address and we will send you a reset code.',
  'account.forgot.username': 'Username or email',
  'account.forgot.usernamePlaceholder': 'you@example.com',
  'account.forgot.tenant': 'Tenant',
  'account.forgot.tenantPlaceholder': 'Leave empty for the default tenant',
  'account.forgot.tenantHint':
    'If you sign in under a specific tenant, enter its name here.',
  'account.forgot.submit': 'Send reset code',
  'account.forgot.submitting': 'Sending…',
  'account.forgot.backToLogin': 'Back to sign in',
  // Enumeration-safe wording: the backend answers 204 whether or not the
  // account exists, so this text must not confirm that it does.
  'account.forgot.sentTitle': 'Check your inbox',
  'account.forgot.sentDescription':
    'If an account matches what you entered, a reset code has been sent to its email address. The code is single-use.',
  'account.forgot.sentHint': 'Already have the code?',
  'account.forgot.sentAction': 'Enter it here',
  'account.forgot.error': 'The request could not be sent. Please try again.',

  // Reset password
  'account.reset.title': 'Choose a new password',
  'account.reset.subtitle':
    'Paste the code from the email and pick a new password.',
  'account.reset.code': 'Reset code',
  'account.reset.codePlaceholder': 'The code from your email',
  'account.reset.codeFromLink': 'The code was taken from your link.',
  'account.reset.newPassword': 'New password',
  'account.reset.confirmPassword': 'Confirm new password',
  'account.reset.passwordHint':
    'At least {min} characters. Your tenant may enforce a stricter policy.',
  'account.reset.mismatch': 'The passwords do not match.',
  'account.reset.tooShort': 'Use at least {min} characters.',
  'account.reset.tooLong': 'Use at most {max} characters.',
  'account.reset.submit': 'Set new password',
  'account.reset.submitting': 'Saving…',
  'account.reset.error':
    'The password could not be reset. The code may be invalid or already used.',
  'account.reset.requestNew': 'Request a new code',
  'account.reset.doneTitle': 'Password updated',
  'account.reset.doneDescription':
    'You can now sign in with your new password.',
  'account.reset.goToLogin': 'Go to sign in',

  // Email confirmation (the emailed link points at this screen)
  'account.confirm.title': 'Confirming your email',
  'account.confirm.pending': 'Confirming…',
  'account.confirm.missingCode':
    'This link is missing its confirmation code. Please open the link from your email again.',
  'account.confirm.doneTitle': 'Email confirmed',
  'account.confirm.doneDescription': 'Your email address has been verified.',
  'account.confirm.error':
    'The email could not be confirmed. The code may be invalid or already used.',
  'account.confirm.goToLogin': 'Go to sign in',

  // Invitation accept (the emailed link points at this screen)
  'account.invite.title': 'Activate your account',
  'account.invite.subtitle':
    'You were invited to join. Choose a password to activate your account.',
  'account.invite.loading': 'Checking your invitation…',
  'account.invite.username': 'Username',
  'account.invite.usernameHint': 'You will sign in with this username.',
  'account.invite.password': 'Password',
  'account.invite.confirmPassword': 'Confirm password',
  'account.invite.passwordHint':
    'At least {min} characters. Your tenant may enforce a stricter policy.',
  'account.invite.mismatch': 'The passwords do not match.',
  'account.invite.tooShort': 'Use at least {min} characters.',
  'account.invite.tooLong': 'Use at most {max} characters.',
  'account.invite.submit': 'Activate account',
  'account.invite.submitting': 'Activating…',
  'account.invite.error':
    'The account could not be activated. The invitation may be invalid or expired.',
  'account.invite.doneTitle': 'Account activated',
  'account.invite.doneDescription':
    'You can now sign in with your username and the password you chose.',
  'account.invite.goToLogin': 'Go to sign in',
  'account.invite.missingToken':
    'This link is missing its invitation token. Please open the link from your email again.',
  'account.invite.invalidTitle': 'Invitation not valid',
  'account.invite.invalidDescription':
    'The invitation is invalid or has expired. Ask your administrator to send a new one.',
  'account.invite.alreadyTitle': 'Invitation already used',
  'account.invite.alreadyDescription':
    'This invitation was already accepted. You can sign in with your credentials.',
} as const satisfies Record<string, string>;

export type AccountMessageId = keyof typeof accountEn;

// Typed against the English key set so the two catalogues cannot drift (1:1).
export const accountTr: Record<AccountMessageId, string> = {
  // Forgot password
  'account.forgot.title': 'Parolanızı sıfırlayın',
  'account.forgot.subtitle':
    'Kullanıcı adınızı veya e-posta adresinizi girin, size bir sıfırlama kodu gönderelim.',
  'account.forgot.username': 'Kullanıcı adı veya e-posta',
  'account.forgot.usernamePlaceholder': 'siz@ornek.com',
  'account.forgot.tenant': 'Kiracı',
  'account.forgot.tenantPlaceholder': 'Varsayılan kiracı için boş bırakın',
  'account.forgot.tenantHint':
    'Belirli bir kiracı altında giriş yapıyorsanız kiracı adını buraya yazın.',
  'account.forgot.submit': 'Sıfırlama kodu gönder',
  'account.forgot.submitting': 'Gönderiliyor…',
  'account.forgot.backToLogin': 'Girişe dön',
  'account.forgot.sentTitle': 'Gelen kutunuzu kontrol edin',
  'account.forgot.sentDescription':
    'Girdiğiniz bilgiyle eşleşen bir hesap varsa, e-posta adresine bir sıfırlama kodu gönderildi. Kod tek kullanımlıktır.',
  'account.forgot.sentHint': 'Kod elinizde mi?',
  'account.forgot.sentAction': 'Buraya girin',
  'account.forgot.error': 'İstek gönderilemedi. Lütfen tekrar deneyin.',

  // Reset password
  'account.reset.title': 'Yeni parola belirleyin',
  'account.reset.subtitle':
    'E-postadaki kodu yapıştırın ve yeni bir parola seçin.',
  'account.reset.code': 'Sıfırlama kodu',
  'account.reset.codePlaceholder': 'E-postanızdaki kod',
  'account.reset.codeFromLink': 'Kod bağlantınızdan alındı.',
  'account.reset.newPassword': 'Yeni parola',
  'account.reset.confirmPassword': 'Yeni parola (tekrar)',
  'account.reset.passwordHint':
    'En az {min} karakter. Kiracınız daha katı bir politika uyguluyor olabilir.',
  'account.reset.mismatch': 'Parolalar eşleşmiyor.',
  'account.reset.tooShort': 'En az {min} karakter kullanın.',
  'account.reset.tooLong': 'En fazla {max} karakter kullanın.',
  'account.reset.submit': 'Yeni parolayı kaydet',
  'account.reset.submitting': 'Kaydediliyor…',
  'account.reset.error':
    'Parola sıfırlanamadı. Kod geçersiz veya daha önce kullanılmış olabilir.',
  'account.reset.requestNew': 'Yeni kod isteyin',
  'account.reset.doneTitle': 'Parola güncellendi',
  'account.reset.doneDescription':
    'Artık yeni parolanızla giriş yapabilirsiniz.',
  'account.reset.goToLogin': 'Girişe git',

  // Email confirmation
  'account.confirm.title': 'E-postanız doğrulanıyor',
  'account.confirm.pending': 'Doğrulanıyor…',
  'account.confirm.missingCode':
    'Bu bağlantıda doğrulama kodu yok. Lütfen e-postanızdaki bağlantıyı tekrar açın.',
  'account.confirm.doneTitle': 'E-posta doğrulandı',
  'account.confirm.doneDescription': 'E-posta adresiniz doğrulandı.',
  'account.confirm.error':
    'E-posta doğrulanamadı. Kod geçersiz veya daha önce kullanılmış olabilir.',
  'account.confirm.goToLogin': 'Girişe git',

  // Davet kabulü (e-postadaki bağlantı bu ekrana gelir)
  'account.invite.title': 'Hesabınızı etkinleştirin',
  'account.invite.subtitle':
    'Katılmanız için davet edildiniz. Hesabınızı etkinleştirmek için bir parola belirleyin.',
  'account.invite.loading': 'Davetiniz kontrol ediliyor…',
  'account.invite.username': 'Kullanıcı adı',
  'account.invite.usernameHint': 'Bu kullanıcı adıyla giriş yapacaksınız.',
  'account.invite.password': 'Parola',
  'account.invite.confirmPassword': 'Parola (tekrar)',
  'account.invite.passwordHint':
    'En az {min} karakter. Kiracınız daha katı bir politika uyguluyor olabilir.',
  'account.invite.mismatch': 'Parolalar eşleşmiyor.',
  'account.invite.tooShort': 'En az {min} karakter kullanın.',
  'account.invite.tooLong': 'En fazla {max} karakter kullanın.',
  'account.invite.submit': 'Hesabı etkinleştir',
  'account.invite.submitting': 'Etkinleştiriliyor…',
  'account.invite.error':
    'Hesap etkinleştirilemedi. Davet geçersiz veya süresi dolmuş olabilir.',
  'account.invite.doneTitle': 'Hesap etkinleştirildi',
  'account.invite.doneDescription':
    'Artık kullanıcı adınız ve belirlediğiniz parolayla giriş yapabilirsiniz.',
  'account.invite.goToLogin': 'Girişe git',
  'account.invite.missingToken':
    'Bu bağlantıda davet kodu yok. Lütfen e-postanızdaki bağlantıyı tekrar açın.',
  'account.invite.invalidTitle': 'Davet geçerli değil',
  'account.invite.invalidDescription':
    'Davet geçersiz veya süresi dolmuş. Yöneticinizden yeni bir davet göndermesini isteyin.',
  'account.invite.alreadyTitle': 'Davet zaten kullanılmış',
  'account.invite.alreadyDescription':
    'Bu davet daha önce kabul edilmiş. Bilgilerinizle giriş yapabilirsiniz.',
};

export const accountMessages: Record<'en' | 'tr', Record<string, string>> = {
  en: accountEn,
  tr: accountTr,
};

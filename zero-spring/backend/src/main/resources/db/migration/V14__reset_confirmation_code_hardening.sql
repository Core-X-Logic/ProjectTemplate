-- R-44 kapanisi — sifre sifirlama ve e-posta dogrulama kodlari V15 davet token'iyla AYNI desene
-- tasinir: DB'de yalniz SHA-256 hex + expiry; ham kod YALNIZ e-postada yasar.
--
-- NEDEN (R-44): `password_reset_code` ve `email_confirmation_code` V2'den beri DUZ METIN ve
-- SURESIZDI. Kod tek kullanimlik ve 32 bayt SecureRandom oldugu icin cevrimici brute force pratik
-- degildi; gercek pencere bir DB dump'i/okuma erisiminin CANLI kodu vermesiydi — ve kod suresiz
-- kaldikca pencere kendiliginden kapanmiyordu. "Invalid or expired reset code" mesajindaki
-- *expired* kosulu kodda HIC gerceklesmeyen bir kosuldu.
--
-- TASARIM NOTLARI (kodun soyleyemedikleri):
--
-- * YENI kolonlar; mevcut kolonlar NE yeniden adlandirilir NE de bosaltilir. Canli bir kurulumda
--   bekleyen (gonderilmis ama henuz kullanilmamis) kodlar olur; bu migration onlari SILMEZ.
--   Gecersizlik semantikle gelir: yeni akis YALNIZ *_hash kolonunu okur, eski kodun hash'i hicbir
--   satirda yoktur → NULL hash hicbir girdiyle eslesmez (fail-closed). TRUNCATE/UPDATE olmamasi
--   ayrica rolling-deploy guvenligidir: deploy penceresinde hala kosan eski surum eski kolonlari
--   okumaya/yazmaya devam eder.
--
-- * Eski iki kolon bu surumde DUSURULMEZ (rolling deploy: eski kod hala yaziyor). Eski surum
--   tamamen emekli olduktan sonra ayri bir V ile dusurulmelidir; ddl-auto=validate entity'de
--   olmayan DB kolonuna ses cikarmaz, o yuzden bu aradaki durum sessiz ve guvenlidir.
--
-- * Sureler V15 davet deseninin mantigiyla secildi ama akisa gore farklidir: reset kodu 1 SAAT
--   (oturum acamayan biri icin kisa bir kurtarma penceresi yeter; kod ne kadar uzun yasarsa dump
--   penceresi o kadar acik kalir), dogrulama kodu 72 SAAT (dusuk riskli — hesap zaten var, kod
--   yalniz `email_confirmed` bayragini ceviriyor; hafta sonuna denk gelen kayit yasasin).
--   Sureleri yazan tek yer `AccountService`/`ProfileService` sabitleridir; kolonlar yalniz tasir.
--
-- * Ayri bir "EXPIRED" durumu/temizlik isi BILEREK yok — sure asimi okuma aninda
--   `expires_at < now()` ile turetilir. Zamanlanmis bir temizleyici `@Component` olurdu ve GUC
--   yazmadigi icin politikali `users` tablosunda 0 satir gorup sessizce "basarili" donerdi
--   (R-46'nin tam sinifi).
alter table users add column password_reset_code_hash varchar(128);
alter table users add column password_reset_code_expires_at timestamptz;
alter table users add column email_confirmation_code_hash varchar(128);
alter table users add column email_confirmation_code_expires_at timestamptz;

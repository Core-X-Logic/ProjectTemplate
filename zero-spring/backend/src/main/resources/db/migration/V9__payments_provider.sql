-- P2'-B: payments.provider — mutabakat job'u, takılı bir satır için HANGİ sağlayıcının sorgu
-- API'sine gidebileceğini bilmek zorunda (iyzico: retrieve VAR; PayTR/Stripe: yok → job atlar,
-- runbook §3.9 ağ olarak kalır). Sütun nullable: V9 öncesi satırlar ve backfill'in eşleyemedikleri
-- null kalır; job bunları LOG'LAYARAK atlar, sessizce değil.
--
-- Uygulanmış migration DÜZENLENMEZ (checksum); bu yüzden V8'e dokunmak yerine yeni dosya.

alter table payments add column provider varchar(32);

-- Muhafazakâr backfill: yalnız kesin desenler. Stripe checkout session id'leri "cs_" ile,
-- PayTRBillingProvider.newMerchantOid çıktısı "ZP" ile başlar. Eşleşmeyen (ya da null session'lı)
-- satır null kalır — yanlış atfetmektense atlamak; iki eski sağlayıcının da sorgu API'si olmadığı
-- için backfill'in tek işlevi job'un "atlandı" sayacını anlamlı kılmaktır.
update payments set provider = 'stripe'
 where provider is null and external_session_id like 'cs\_%' escape '\';
update payments set provider = 'paytr'
 where provider is null and external_session_id like 'ZP%';

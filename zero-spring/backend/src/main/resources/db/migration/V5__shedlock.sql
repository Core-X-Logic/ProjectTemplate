-- F5 Slice B: ShedLock kilit deposu — çok-instance'ta yaşam döngüsü job'ı tek çalışır (K10 çözümü:
-- kaynakta worker'larda distributed lock hiç yoktu, aynı tenant birden çok node'da işlenebiliyordu).
--
-- Tablo/kolon adları ShedLock'un JdbcTemplateLockProvider sözleşmesi tarafından dayatılır
-- (name / lock_until / locked_at / locked_by); proje adlandırma konvansiyonundan tek sapma budur.
-- Zaman kolonları proje konvansiyonuna uygun şekilde timestamptz (UTC) tutulur.

create table shedlock (
  name varchar(64) not null,
  lock_until timestamptz not null,
  locked_at timestamptz not null,
  locked_by varchar(255) not null,
  constraint pk_shedlock primary key (name)
);

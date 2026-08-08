package com.mycompanyname.zero.config;

import com.mycompanyname.zero.AbstractIntegrationIT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Kimlik ayrımının GERÇEKTEN yürürlükte olduğunu kanıtlar (RLS taban çizgisi Adım 1).
 *
 * <p><strong>Bu sınıf neden var.</strong> Rol ayrımı sessizce başarısız olabilen bir değişikliktir:
 * uygulama havuzu yanlışlıkla migration kimliğiyle (owner ya da superuser) bağlanırsa
 * <em>hiçbir test kırmızıya dönmez</em> — suite'in tamamı yeşil kalır, çünkü daha çok yetkiyle
 * her şey çalışır. Ayrımın tek belirtisi, RLS politikalarının hiçbir şeyi kısıtlamaması olurdu:
 * {@code FORCE ROW LEVEL SECURITY} owner'ı bağlar ama superuser'ı bağlamaz, yani izolasyon
 * testleri <strong>yalancı yeşil</strong> döner ve bunu söyleyen hiçbir satır olmaz.
 *
 * <p>Buradaki üç iddia o boşluğu kapatır ve RLS politikalarının (V12/V13) üzerine inşa edildiği
 * zemini ölçer. Sıra önemli: politika yazmadan önce bu testin yeşil olması gerekir, aksi halde
 * politikaların kanıt değeri yoktur.
 *
 * <p><strong>Neden bu test boş yeşil OLAMAZ.</strong> Mutasyon denemesine gerek yok, iddialar
 * birbirini kilitliyor: birincisi {@code current_user == zero_app} der, üçüncüsü
 * {@code installed_by != zero_app} der. İki kimlik aslında aynı olsaydı bu ikisi
 * <em>mantıksal olarak</em> birlikte geçemezdi. İkincisi ayrı bir sınıfı kapatır: doğru ada sahip
 * ama {@code SUPERUSER}/{@code BYPASSRLS} bir rol ilk testi geçer ve RLS'i yine baypas ederdi.
 */
class AppDbIdentityIT extends AbstractIntegrationIT {

    @Autowired
    private DataSource dataSource;

    private JdbcTemplate jdbc;

    private JdbcTemplate jdbc() {
        if (jdbc == null) {
            jdbc = new JdbcTemplate(dataSource);
        }
        return jdbc;
    }

    /** Uygulama havuzu app rolüyle bağlanır — migration kimliğiyle değil. */
    @Test
    void theApplicationPoolConnectsAsTheApplicationRole() {
        assertThat(jdbc().queryForObject("select current_user", String.class))
                .as("uygulama havuzu '%s' ile bağlanmalı; migration kimliğine düşerse RLS "
                        + "politikaları hiçbir şeyi kısıtlamaz ve bunu söyleyen bir hata olmaz",
                        APP_DB_USERNAME)
                .isEqualTo(APP_DB_USERNAME);
    }

    /**
     * Rolün YETKİLERİ de ölçülür, yalnız adı değil. Doğru ada sahip ama {@code SUPERUSER} ya da
     * {@code BYPASSRLS} bir rol, yukarıdaki testi geçer ve RLS'i yine baypas eder.
     */
    @Test
    void theApplicationRoleCanNeitherBeSuperuserNorBypassRls() {
        Boolean superuser = jdbc().queryForObject(
                "select rolsuper from pg_roles where rolname = ?", Boolean.class, APP_DB_USERNAME);
        Boolean bypassRls = jdbc().queryForObject(
                "select rolbypassrls from pg_roles where rolname = ?", Boolean.class, APP_DB_USERNAME);

        assertThat(superuser).as("superuser her politikayı baypas eder").isFalse();
        assertThat(bypassRls).as("BYPASSRLS tam olarak bu koruma katmanını kapatır").isFalse();
    }

    /**
     * Migration'ları BAŞKA bir kimlik koştu. Kanıt Flyway'in kendi kaydında: {@code installed_by}
     * o an bağlı olan kullanıcıdır. İki kimlik aynı olsaydı bu satır app rolünü gösterirdi ve
     * "ayrım var" iddiası kâğıt üstünde kalırdı.
     */
    @Test
    void migrationsRanUnderADifferentIdentity() {
        String installedBy = jdbc().queryForObject(
                "select installed_by from flyway_schema_history order by installed_rank limit 1",
                String.class);

        assertThat(installedBy)
                .as("Flyway app rolüyle koşarsa owner ayrımı ve FORCE ROW LEVEL SECURITY anlamsızlaşır")
                .isNotNull()
                .isNotEqualTo(APP_DB_USERNAME);
    }
}

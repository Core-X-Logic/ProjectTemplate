package com.mycompanyname.zero.config;

import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;

import javax.sql.DataSource;
import java.time.Clock;

/**
 * Scheduling infrastructure for the SaaS lifecycle job.
 *
 * <p><b>ShedLock (K10).</b> The source system ran its subscription workers with no distributed lock,
 * so on more than one node the same tenant could be processed several times. Every scheduled method
 * here is wrapped by {@code @SchedulerLock}, whose lock rows live in the {@code shedlock} table
 * created by {@code V5__shedlock.sql}. The default {@code interceptMode} is {@code PROXY_METHOD},
 * which means the lock is honoured for <em>any</em> call arriving through the bean proxy, not only
 * for calls made by the scheduler.
 *
 * <p><b>Clock.</b> All time-dependent SaaS logic reads {@link Clock} instead of
 * {@code Instant.now()}, so a test can move time forward by substituting the bean. The production
 * bean is {@link Clock#systemUTC()}, matching the project-wide "java.time, UTC" rule.
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@EnableSchedulerLock(defaultLockAtMostFor = "${zero.saas.lifecycle.lock-at-most-for:PT10M}")
public class SchedulingConfig {

    @Bean
    public LockProvider lockProvider(DataSource dataSource) {
        return new JdbcTemplateLockProvider(JdbcTemplateLockProvider.Configuration.builder()
                .withJdbcTemplate(new JdbcTemplate(dataSource))
                .withTableName("shedlock")
                // PROD-R15c: lock timestamps come from the database clock instead of each node's.
                // Without this, a node whose clock runs fast considers a held lock expired and runs
                // the job concurrently — the very thing ShedLock is here to prevent.
                //
                // This requires the shedlock time columns to be TIMESTAMP WITHOUT TIME ZONE: the SQL
                // ShedLock emits here is `timezone('utc', CURRENT_TIMESTAMP)`, a tz-less UTC wall
                // clock. V5 originally declared them timestamptz, which made PostgreSQL reinterpret
                // that value in the writing node's session time zone — so two nodes in different
                // zones wrote instants that disagreed by their offset and stopped excluding each
                // other. V6__hardening.sql converts the columns; the two must stay in step.
                .usingDbTime()
                .build());
    }

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}

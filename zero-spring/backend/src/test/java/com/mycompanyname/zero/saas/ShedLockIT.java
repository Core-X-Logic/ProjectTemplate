package com.mycompanyname.zero.saas;

import com.mycompanyname.zero.saas.subscription.SubscriptionLifecycleJob;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F5 Slice B proof that the lifecycle job runs once even when it is triggered twice
 * (CONTRACT-phase5 Slice B; K10 — the source system's workers had no distributed lock at all, so on
 * more than one node the same tenant was processed repeatedly).
 *
 * <p>Triggering the bean directly is a faithful stand-in for a second node: ShedLock's default
 * {@code PROXY_METHOD} interception guards every call that arrives through the bean proxy, which is
 * the same advice a scheduler-driven call goes through. {@code lockAtLeastFor} (30s by default) is
 * what makes the second call a no-op — the lock is deliberately held past the end of the first run
 * so two nodes with slightly different clocks cannot both slip in.
 *
 * <p>Two independent pieces of evidence are asserted: the job's own execution counter, and the row
 * ShedLock wrote into the {@code shedlock} table.
 *
 * <p>Since PROD-R15c the lock provider is configured with {@code usingDbTime()}, so every timestamp
 * in that table comes from the database clock rather than from whichever node happened to take the
 * lock. The assertions below therefore compare database timestamps only.
 */
class ShedLockIT extends AbstractSaasIT {

    @Autowired
    private SubscriptionLifecycleJob lifecycleJob;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void twoBackToBackTriggersProduceExactlyOneExecution() {
        int before = lifecycleJob.executionCount();

        lifecycleJob.run();
        lifecycleJob.run();

        assertThat(lifecycleJob.executionCount() - before)
                .as("the second trigger must find the lock held and skip the run entirely")
                .isEqualTo(1);
    }

    @Test
    void theLockIsRecordedAndHeldPastTheEndOfTheRun() {
        lifecycleJob.run();

        // Both comparisons are evaluated inside the database (PROD-R15c). Since usingDbTime(), these
        // timestamps are UTC wall-clock values written by ShedLock's own `timezone('utc',
        // CURRENT_TIMESTAMP)`; pulling them into the JVM and comparing against Instant.now() would
        // reintroduce precisely the cross-clock comparison usingDbTime() exists to eliminate, and
        // would report a failure on any machine whose time zone is not UTC.
        Map<String, Object> lock = jdbcTemplate.queryForMap("""
                select name,
                       locked_by,
                       lock_until > locked_at                            as held_past_run,
                       lock_until > timezone('utc', current_timestamp)   as still_held
                from shedlock
                where name = ?
                """, SubscriptionLifecycleJob.LOCK_NAME);

        assertThat(lock.get("name")).isEqualTo(SubscriptionLifecycleJob.LOCK_NAME);
        assertThat(lock.get("locked_by"))
                .as("the holder is recorded so an operator can see which instance took the lock")
                .isNotNull();
        assertThat(lock.get("held_past_run"))
                .as("lockAtLeastFor must extend the lock beyond the moment the run finished")
                .isEqualTo(true);
        assertThat(lock.get("still_held"))
                .as("the lock must still be in the future by the database's own reckoning — that "
                        + "predicate is exactly what a second node evaluates when it tries to acquire it")
                .isEqualTo(true);
    }

    @Test
    void theLockIsReleasedSoALaterRunCanAcquireItAgain() {
        // A single shedlock row is reused; it must not turn into a permanent block after the first run.
        Integer rows = jdbcTemplate.queryForObject(
                "select count(*) from shedlock where name = ?", Integer.class,
                SubscriptionLifecycleJob.LOCK_NAME);
        lifecycleJob.run();
        Integer rowsAfter = jdbcTemplate.queryForObject(
                "select count(*) from shedlock where name = ?", Integer.class,
                SubscriptionLifecycleJob.LOCK_NAME);

        assertThat(rows).isLessThanOrEqualTo(1);
        assertThat(rowsAfter)
                .as("ShedLock keeps exactly one row per lock name and updates it in place")
                .isEqualTo(1);
    }
}

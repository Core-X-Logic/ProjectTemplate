package com.mycompanyname.zero.saas.billing;

import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.Optional;

public interface WebhookEventRepository extends JpaRepository<WebhookEvent, Long> {

    /**
     * The dedup insert. Native on purpose: {@code on conflict do nothing} is the one primitive that
     * makes "first delivery wins" ATOMIC under concurrent redeliveries — a read-then-insert would
     * let two simultaneous deliveries both read "absent" and one of them die on the unique index
     * with a 409, which is exactly the duplicate-must-never-4xx bug (G14) this slice closes. Under
     * concurrency the second insert blocks on the first's uncommitted row and resolves to 0 rows the
     * moment the first commits.
     *
     * @return 1 when this call inserted the row (the caller owns processing), 0 when the event was
     *         already recorded (duplicate delivery — answer 200, touch nothing)
     */
    @Modifying
    @Query(value = "insert into webhook_events (provider, event_id, event_type, payload, received_at, status) "
            + "values (:provider, :eventId, :eventType, :payload, :receivedAt, :status) "
            + "on conflict (provider, event_id) do nothing", nativeQuery = true)
    int insertIfAbsent(@Param("provider") String provider,
                       @Param("eventId") String eventId,
                       @Param("eventType") String eventType,
                       @Param("payload") String payload,
                       @Param("receivedAt") Instant receivedAt,
                       @Param("status") String status);

    Optional<WebhookEvent> findByProviderAndEventId(String provider, String eventId);
}

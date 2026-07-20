package com.mycompanyname.zero.saas.billing;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface PaymentRepository extends JpaRepository<Payment, Long> {

    /** Plain lookup, for callers that only read. Backed by {@code uq_payments_external_session}. */
    Optional<Payment> findByExternalSessionId(String externalSessionId);

    /**
     * The WEBHOOK-PATH lookup, and only that path's: {@code select ... for update}.
     *
     * <p>Why the plain lookup is not enough there: the event dedup serializes deliveries of the
     * SAME event id, but two checkout-completed events with DIFFERENT ids naming the same session
     * both pass dedup. Without this lock both could read the payment as {@code NOT_PAID} and both
     * activate — a double period extension. With {@code PESSIMISTIC_WRITE} the second transaction
     * blocks on the row until the first commits, then reads {@code PAID} and the payment-status
     * guard in {@code BillingWebhookService} fires. Other callers keep the plain lookup so a
     * dashboard read can never queue behind a webhook.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Payment p where p.externalSessionId = :externalSessionId")
    Optional<Payment> findByExternalSessionIdForUpdate(@Param("externalSessionId") String externalSessionId);
}

package com.mycompanyname.zero.identity.auth;

import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Iterator;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory, single-use, short-TTL store for impersonation hand-off tokens.
 *
 * <p>Deliberately not backed by Redis: the integration test profile runs with only a Postgres
 * container and {@code spring.cache.type=simple}, so an in-process store keeps the impersonation
 * flow testable without extra infrastructure while honouring the 30s single-use contract.
 */
@Component
public class ImpersonationTokenStore {

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final int TOKEN_BYTES = 32;

    private final Map<String, Ticket> tickets = new ConcurrentHashMap<>();

    /** Actor (real user) identity plus the impersonation target, with an absolute expiry. */
    public record Ticket(Long actorUserId, Long actorTenantId, Long targetUserId, Instant expiresAt) {
    }

    public String issue(Long actorUserId, Long actorTenantId, Long targetUserId, Duration ttl) {
        purgeExpired();
        byte[] bytes = new byte[TOKEN_BYTES];
        SECURE_RANDOM.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        tickets.put(token, new Ticket(actorUserId, actorTenantId, targetUserId, Instant.now().plus(ttl)));
        return token;
    }

    /**
     * Atomically removes and returns the ticket if it is present, unexpired, and was minted for
     * {@code callerUserId} (single use).
     *
     * <p>R-40. The actor is a parameter rather than something the caller reads off the returned
     * ticket, so the bind cannot be forgotten at a call site: there is no way to obtain a
     * {@code Ticket} without having already named the principal it must belong to. Previously the
     * ticket string alone was sufficient, and a string that travels through a URL body, a proxy log
     * or a support transcript is not a secret one can rely on for authorization — it only has to
     * leak within its 30 second life for another authenticated user to spend it.
     *
     * <p><b>A mismatching actor does not burn the ticket.</b> The whole operation runs inside one
     * {@code compute}, and on a mismatch the mapping is put back unchanged, so a wrong caller cannot
     * turn a leaked ticket into a denial of the legitimate hand-off — it would otherwise be possible
     * to invalidate every ticket one could guess at. Expiry still drops the entry, and a matching
     * actor still consumes it exactly once. {@code ConcurrentHashMap.compute} holds the bin lock for
     * the duration, so two concurrent redemptions of the same ticket cannot both succeed.
     *
     * <p>An empty result is returned for unknown, expired, and foreign tickets alike. The caller
     * therefore cannot answer differently for the three, which keeps the endpoint from confirming
     * that a given string is a live ticket belonging to somebody else.
     */
    public Optional<Ticket> consume(String token, Long callerUserId) {
        if (token == null || callerUserId == null) {
            return Optional.empty();
        }
        Ticket[] consumed = new Ticket[1];
        tickets.compute(token, (key, ticket) -> {
            if (ticket == null) {
                return null;
            }
            if (ticket.expiresAt().isBefore(Instant.now())) {
                return null;
            }
            if (!callerUserId.equals(ticket.actorUserId())) {
                return ticket;
            }
            consumed[0] = ticket;
            return null;
        });
        return Optional.ofNullable(consumed[0]);
    }

    private void purgeExpired() {
        Instant now = Instant.now();
        Iterator<Map.Entry<String, Ticket>> it = tickets.entrySet().iterator();
        while (it.hasNext()) {
            if (it.next().getValue().expiresAt().isBefore(now)) {
                it.remove();
            }
        }
    }
}

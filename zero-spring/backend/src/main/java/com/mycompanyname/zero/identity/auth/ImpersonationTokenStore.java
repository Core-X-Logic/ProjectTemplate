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

    /** Atomically removes and returns the ticket if present and not expired (single use). */
    public Optional<Ticket> consume(String token) {
        if (token == null) {
            return Optional.empty();
        }
        Ticket ticket = tickets.remove(token);
        if (ticket == null || ticket.expiresAt().isBefore(Instant.now())) {
            return Optional.empty();
        }
        return Optional.of(ticket);
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

package com.mycompanyname.zero.saas.billing.web;

import com.mycompanyname.zero.saas.billing.BillingWebhookService;
import com.mycompanyname.zero.shared.web.EndpointPolicy;
import com.mycompanyname.zero.shared.web.EndpointPolicy.Exposure;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Stripe webhook intake. Anonymous BY NECESSITY: Stripe calls it and holds no credential of ours —
 * the authentication is the {@code Stripe-Signature} header, verified offline against the endpoint
 * secret before anything is stored. Full anonymous wiring (all four registrations, each enforced by
 * a gate):
 *
 * <ul>
 *   <li>{@code @EndpointPolicy(ANONYMOUS)} here — the handler's claim;</li>
 *   <li>{@code SecurityConfig}: {@code permitAll("/api/billing/webhook/stripe")} — the grant;</li>
 *   <li>{@code ArchitectureRules.INTENTIONALLY_ANONYMOUS} — the Rule 5 registration;</li>
 *   <li>{@code zero.ratelimit.paths} — anonymous + {@code @RequestBody} means throttled
 *       ({@code SecurityPathBindingIT.everyAnonymousBodyHandlerIsThrottled} derives that
 *       obligation, it is not optional).</li>
 * </ul>
 *
 * <p>The body is taken as a RAW {@code String}: signature verification runs over the exact bytes
 * Stripe signed, and any DTO binding before verification would both break the HMAC and process
 * unauthenticated input.
 *
 * <p>Responses: 200 for processed, ignored AND duplicate deliveries (a duplicate answered 4xx is
 * the measured source bug that caused infinite Stripe retries); 400 only for a failed signature;
 * 404 when billing is disabled (the surface does not exist — see
 * {@code BillingWebhookService#requireProvider}); 500 when processing fails after verification, in
 * which case the whole transaction — dedup row included — rolled back and Stripe's retry is safe.
 */
@RestController
@RequestMapping("/api/billing")
@RequiredArgsConstructor
public class BillingWebhookController {

    private final BillingWebhookService webhookService;

    @PostMapping("/webhook/stripe")
    @EndpointPolicy(Exposure.ANONYMOUS)
    public ResponseEntity<Void> stripeWebhook(
            @RequestBody String payload,
            @RequestHeader(value = "Stripe-Signature", required = false) String signatureHeader) {
        webhookService.handle(payload, signatureHeader);
        // Deliberately bodyless: Stripe reads the status code and nothing else, and echoing any
        // detail back to an unauthenticated caller buys nothing.
        return ResponseEntity.ok().build();
    }
}

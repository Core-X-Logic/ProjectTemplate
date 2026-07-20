package com.mycompanyname.zero.saas.billing.web;

import com.mycompanyname.zero.saas.billing.BillingWebhookService;
import com.mycompanyname.zero.saas.billing.IyzicoBillingProvider;
import com.mycompanyname.zero.saas.billing.PayTRBillingProvider;
import com.mycompanyname.zero.saas.billing.StripeBillingProvider;
import com.mycompanyname.zero.shared.web.EndpointPolicy;
import com.mycompanyname.zero.shared.web.EndpointPolicy.Exposure;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;

/**
 * Provider webhook intake — ONE METHOD PER PROVIDER, each on an EXACT path. Anonymous BY NECESSITY:
 * the provider calls it and holds no credential of ours — the authentication is the provider's own
 * proof (Stripe: the {@code Stripe-Signature} header; PayTR: the {@code hash} form field), verified
 * offline in the provider adapter before anything is stored. No path variables and no wildcards, so
 * the next {@code /api/billing} endpoint cannot inherit the anonymous grant; each path is registered
 * separately in all four gate places (each enforced by a test):
 *
 * <ul>
 *   <li>{@code @EndpointPolicy(ANONYMOUS)} here, per handler — the handler's claim;</li>
 *   <li>{@code SecurityConfig}: one exact {@code permitAll} matcher per path — the grant;</li>
 *   <li>{@code ArchitectureRules.INTENTIONALLY_ANONYMOUS} — the Rule 5 registration, per handler;</li>
 *   <li>{@code zero.ratelimit.paths} — anonymous + {@code @RequestBody} means throttled
 *       ({@code SecurityPathBindingIT.everyAnonymousBodyHandlerIsThrottled} derives that
 *       obligation, it is not optional).</li>
 * </ul>
 *
 * <p>The body is taken as a RAW {@code String}: verification runs over the exact bytes the provider
 * signed (Stripe's HMAC over the JSON body; PayTR's HMAC over fields of the form body), and any DTO
 * binding before verification would both break the proof and process unauthenticated input.
 *
 * <p><b>Responses are the provider's, not ours.</b> 200 for processed, ignored AND duplicate
 * deliveries (a duplicate answered 4xx is the measured source bug that caused infinite Stripe
 * retries) — with the BODY the provider requires: Stripe reads the status code only, so its ack is
 * bodyless; PayTR settles the money ONLY on the literal plain-text body {@code OK}, so its ack is
 * exactly that, unwrapped ({@link #ack}). 400 only for a failed verification — for PayTR
 * deliberately NOT {@code OK}: confirming a notification that did not verify would be confirming
 * money nobody proved was paid. 404 when the named provider is not enabled (the surface does not
 * exist — see {@code BillingWebhookService#requireProvider}); 500 when processing fails after
 * verification, in which case the whole transaction — dedup row included — rolled back and the
 * provider's retry is safe.
 */
@RestController
@RequestMapping("/api/billing")
@RequiredArgsConstructor
public class BillingWebhookController {

    private final BillingWebhookService webhookService;

    @PostMapping("/webhook/stripe")
    @EndpointPolicy(Exposure.ANONYMOUS)
    public ResponseEntity<String> stripeWebhook(
            @RequestBody String payload,
            @RequestHeader(value = "Stripe-Signature", required = false) String signatureHeader) {
        return ack(webhookService.handle(StripeBillingProvider.PROVIDER_ID, payload, signatureHeader));
    }

    /**
     * PayTR "Bildirim URL" intake: server-to-server POST, {@code application/x-www-form-urlencoded},
     * hash inside the body — hence no signature header parameter. The form body reaches this handler
     * as the raw string because the throttle filter caches the stream and nothing ahead of the
     * handler consumes the request parameters.
     */
    @PostMapping("/webhook/paytr")
    @EndpointPolicy(Exposure.ANONYMOUS)
    public ResponseEntity<String> paytrWebhook(@RequestBody String payload) {
        return ack(webhookService.handle(PayTRBillingProvider.PROVIDER_ID, payload, null));
    }

    /**
     * iyzico webhook intake (P2'-B): server-to-server POST, JSON body, proof in the
     * {@code X-IYZ-SIGNATURE-V3} header (lowercase hex HMAC-SHA256 — verified offline in
     * {@code IyzicoBillingProvider} before anything is stored). The ack is a bodyless 200: iyzico
     * settles delivery on the status code alone and retries up to three times (10-minute cadence)
     * until it sees one. Note this route being third makes the no-wildcard rule visible: a
     * {@code /webhook/**} matcher would have granted it silently; instead it is registered in all
     * four gate places by hand, like its two siblings.
     */
    @PostMapping("/webhook/iyzico")
    @EndpointPolicy(Exposure.ANONYMOUS)
    public ResponseEntity<String> iyzicoWebhook(
            @RequestBody String payload,
            @RequestHeader(value = "X-IYZ-SIGNATURE-V3", required = false) String signatureHeader) {
        return ack(webhookService.handle(IyzicoBillingProvider.PROVIDER_ID, payload, signatureHeader));
    }

    /**
     * The provider-driven success acknowledgement. {@code null} → deliberately bodyless 200
     * (Stripe reads the status code and nothing else, and echoing any detail back to an
     * unauthenticated caller buys nothing). Otherwise the EXACT bytes the provider requires, as
     * {@code text/plain} — for PayTR that is the two characters {@code OK}, and anything else
     * (a JSON wrapper, a ProblemDetail, even a trailing newline) is read by PayTR as a FAILED
     * notification: the buyer is charged but the money is never settled to the merchant. The exact
     * body is pinned by {@code PayTRWebhookIT}; the charset is fixed so no content negotiation can
     * reshape it.
     */
    private static ResponseEntity<String> ack(String ackBody) {
        if (ackBody == null) {
            return ResponseEntity.ok().build();
        }
        return ResponseEntity.ok()
                .contentType(new MediaType(MediaType.TEXT_PLAIN, StandardCharsets.UTF_8))
                .body(ackBody);
    }
}

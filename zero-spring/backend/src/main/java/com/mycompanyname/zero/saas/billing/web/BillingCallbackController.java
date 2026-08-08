package com.mycompanyname.zero.saas.billing.web;

import com.mycompanyname.zero.saas.billing.BillingConfirmationService;
import com.mycompanyname.zero.saas.billing.BillingProvider;
import com.mycompanyname.zero.saas.billing.BillingProviderRegistry;
import com.mycompanyname.zero.saas.billing.IyzicoBillingProvider;
import com.mycompanyname.zero.saas.billing.credentials.BillingProviderAvailability;
import com.mycompanyname.zero.shared.domain.DomainException;
import com.mycompanyname.zero.shared.web.EndpointPolicy;
import com.mycompanyname.zero.shared.web.EndpointPolicy.Exposure;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The iyzico browser-callback (P2'-B) — the {@code callbackUrl} of the Checkout Form flow, hit by
 * the BUYER'S BROWSER with the single parameter {@code token}. Anonymous by necessity: the browser
 * arrives from the iyzico payment page holding no credential of ours, and the request carries no
 * signature of any kind — which is exactly why it is a TRIGGER and nothing more.
 *
 * <p><b>Never authoritative, by construction.</b> The token is used solely to start the
 * retrieve-confirm path ({@link BillingConfirmationService}): an unknown token answers 200 without
 * even a provider call (so this anonymous surface cannot be driven into outbound API traffic), and
 * a known token activates ONLY if iyzico's own retrieve confirms collection — the same call the
 * webhook funnel and the reconciliation job make. A forged or replayed callback can therefore at
 * worst trigger a confirmation that was due anyway ({@code IyzicoWebhookIT} pins this: callback
 * with a non-confirming retrieve activates nothing).
 *
 * <p><b>GET and POST both, deliberately.</b> docs.iyzico.com documents the parameter but not the
 * HTTP method of the callback (recorded UNKNOWN, risk register): POST form-encoded is accepted as
 * the documented-adjacent shape and GET is tolerated. One handler method mapped to both keeps the
 * gate registration a single entry ({@code BillingCallbackController#iyzicoCallback}).
 *
 * <p><b>Responses tell the anonymous caller nothing.</b> 200 with no body whatever the outcome —
 * a payment-status oracle keyed by guessable tokens is not a surface this endpoint may become. The
 * buyer-facing result screen is the SPA's job (P2'-C) via its authenticated status reads. 404 only
 * when iyzico is disabled: the surface does not exist, same shape as the webhooks. Exact path, no
 * wildcard; registered in all four gate places like its webhook siblings (throttled via
 * {@code zero.ratelimit.paths} even though it takes no {@code @RequestBody} — the derived
 * obligation does not demand it, but an anonymous endpoint that reaches the database earns it).
 */
@RestController
@RequestMapping("/api/billing")
@RequiredArgsConstructor
@Slf4j
public class BillingCallbackController {

    private static final String CALLBACK_ACTOR = IyzicoBillingProvider.PROVIDER_ID + "-callback";

    private final BillingProviderRegistry providerRegistry;
    private final BillingProviderAvailability availability;
    private final BillingConfirmationService confirmationService;

    @RequestMapping(path = "/callback/iyzico", method = {RequestMethod.GET, RequestMethod.POST})
    @EndpointPolicy(Exposure.ANONYMOUS)
    public ResponseEntity<Void> iyzicoCallback(
            @RequestParam(value = "token", required = false) String token) {
        BillingProvider provider = providerRegistry.find(IyzicoBillingProvider.PROVIDER_ID)
                // Same decision and reasoning as BillingWebhookService#requireProvider: when
                // neither the environment nor a stored credential set configures iyzico this
                // surface does not exist, and 404 discloses nothing about whether billing COULD
                // be enabled here (surfaceExists, not checkoutEnabled: a disabled provider's
                // in-flight payments still finish through this trigger — ADR-0020).
                .filter(p -> availability.surfaceExists(p.id()))
                .orElseThrow(() -> DomainException.notFound(
                        "Billing is not enabled on this installation"));
        if (token == null || token.isBlank()) {
            // A trigger about nothing. 200, not 400: the caller is a buyer's browser mid-redirect,
            // and there is nothing it could do differently with an error.
            return ResponseEntity.ok().build();
        }
        try {
            BillingConfirmationService.Outcome outcome =
                    confirmationService.confirmBySessionQuery(provider, token, CALLBACK_ACTOR);
            // Outcome values are provider-safe constants; the token is never logged or echoed.
            log.info("iyzico callback trigger handled: {}", outcome);
        } catch (RuntimeException ex) {
            // Stack-review Finding 4. A provider transport failure mid-confirmation must NOT
            // surface here as a 500: the webhook's 500 is a deliberate rollback-and-retry
            // contract, but a browser callback has no retry semantics — the buyer would see an
            // error page for a payment that is in fact fine, and an anonymous caller who can
            // provoke the failure (a known token during a provider outage) would be writing
            // ERROR-level stack traces into the log at will (the ClientErrorLogBudget rule). The
            // trigger failing costs nothing but latency: the webhook and the reconciliation job
            // run the SAME confirmation and remain the nets. WARN with the cause, answer the
            // same disclosing-nothing 200 as every other outcome.
            log.warn("iyzico callback trigger failed mid-confirmation; the webhook and the "
                    + "reconciliation job remain the nets", ex);
        }
        return ResponseEntity.ok().build();
    }
}

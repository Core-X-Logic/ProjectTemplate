package com.mycompanyname.zero.saas.billing.web;

import com.mycompanyname.zero.saas.SaasPermissions;
import com.mycompanyname.zero.saas.billing.BillingCheckoutService;
import com.mycompanyname.zero.saas.billing.web.dto.CheckoutSessionDto;
import com.mycompanyname.zero.saas.billing.web.dto.StartCheckoutRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Checkout initiation. Guarded by the EXISTING {@code subscriptions.manage} permission — selling a
 * package is subscription management, and every SaaS write is {@code Side.HOST} ("Tenant kendi
 * limitini yükseltemez"): the host operates checkout on the tenant's behalf, exactly like
 * {@code PUT /api/subscriptions/{tenantId}/edition}. No new permission is minted. A tenant-facing
 * self-checkout is a deliberate NON-goal of this slice (frontend Can/route-guard recorded as out of
 * scope with it).
 */
@RestController
@RequestMapping("/api/billing")
@RequiredArgsConstructor
public class BillingCheckoutController {

    private final BillingCheckoutService checkoutService;

    @PostMapping("/checkout")
    @PreAuthorize("hasAuthority('" + SaasPermissions.SUBSCRIPTIONS_MANAGE + "')")
    public CheckoutSessionDto checkout(@Valid @RequestBody StartCheckoutRequest request,
                                       @AuthenticationPrincipal Jwt jwt) {
        return checkoutService.startCheckout(request, actor(jwt));
    }

    private String actor(Jwt jwt) {
        String username = jwt == null ? null : jwt.getClaimAsString("username");
        return username == null || username.isBlank() ? "system" : username;
    }
}

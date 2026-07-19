package com.mycompanyname.zero.saas.subscription.web;

import com.mycompanyname.zero.saas.SaasPermissions;
import com.mycompanyname.zero.saas.subscription.SubscriptionService;
import com.mycompanyname.zero.saas.subscription.web.dto.AssignEditionRequest;
import com.mycompanyname.zero.saas.subscription.web.dto.ChangeEditionRequest;
import com.mycompanyname.zero.saas.subscription.web.dto.EditionChangeDto;
import com.mycompanyname.zero.saas.subscription.web.dto.SubscriptionDetailDto;
import com.mycompanyname.zero.saas.subscription.web.dto.SubscriptionDto;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Subscription API. All administrative routes require a {@code Side.HOST} permission; the single
 * tenant-facing route is {@code GET /me}, which needs no permission but can only ever return the
 * caller's <em>own</em> subscription because the tenant is taken from the authenticated JWT — never
 * from a path variable the caller controls.
 */
@RestController
@RequestMapping("/api/subscriptions")
@RequiredArgsConstructor
public class SubscriptionController {

    private final SubscriptionService subscriptionService;

    @GetMapping
    @PreAuthorize("hasAuthority('" + SaasPermissions.SUBSCRIPTIONS_READ + "')")
    public Page<SubscriptionDto> list(Pageable pageable) {
        return subscriptionService.list(pageable);
    }

    /**
     * The caller's own subscription. Mapped before {@code /{tenantId}} by path-pattern specificity
     * (a literal segment always beats a variable), so "me" is never parsed as a tenant id.
     *
     * <p>Authentication only, no permission: the tenant comes from the JWT {@code tenant} claim,
     * never from a path variable, so the caller cannot name a tenant it does not belong to. Every
     * tenant user needs to see its own plan, so a permission would have to be granted to all of
     * them and would decide nothing.
     */
    @GetMapping("/me")
    @PreAuthorize("isAuthenticated()")
    public SubscriptionDto me(@AuthenticationPrincipal Jwt jwt) {
        return subscriptionService.getOwnSubscription(tenantId(jwt));
    }

    @GetMapping("/{tenantId}")
    @PreAuthorize("hasAuthority('" + SaasPermissions.SUBSCRIPTIONS_READ + "')")
    public SubscriptionDetailDto getByTenantId(@PathVariable Long tenantId) {
        return subscriptionService.getByTenantId(tenantId);
    }

    @PutMapping("/{tenantId}/edition")
    @PreAuthorize("hasAuthority('" + SaasPermissions.SUBSCRIPTIONS_MANAGE + "')")
    public SubscriptionDetailDto assignEdition(@PathVariable Long tenantId,
                                               @Valid @RequestBody AssignEditionRequest request,
                                               @AuthenticationPrincipal Jwt jwt) {
        return subscriptionService.assignEdition(tenantId, request, actor(jwt));
    }

    /**
     * Upgrade/downgrade (S13). Host-only like every other write: a tenant requesting an upgrade goes
     * through this route on its behalf, and the tenant can never move itself onto a package it has
     * not paid for. The pro-rated amount is returned; collecting it is up to the application.
     * See ARCHITECTURE-RULES.md — "Tenant kendi limitini yükseltemez".
     */
    @PostMapping("/{tenantId}/change-edition")
    @PreAuthorize("hasAuthority('" + SaasPermissions.SUBSCRIPTIONS_MANAGE + "')")
    public EditionChangeDto changeEdition(@PathVariable Long tenantId,
                                          @Valid @RequestBody ChangeEditionRequest request,
                                          @AuthenticationPrincipal Jwt jwt) {
        return subscriptionService.changeEdition(tenantId, request, actor(jwt));
    }

    @PostMapping("/{tenantId}/activate")
    @PreAuthorize("hasAuthority('" + SaasPermissions.SUBSCRIPTIONS_MANAGE + "')")
    public SubscriptionDetailDto activate(@PathVariable Long tenantId, @AuthenticationPrincipal Jwt jwt) {
        return subscriptionService.activate(tenantId, actor(jwt));
    }

    @PostMapping("/{tenantId}/cancel")
    @PreAuthorize("hasAuthority('" + SaasPermissions.SUBSCRIPTIONS_MANAGE + "')")
    public SubscriptionDetailDto cancel(@PathVariable Long tenantId, @AuthenticationPrincipal Jwt jwt) {
        return subscriptionService.cancel(tenantId, actor(jwt));
    }

    private Long tenantId(Jwt jwt) {
        Object tenant = jwt == null ? null : jwt.getClaim("tenant");
        return tenant == null ? null : ((Number) tenant).longValue();
    }

    private String actor(Jwt jwt) {
        String username = jwt == null ? null : jwt.getClaimAsString("username");
        return username == null || username.isBlank() ? "system" : username;
    }
}

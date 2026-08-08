package com.mycompanyname.zero.saas.billing.web;

import com.mycompanyname.zero.saas.SaasPermissions;
import com.mycompanyname.zero.saas.billing.credentials.BillingProviderAdminService;
import com.mycompanyname.zero.saas.billing.web.dto.ProviderStatusDto;
import com.mycompanyname.zero.saas.billing.web.dto.UpdateProviderCredentialsRequest;
import com.mycompanyname.zero.saas.billing.web.dto.UpdateProviderOrderRequest;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Host administration of managed billing credentials and failover order (ADR-0020). Guarded by the
 * dedicated {@code billing.credentials.manage} permission, {@code Side.HOST} like every SaaS
 * write ("Tenant kendi limitini yükseltemez" — and doubly so here: these are the INSTALLATION'S
 * merchant accounts).
 *
 * <p><b>Write-only credential surface.</b> PUT accepts values; GET answers status, field names and
 * a masked hint — never a stored or environment credential value, which is the property that kept
 * this off the generic settings endpoint (whose host GET round-trips values by design).
 */
@RestController
@RequestMapping("/api/billing/providers")
@RequiredArgsConstructor
public class BillingProviderAdminController {

    private final BillingProviderAdminService adminService;

    @GetMapping
    @PreAuthorize("hasAuthority('" + SaasPermissions.BILLING_CREDENTIALS_MANAGE + "')")
    public List<ProviderStatusDto> status() {
        return adminService.status();
    }

    @PutMapping("/{providerId}/credentials")
    @PreAuthorize("hasAuthority('" + SaasPermissions.BILLING_CREDENTIALS_MANAGE + "')")
    public ProviderStatusDto updateCredentials(
            @PathVariable String providerId,
            @Valid @RequestBody UpdateProviderCredentialsRequest request) {
        return adminService.updateCredentials(providerId, request);
    }

    @DeleteMapping("/{providerId}/credentials")
    @PreAuthorize("hasAuthority('" + SaasPermissions.BILLING_CREDENTIALS_MANAGE + "')")
    public ResponseEntity<Void> deleteCredentials(@PathVariable String providerId) {
        adminService.deleteCredentials(providerId);
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/order")
    @PreAuthorize("hasAuthority('" + SaasPermissions.BILLING_CREDENTIALS_MANAGE + "')")
    public List<ProviderStatusDto> updateOrder(@Valid @RequestBody UpdateProviderOrderRequest request) {
        return adminService.updateOrder(request);
    }
}

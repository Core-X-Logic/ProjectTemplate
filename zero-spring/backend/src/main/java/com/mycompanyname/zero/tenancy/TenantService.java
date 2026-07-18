package com.mycompanyname.zero.tenancy;

import com.mycompanyname.zero.shared.domain.DomainException;
import com.mycompanyname.zero.tenancy.web.dto.CreateTenantRequest;
import com.mycompanyname.zero.tenancy.web.dto.TenantDto;
import java.util.List;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional
public class TenantService {

    private final TenantRepository tenantRepository;
    private final ApplicationEventPublisher eventPublisher;

    public TenantDto createTenant(CreateTenantRequest request) {
        String name = request.name().toLowerCase(Locale.ROOT);
        if (tenantRepository.findByNameIgnoreCase(name).isPresent()) {
            throw DomainException.conflict("Tenant with name '" + name + "' already exists");
        }
        Tenant tenant = new Tenant();
        tenant.setName(name);
        tenant.setDisplayName(request.displayName());
        tenant.setActive(true);
        Tenant saved = tenantRepository.save(tenant);
        // IDENTITY generation means the insert (and therefore the id) is already flushed here, so a
        // synchronous listener may safely create rows referencing tenants(id) in the same transaction.
        eventPublisher.publishEvent(new TenantCreatedEvent(saved.getId(), saved.getName()));
        return toDto(saved);
    }

    public TenantDto setActive(Long id, boolean active) {
        Tenant tenant = tenantRepository.findById(id)
                .orElseThrow(() -> DomainException.notFound("Tenant not found: " + id));
        tenant.setActive(active);
        return toDto(tenant);
    }

    @Transactional(readOnly = true)
    public List<TenantDto> list() {
        return tenantRepository.findAll().stream().map(this::toDto).toList();
    }

    @Transactional(readOnly = true)
    public Tenant getByNameOrThrow(String name) {
        return tenantRepository.findByNameIgnoreCase(name)
                .orElseThrow(() -> DomainException.tenantUnknown("Unknown tenant: " + name));
    }

    private TenantDto toDto(Tenant tenant) {
        return new TenantDto(
                tenant.getId(),
                tenant.getName(),
                tenant.getDisplayName(),
                tenant.isActive(),
                tenant.getCreatedAt());
    }
}

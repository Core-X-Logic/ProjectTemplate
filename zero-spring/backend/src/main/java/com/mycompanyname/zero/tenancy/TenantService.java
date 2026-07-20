package com.mycompanyname.zero.tenancy;

import com.mycompanyname.zero.shared.domain.DomainException;
import com.mycompanyname.zero.tenancy.web.dto.CreateTenantRequest;
import com.mycompanyname.zero.tenancy.web.dto.CreateTenantResponse;
import com.mycompanyname.zero.tenancy.web.dto.TenantDto;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
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

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final String LOWER = "abcdefghijklmnopqrstuvwxyz";
    private static final String UPPER = "ABCDEFGHIJKLMNOPQRSTUVWXYZ";
    private static final String DIGITS = "0123456789";
    private static final String SPECIAL = "!@#$%^&*-_=+";
    private static final String ALL_CLASSES = LOWER + UPPER + DIGITS + SPECIAL;
    private static final int GENERATED_PASSWORD_LENGTH = 24;

    private final TenantRepository tenantRepository;
    private final ApplicationEventPublisher eventPublisher;

    /**
     * Creates the tenant AND its bootstrap admin (Issue #1: a tenant nobody can log into is not a
     * tenant, and nothing later repaired that). The admin itself is written by the identity
     * module's synchronous {@code TenantCreatedEvent} listener, inside this same transaction, so a
     * "tenant exists but has no admin" state cannot be committed.
     *
     * <p><b>Initial password.</b> Taken from {@code adminPassword} when the caller supplied one
     * (policy-checked by the identity listener); otherwise generated here — SecureRandom, 24 chars,
     * at least one lower/upper/digit/special — and returned exactly once via
     * {@link CreateTenantResponse#generatedAdminPassword()}. It is deliberately never logged and
     * never persisted in plaintext: only the hash reaches the database, so the create response is
     * the single opportunity to read it.
     */
    public CreateTenantResponse createTenant(CreateTenantRequest request) {
        String name = request.name().toLowerCase(Locale.ROOT);
        if (tenantRepository.findByNameIgnoreCase(name).isPresent()) {
            throw DomainException.conflict("Tenant with name '" + name + "' already exists");
        }
        boolean passwordGenerated = request.adminPassword() == null || request.adminPassword().isBlank();
        String adminPassword = passwordGenerated ? generateAdminPassword() : request.adminPassword();

        Tenant tenant = new Tenant();
        tenant.setName(name);
        tenant.setDisplayName(request.displayName());
        tenant.setActive(true);
        Tenant saved = tenantRepository.save(tenant);
        // IDENTITY generation means the insert (and therefore the id) is already flushed here, so a
        // synchronous listener may safely create rows referencing tenants(id) in the same transaction.
        eventPublisher.publishEvent(new TenantCreatedEvent(saved.getId(), saved.getName(),
                request.adminEmail(), adminPassword, passwordGenerated));
        return new CreateTenantResponse(
                saved.getId(),
                saved.getName(),
                saved.getDisplayName(),
                saved.isActive(),
                saved.getCreatedAt(),
                passwordGenerated ? adminPassword : null);
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

    /**
     * A strong random initial credential: 24 chars with every character class represented, so it
     * satisfies the default password policy (length, digit, upper, lower) with a wide margin.
     * Shuffled with the same {@link SecureRandom}, so the guaranteed-class characters do not sit at
     * predictable positions.
     */
    private static String generateAdminPassword() {
        List<Character> chars = new ArrayList<>(GENERATED_PASSWORD_LENGTH);
        chars.add(pick(LOWER));
        chars.add(pick(UPPER));
        chars.add(pick(DIGITS));
        chars.add(pick(SPECIAL));
        while (chars.size() < GENERATED_PASSWORD_LENGTH) {
            chars.add(pick(ALL_CLASSES));
        }
        Collections.shuffle(chars, SECURE_RANDOM);
        StringBuilder password = new StringBuilder(GENERATED_PASSWORD_LENGTH);
        chars.forEach(password::append);
        return password.toString();
    }

    private static char pick(String alphabet) {
        return alphabet.charAt(SECURE_RANDOM.nextInt(alphabet.length()));
    }
}

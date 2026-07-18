package com.mycompanyname.zero.saas.edition;

import com.mycompanyname.zero.saas.edition.web.dto.CreateEditionRequest;
import com.mycompanyname.zero.saas.edition.web.dto.EditionDetailDto;
import com.mycompanyname.zero.saas.edition.web.dto.EditionDto;
import com.mycompanyname.zero.saas.edition.web.dto.UpdateEditionRequest;
import com.mycompanyname.zero.saas.feature.FeatureDefinition;
import com.mycompanyname.zero.saas.feature.FeatureDefinitions;
import com.mycompanyname.zero.saas.feature.web.dto.FeatureValueDto;
import com.mycompanyname.zero.saas.subscription.SubscriptionRepository;
import com.mycompanyname.zero.shared.domain.DomainException;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Edition catalogue management. Every mutation here is host-only (see the controller's
 * {@code @PreAuthorize}); a tenant can never change what it is sold (F5-R3).
 *
 * <p>Enforces the four catalogue invariants of CONTRACT-phase5 A.1:
 * an edition that is still sold — or that another edition downgrades to — cannot be deleted (409);
 * a downgrade target must be free (400); and a free edition cannot offer a trial (400).
 */
@Service
@RequiredArgsConstructor
@Transactional
public class EditionService {

    private final EditionRepository editionRepository;
    private final EditionFeatureRepository editionFeatureRepository;
    private final SubscriptionRepository subscriptionRepository;

    @Transactional(readOnly = true)
    public Page<EditionDto> list(Pageable pageable) {
        return editionRepository.findAllByOrderBySortOrderAscIdAsc(pageable).map(EditionService::toDto);
    }

    @Transactional(readOnly = true)
    public EditionDetailDto getById(Long id) {
        return toDetailDto(requireEdition(id));
    }

    public EditionDetailDto create(CreateEditionRequest request) {
        String name = request.name().trim();
        if (editionRepository.findByNameIgnoreCase(name).isPresent()) {
            throw DomainException.conflict("Edition already exists: " + name);
        }
        Edition edition = new Edition();
        edition.setName(name);
        apply(edition,
                request.displayName(), request.description(),
                request.monthlyPrice(), request.annualPrice(), request.currency(),
                request.trialDayCount(), request.graceDayCount(),
                request.expiringEditionId(), request.active(), request.sortOrder());
        return toDetailDto(editionRepository.save(edition));
    }

    public EditionDetailDto update(Long id, UpdateEditionRequest request) {
        Edition edition = requireEdition(id);
        apply(edition,
                request.displayName(), request.description(),
                request.monthlyPrice(), request.annualPrice(), request.currency(),
                request.trialDayCount(), request.graceDayCount(),
                request.expiringEditionId(), request.active(), request.sortOrder());
        return toDetailDto(editionRepository.save(edition));
    }

    public void delete(Long id) {
        Edition edition = requireEdition(id);

        long subscribers = subscriptionRepository.countByEditionId(id);
        if (subscribers > 0) {
            throw DomainException.conflict("Edition '" + edition.getName() + "' is assigned to "
                    + subscribers + " tenant(s) and cannot be deleted");
        }
        // K14: a free edition used as another edition's downgrade target must survive, otherwise the
        // expiring subscription would have nowhere to land.
        long dependents = editionRepository.countByExpiringEditionId(id);
        if (dependents > 0) {
            throw DomainException.conflict("Edition '" + edition.getName() + "' is the expiring edition of "
                    + dependents + " other edition(s) and cannot be deleted");
        }
        editionRepository.delete(edition);
    }

    /**
     * Batch write of the edition's feature values. Entries with a {@code null}/blank value remove the
     * edition-level override so resolution falls through to the definition default; unknown feature
     * names and type-incompatible values are rejected with VALIDATION.
     */
    public EditionDetailDto setFeatures(Long id, List<FeatureValueDto> values) {
        Edition edition = requireEdition(id);
        if (values == null) {
            return toDetailDto(edition);
        }
        for (FeatureValueDto value : values) {
            if (value == null || value.name() == null) {
                continue;
            }
            String normalized = FeatureDefinitions.normalize(value.name(), value.value());
            Optional<EditionFeature> existing =
                    editionFeatureRepository.findByEditionIdAndFeatureName(edition.getId(), value.name());
            if (normalized == null) {
                existing.ifPresent(editionFeatureRepository::delete);
                continue;
            }
            EditionFeature feature = existing.orElseGet(EditionFeature::new);
            feature.setEditionId(edition.getId());
            feature.setFeatureName(value.name());
            feature.setValue(normalized);
            editionFeatureRepository.save(feature);
        }
        return toDetailDto(edition);
    }

    @Transactional(readOnly = true)
    public Edition requireEdition(Long id) {
        return editionRepository.findById(id)
                .orElseThrow(() -> DomainException.notFound("Edition not found: " + id));
    }

    // --- internals ---

    private void apply(Edition edition, String displayName, String description,
                       BigDecimal monthlyPrice, BigDecimal annualPrice, String currency,
                       Integer trialDayCount, Integer graceDayCount,
                       Long expiringEditionId, Boolean active, Integer sortOrder) {
        edition.setDisplayName(displayName.trim());
        edition.setDescription(description);
        edition.setMonthlyPrice(monthlyPrice);
        edition.setAnnualPrice(annualPrice);
        edition.setCurrency(currency == null || currency.isBlank()
                ? null
                : currency.trim().toUpperCase(Locale.ROOT));
        edition.setTrialDayCount(trialDayCount == null ? 0 : trialDayCount);
        edition.setGraceDayCount(graceDayCount == null ? 0 : graceDayCount);
        edition.setActive(active == null || active);
        edition.setSortOrder(sortOrder == null ? 0 : sortOrder);
        edition.setExpiringEditionId(expiringEditionId);

        validatePricing(edition);
        validateExpiringEdition(edition);
    }

    private void validatePricing(Edition edition) {
        if (!edition.isFree() && (edition.getCurrency() == null || edition.getCurrency().isBlank())) {
            throw DomainException.validation("A priced edition requires a currency");
        }
        // Rule 3: a free edition has nothing to convert into, so a trial is meaningless there.
        if (edition.isFree() && edition.getTrialDayCount() > 0) {
            throw DomainException.validation("A free edition cannot offer a trial period");
        }
    }

    private void validateExpiringEdition(Edition edition) {
        Long targetId = edition.getExpiringEditionId();
        if (targetId == null) {
            return;
        }
        if (Objects.equals(targetId, edition.getId())) {
            throw DomainException.validation("An edition cannot expire into itself");
        }
        Edition target = editionRepository.findById(targetId)
                .orElseThrow(() -> DomainException.validation("Expiring edition not found: " + targetId));
        // Rule 2: downgrading must never land the tenant on something billable.
        if (!target.isFree()) {
            throw DomainException.validation(
                    "The expiring edition must be free, but '" + target.getName() + "' is priced");
        }
    }

    private EditionDetailDto toDetailDto(Edition edition) {
        Map<String, String> stored = new LinkedHashMap<>();
        for (EditionFeature feature : editionFeatureRepository.findByEditionId(edition.getId())) {
            stored.put(feature.getFeatureName(), feature.getValue());
        }
        // Every known feature is listed so the editor can render the full form; null = not overridden.
        List<FeatureValueDto> features = FeatureDefinitions.ALL.stream()
                .map(FeatureDefinition::name)
                .map(name -> new FeatureValueDto(name, stored.get(name)))
                .toList();
        return new EditionDetailDto(toDto(edition), features);
    }

    static EditionDto toDto(Edition edition) {
        return new EditionDto(
                edition.getId(),
                edition.getName(),
                edition.getDisplayName(),
                edition.getDescription(),
                edition.getMonthlyPrice(),
                edition.getAnnualPrice(),
                edition.getCurrency(),
                edition.getTrialDayCount(),
                edition.getGraceDayCount(),
                edition.getExpiringEditionId(),
                edition.isActive(),
                edition.getSortOrder(),
                edition.isFree());
    }
}

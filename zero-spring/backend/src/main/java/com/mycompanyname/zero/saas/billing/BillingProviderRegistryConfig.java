package com.mycompanyname.zero.saas.billing;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Builds the {@link BillingProviderRegistry} from every {@link BillingProvider} bean the context
 * registered (P2'-A).
 *
 * <p><b>Why this reads the bean factory instead of injecting {@code List<BillingProvider>}.</b> A
 * plain list cannot see {@code @Primary}, and the billing integration tests depend on it: they run
 * with a provider ENABLED (so the real bean exists) and {@code @Import} a {@code @Primary} recording
 * double that deliberately REPLACES it under the same id — the exact semantics the old
 * {@code ObjectProvider<BillingProvider>} wiring gave them. A list would hand this registry both
 * beans and turn every such test context into a duplicate-id boot failure. So duplicates are first
 * collapsed the way Spring itself would resolve them — a single {@code @Primary} definition wins its
 * id — and only what survives reaches the registry constructor, where a REAL conflict (no primary,
 * or two primaries, claiming one id) still refuses boot.
 */
@Configuration
public class BillingProviderRegistryConfig {

    @Bean
    public BillingProviderRegistry billingProviderRegistry(ConfigurableListableBeanFactory beanFactory) {
        Map<String, BillingProvider> selectedById = new LinkedHashMap<>();
        Map<String, Boolean> selectedIsPrimary = new LinkedHashMap<>();

        for (Map.Entry<String, BillingProvider> entry
                : beanFactory.getBeansOfType(BillingProvider.class).entrySet()) {
            BillingProvider candidate = entry.getValue();
            boolean primary = isPrimary(beanFactory, entry.getKey());
            String id = candidate.id();

            BillingProvider current = selectedById.get(id);
            if (current == null) {
                selectedById.put(id, candidate);
                selectedIsPrimary.put(id, primary);
                continue;
            }
            boolean currentPrimary = selectedIsPrimary.get(id);
            if (primary == currentPrimary) {
                // No primary, or two primaries: nothing chose between them. The registry
                // constructor states the refusal (and is what the duplicate-id unit test drives).
                return new BillingProviderRegistry(List.of(current, candidate));
            }
            if (primary) {
                selectedById.put(id, candidate);
                selectedIsPrimary.put(id, true);
            }
        }
        return new BillingProviderRegistry(selectedById.values());
    }

    /**
     * Reads primariness off the bean DEFINITION, which is where {@code @Primary} lives. A bean
     * without a local definition (parent factory, programmatic singleton) is treated as
     * non-primary — the safe reading: it can still win an id it has to itself, and a duplicate it
     * cannot win fails loudly instead of silently outranking something.
     */
    private static boolean isPrimary(ConfigurableListableBeanFactory beanFactory, String beanName) {
        try {
            return beanFactory.containsBeanDefinition(beanName)
                    && beanFactory.getBeanDefinition(beanName).isPrimary();
        } catch (NoSuchBeanDefinitionException ex) {
            return false;
        }
    }
}

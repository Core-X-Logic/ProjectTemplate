package com.mycompanyname.zero.audit.history;

import java.util.List;
import java.util.Map;
import org.hibernate.integrator.spi.Integrator;
import org.hibernate.jpa.boot.spi.IntegratorProvider;
import org.springframework.boot.autoconfigure.orm.jpa.HibernatePropertiesCustomizer;
import org.springframework.stereotype.Component;

/**
 * Wires the entity-history {@link Integrator} into Hibernate through Spring Boot's JPA property
 * customization hook. The listener is injected as a Spring bean so it can resolve its collaborators
 * (via {@code ObjectProvider}) lazily, avoiding a bootstrap cycle with the JPA repositories.
 */
@Component
public class AuditHibernateConfigurer implements HibernatePropertiesCustomizer {

    private final EntityChangeListener listener;

    public AuditHibernateConfigurer(EntityChangeListener listener) {
        this.listener = listener;
    }

    @Override
    public void customize(Map<String, Object> hibernateProperties) {
        hibernateProperties.put("hibernate.integrator_provider",
                (IntegratorProvider) () -> List.<Integrator>of(new EntityChangeIntegrator(listener)));
    }
}

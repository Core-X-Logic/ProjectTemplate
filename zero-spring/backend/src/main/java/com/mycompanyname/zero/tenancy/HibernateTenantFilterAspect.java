package com.mycompanyname.zero.tenancy;

import com.mycompanyname.zero.shared.tenant.TenantContext;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.hibernate.Session;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Second line of defense for tenant isolation (explicit tenant-scoped repository queries are the
 * primary defense): enables the Hibernate tenant/host filter on the current transactional Session
 * before every @Service method body runs.
 *
 * <p>Ordering is what makes this reliable: the transaction advisor is pinned at
 * {@code LOWEST_PRECEDENCE - 100} (see {@code TransactionOrderConfig}) and this aspect at
 * {@code LOWEST_PRECEDENCE - 10}, so the aspect is guaranteed to execute INSIDE an already-open
 * transaction. The {@code @PersistenceContext} EntityManager proxy then unwraps to the actual
 * transaction-bound Session, so the enabled filter applies to the queries the service issues.
 */
@Aspect
@Component
@Order(Ordered.LOWEST_PRECEDENCE - 10)
public class HibernateTenantFilterAspect {

    public static final String TENANT_FILTER = "tenantFilter";
    public static final String HOST_FILTER = "hostFilter";

    @PersistenceContext
    private EntityManager entityManager;

    @Around("within(@org.springframework.stereotype.Service *)")
    public Object applyTenantFilter(ProceedingJoinPoint joinPoint) throws Throwable {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            Session session = entityManager.unwrap(Session.class);
            Long tenantId = TenantContext.getTenantId();
            if (tenantId != null) {
                session.enableFilter(TENANT_FILTER).setParameter("tenantId", tenantId);
            } else {
                session.enableFilter(HOST_FILTER);
            }
        }
        return joinPoint.proceed();
    }
}

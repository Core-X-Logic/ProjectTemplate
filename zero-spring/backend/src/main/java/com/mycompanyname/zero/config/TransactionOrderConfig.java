package com.mycompanyname.zero.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.transaction.annotation.EnableTransactionManagement;

/**
 * Pins the transaction advisor's AOP order explicitly. Without this, the transaction advisor and
 * {@code HibernateTenantFilterAspect} both sit at {@code Ordered.LOWEST_PRECEDENCE} and their
 * relative order is undefined, which made the tenant filter enablement unreliable (the aspect could
 * run OUTSIDE the transaction and be skipped).
 *
 * <p>With the advisor at {@code LOWEST_PRECEDENCE - 100} (outer) and the aspect at
 * {@code LOWEST_PRECEDENCE - 10} (inner), the aspect is guaranteed to run inside an active
 * transaction and enable the Hibernate tenant filter on the transaction-bound Session.
 */
@Configuration(proxyBeanMethods = false)
@EnableTransactionManagement(order = Ordered.LOWEST_PRECEDENCE - 100)
public class TransactionOrderConfig {
}

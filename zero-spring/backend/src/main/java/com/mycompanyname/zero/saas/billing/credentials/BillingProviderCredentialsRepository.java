package com.mycompanyname.zero.saas.billing.credentials;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BillingProviderCredentialsRepository
        extends JpaRepository<BillingProviderCredentials, Long> {

    Optional<BillingProviderCredentials> findByProvider(String provider);
}

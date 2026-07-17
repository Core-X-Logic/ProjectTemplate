package com.mycompanyname.zero.identity.auth;

import com.mycompanyname.zero.config.JwtProperties;
import com.mycompanyname.zero.tenancy.AuthenticatedTenantFilter;
import com.mycompanyname.zero.tenancy.TenantRepository;
import com.mycompanyname.zero.tenancy.TenantResolverFilter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.DelegatingPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;

import javax.crypto.spec.SecretKeySpec;
import java.util.HashMap;
import java.util.Map;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http,
                                                   TenantResolverFilter tenantResolverFilter,
                                                   AuthenticatedTenantFilter authenticatedTenantFilter,
                                                   JwtDecoder jwtDecoder,
                                                   JwtAuthenticationConverter jwtAuthenticationConverter) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/auth/login", "/api/auth/refresh").permitAll()
                        .requestMatchers("/api/account/forgot-password", "/api/account/reset-password",
                                "/api/account/confirm-email").permitAll()
                        .requestMatchers("/api/localization/**").permitAll()
                        .requestMatchers("/actuator/health/**", "/v3/api-docs/**", "/swagger-ui/**").permitAll()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt -> jwt
                        .decoder(jwtDecoder)
                        .jwtAuthenticationConverter(jwtAuthenticationConverter)))
                // stage 1: header -> TenantContext (needed by permitAll login/refresh)
                .addFilterBefore(tenantResolverFilter, BearerTokenAuthenticationFilter.class)
                // stage 2: JWT 'tenant' claim is authoritative for authenticated requests
                .addFilterAfter(authenticatedTenantFilter, BearerTokenAuthenticationFilter.class);
        return http.build();
    }

    @Bean
    public TenantResolverFilter tenantResolverFilter(TenantRepository tenantRepository,
                                                     @Value("${zero.multitenancy.header:X-Tenant}") String headerName) {
        return new TenantResolverFilter(tenantRepository, headerName);
    }

    @Bean
    public FilterRegistrationBean<TenantResolverFilter> tenantResolverFilterRegistration(TenantResolverFilter filter) {
        FilterRegistrationBean<TenantResolverFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }

    @Bean
    public AuthenticatedTenantFilter authenticatedTenantFilter(
            @Value("${zero.multitenancy.header:X-Tenant}") String headerName) {
        return new AuthenticatedTenantFilter(headerName);
    }

    @Bean
    public FilterRegistrationBean<AuthenticatedTenantFilter> authenticatedTenantFilterRegistration(
            AuthenticatedTenantFilter filter) {
        FilterRegistrationBean<AuthenticatedTenantFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }

    @Bean
    public JwtDecoder jwtDecoder(JwtProperties properties) {
        SecretKeySpec secretKey = JwtService.buildSecretKey(properties.getSecret());
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(secretKey)
                .macAlgorithm(MacAlgorithm.HS512)
                .build();
        // default validators (timestamps) + mandatory issuer check
        decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(properties.getIssuer()));
        return decoder;
    }

    @Bean
    public JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtGrantedAuthoritiesConverter authoritiesConverter = new JwtGrantedAuthoritiesConverter();
        authoritiesConverter.setAuthoritiesClaimName("authorities");
        authoritiesConverter.setAuthorityPrefix("");
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(authoritiesConverter);
        return converter;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        String idForEncode = "bcrypt";
        Map<String, PasswordEncoder> encoders = new HashMap<>();
        encoders.put(idForEncode, new BCryptPasswordEncoder(12));
        return new DelegatingPasswordEncoder(idForEncode, encoders);
    }
}

package com.mycompanyname.zero.identity.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mycompanyname.zero.config.CorsProperties;
import com.mycompanyname.zero.config.JwtProperties;
import com.mycompanyname.zero.config.RateLimitFilter;
import com.mycompanyname.zero.config.RateLimitProperties;
import com.mycompanyname.zero.config.RequestLimitProperties;
import com.mycompanyname.zero.config.RequestSizeLimitFilter;
import com.mycompanyname.zero.identity.domain.AppPermissions;
import com.mycompanyname.zero.tenancy.AuthenticatedTenantFilter;
import com.mycompanyname.zero.tenancy.TenantAccessCheck;
import com.mycompanyname.zero.tenancy.TenantRepository;
import com.mycompanyname.zero.tenancy.TenantResolverFilter;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.DelegatingPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.security.web.header.writers.StaticHeadersWriter;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerAdapter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

import javax.crypto.spec.SecretKeySpec;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    /** One year, the minimum the browser preload lists accept. */
    private static final long HSTS_MAX_AGE_SECONDS = 31_536_000L;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http,
                                                   TenantResolverFilter tenantResolverFilter,
                                                   AuthenticatedTenantFilter authenticatedTenantFilter,
                                                   RateLimitFilter rateLimitFilter,
                                                   RequestSizeLimitFilter requestSizeLimitFilter,
                                                   JwtDecoder jwtDecoder,
                                                   JwtAuthenticationConverter jwtAuthenticationConverter,
                                                   Environment environment,
                                                   @Value("${zero.security.content-security-policy}") String contentSecurityPolicy,
                                                   @Value("${zero.security.permissions-policy}") String permissionsPolicy)
            throws Exception {
        // C5. The springdoc permit is granted by naming the environments that want it, never by
        // failing to name the one that does not. `prod` is still consulted so that an explicit
        // dev+prod or test+prod combination resolves closed rather than open.
        boolean production = environment.acceptsProfiles(Profiles.of("prod"));
        boolean development = !production && environment.acceptsProfiles(Profiles.of("dev", "test"));
        http
                .csrf(csrf -> csrf.disable())
                .cors(Customizer.withDefaults())
                // PROD-R4 / PROD-R5. Spring's defaults stop at nosniff + frame-options, and HSTS only
                // reaches the wire because server.forward-headers-strategy=framework makes
                // request.isSecure() true behind a TLS-terminating proxy.
                .headers(headers -> headers
                        .httpStrictTransportSecurity(hsts -> hsts
                                .includeSubDomains(true)
                                .preload(true)
                                .maxAgeInSeconds(HSTS_MAX_AGE_SECONDS))
                        .contentSecurityPolicy(csp -> csp.policyDirectives(contentSecurityPolicy))
                        .referrerPolicy(referrer -> referrer
                                .policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.STRICT_ORIGIN_WHEN_CROSS_ORIGIN))
                        .frameOptions(frame -> frame.deny())
                        // No dedicated DSL method exists for Permissions-Policy in Spring Security 6.
                        .addHeaderWriter(new StaticHeadersWriter("Permissions-Policy", permissionsPolicy)))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> {
                    auth
                            .requestMatchers("/api/auth/login", "/api/auth/refresh").permitAll()
                            .requestMatchers("/api/account/forgot-password", "/api/account/reset-password",
                                    "/api/account/confirm-email").permitAll()
                            .requestMatchers("/api/localization/**").permitAll()
                            .requestMatchers("/actuator/health/**").permitAll()
                            // PROD-R17. Everything else under /actuator was reachable by *any*
                            // authenticated caller, because it fell through to anyRequest().authenticated()
                            // and nothing narrower ever claimed it. Confirmed live: anonymous 401, but a
                            // tenant user holding zero permissions read /actuator/prometheus and got 200 —
                            // heap and JVM state, every route name, request counters, and a derivable
                            // tenant count. management.endpoints.web.exposure.include lists
                            // health,info,metrics,prometheus in the *base* config with no prod override,
                            // so this was production behaviour, not a dev-only artefact.
                            //
                            // No new permission was minted for it. settings.host.manage is already
                            // host-only, is already the "operate the installation" authority, and no
                            // tenant role can hold it — which is exactly the boundary being drawn.
                            // Scraping is an operational concern, not an authorization hole: see
                            // RELEASE-RUNBOOK 1.3-J for the two supported answers (host service account,
                            // or a management port bound off the public interface).
                            .requestMatchers("/actuator/**").hasAuthority(AppPermissions.SETTINGS_HOST);
                    // B6. The API description was anonymously readable on every profile: GET
                    // /v3/api-docs answered 200 and enumerated every route, parameter and DTO before
                    // a single credential was presented. In prod that is reconnaissance with no
                    // offsetting benefit — the strict CSP there already prevents Swagger UI from
                    // running, so no operator was using it. application-prod.yml also turns springdoc
                    // off; this is the second lock, so re-enabling springdoc alone cannot reopen the
                    // hole. Dev and test keep the permit, which is what the CI typed-client gate and
                    // local Swagger UI run against.
                    //
                    // C5. The condition used to be `if (!production)`, and both locks then hung on
                    // the same hook: a boot with no SPRING_PROFILES_ACTIVE got neither, and live —
                    // with "No active profile set" in the log — GET /v3/api-docs answered 200 with
                    // the complete document. That made this control fail-OPEN on the most ordinary
                    // configuration accident there is, while B4 next door had already been made
                    // fail-CLOSED against the identical mishap (zero.seed.enabled defaults to false
                    // and dev/test opt back in). The asymmetry was the bug. Same shape now: closed by
                    // default, opened only where it is wanted and said so out loud.
                    if (development) {
                        auth.requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html")
                                .permitAll();
                    }
                    auth.anyRequest().authenticated();
                })
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt -> jwt
                        .decoder(jwtDecoder)
                        .jwtAuthenticationConverter(jwtAuthenticationConverter)))
                // PROD-R6. After CorsFilter on purpose: a 429 then still carries the CORS and security
                // headers the browser needs to read it, and preflight OPTIONS (which CorsFilter answers
                // itself) never spends an allowance. Ahead of tenant resolution so a flood is refused
                // before it reaches the database.
                .addFilterAfter(rateLimitFilter, CorsFilter.class)
                // F1. After the limiter, not before, and the order is load-bearing in two directions.
                // The five anonymous paths are bounded at 16 KB by RateLimitFilter — 64x stricter than
                // this filter's 1 MB — so running ahead of it would raise their bound and hand B2 back
                // its 20 KB pad. And C4 requires a refusal to still spend the sender's IP allowance,
                // which only happens if the limiter sees the request first. See RequestSizeLimitFilter.
                .addFilterAfter(requestSizeLimitFilter, RateLimitFilter.class)
                // stage 1: header -> TenantContext (needed by permitAll login/refresh)
                .addFilterBefore(tenantResolverFilter, BearerTokenAuthenticationFilter.class)
                // stage 2: JWT 'tenant' claim is authoritative for authenticated requests
                .addFilterAfter(authenticatedTenantFilter, BearerTokenAuthenticationFilter.class);
        return http.build();
    }

    /**
     * PROD-R3. {@code allowCredentials} stays false: the SPA authenticates with a bearer token in the
     * {@code Authorization} header, so no cookie ever needs to cross origins — and keeping it false
     * removes the trap where a later wildcard origin would silently become exploitable.
     */
    @Bean
    public CorsConfigurationSource corsConfigurationSource(CorsProperties properties) {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(List.copyOf(properties.getAllowedOrigins()));
        configuration.setAllowedMethods(List.of(
                HttpMethod.GET.name(), HttpMethod.POST.name(), HttpMethod.PUT.name(),
                HttpMethod.PATCH.name(), HttpMethod.DELETE.name(), HttpMethod.OPTIONS.name()));
        configuration.setAllowedHeaders(List.of(
                HttpHeaders.AUTHORIZATION, HttpHeaders.CONTENT_TYPE, "X-Tenant", HttpHeaders.ACCEPT_LANGUAGE));
        // The Excel/CSV exports depend on the browser being able to read the filename.
        configuration.setExposedHeaders(List.of(HttpHeaders.CONTENT_DISPOSITION));
        configuration.setAllowCredentials(false);
        configuration.setMaxAge(1800L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }

    /**
     * D1. {@code RequestBodyFormats} is handed the {@link ObjectProvider} rather than a resolved
     * {@code RequestMappingHandlerAdapter}: the security chain is built early, and pulling the MVC
     * adapter into existence at that point would couple two unrelated corners of the startup order.
     * It resolves itself on the first throttled request instead, and the answer cannot change after
     * startup.
     */
    @Bean
    public RateLimitFilter rateLimitFilter(RateLimitProperties properties,
                                           ObjectMapper objectMapper,
                                           ObjectProvider<RequestMappingHandlerAdapter> handlerAdapters) {
        return new RateLimitFilter(properties, objectMapper, handlerAdapters);
    }

    /**
     * The filter is placed explicitly in the security chain, so Boot's automatic servlet registration
     * would otherwise run it a second time (and outside the chain).
     */
    @Bean
    public FilterRegistrationBean<RateLimitFilter> rateLimitFilterRegistration(RateLimitFilter filter) {
        FilterRegistrationBean<RateLimitFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }

    /**
     * F1. The application-wide body bound. {@link RateLimitProperties} is injected alongside its own
     * properties for the trusted-proxy depth alone — see {@code RequestSizeLimitFilter}'s constructor
     * for why that is read rather than duplicated.
     */
    @Bean
    public RequestSizeLimitFilter requestSizeLimitFilter(RequestLimitProperties properties,
                                                         RateLimitProperties rateLimitProperties,
                                                         ObjectMapper objectMapper) {
        return new RequestSizeLimitFilter(properties, rateLimitProperties, objectMapper);
    }

    /** Same reason as {@link #rateLimitFilterRegistration}: placed in the chain, not by Boot. */
    @Bean
    public FilterRegistrationBean<RequestSizeLimitFilter> requestSizeLimitFilterRegistration(
            RequestSizeLimitFilter filter) {
        FilterRegistrationBean<RequestSizeLimitFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }

    /**
     * The {@link TenantAccessCheck} is optional and injected via {@link ObjectProvider}: the security
     * chain must still start when no module contributes a gate (and the SaaS gate is what supplies it
     * here — {@code SubscriptionAccessCheck}).
     */
    @Bean
    public TenantResolverFilter tenantResolverFilter(TenantRepository tenantRepository,
                                                     ObjectProvider<TenantAccessCheck> tenantAccessChecks,
                                                     @Value("${zero.multitenancy.header:X-Tenant}") String headerName) {
        return new TenantResolverFilter(tenantRepository, headerName, tenantAccessChecks.getIfAvailable());
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
        // default validators (timestamps) + mandatory issuer check + audience (PROD-R16)
        OAuth2TokenValidator<Jwt> validator = new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(properties.getIssuer()),
                new JwtAudienceValidator(properties.getAudience()));
        decoder.setJwtValidator(validator);
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

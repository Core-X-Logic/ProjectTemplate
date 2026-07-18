package com.mycompanyname.zero.config;

import java.time.Duration;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "zero.jwt")
@Getter
@Setter
public class JwtProperties {

    private String secret;

    private Duration accessTokenTtl;

    private Duration refreshTokenTtl;

    private String issuer;

    /**
     * PROD-R16. Every access token carries this as its {@code aud} claim and the decoder rejects a
     * token that does not. Without it, any token signed with the same secret — a sibling service
     * sharing the key, a token minted for a different deployment — is accepted here.
     */
    private String audience;
}

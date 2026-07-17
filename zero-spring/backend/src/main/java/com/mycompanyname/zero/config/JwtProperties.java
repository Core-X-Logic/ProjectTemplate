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
}

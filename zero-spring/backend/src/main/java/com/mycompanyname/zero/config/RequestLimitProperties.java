package com.mycompanyname.zero.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * The application-wide request body bound (F1).
 *
 * <p>Deliberately separate from {@link RateLimitProperties}. The throttle answers "how many requests
 * may this caller make?" on five anonymous paths; this answers "how large may any request be?"
 * everywhere. Fusing them is what produced F1 in the first place: the 16 KB bound was a
 * <em>property of the rate limiter</em>, so it only ever applied where the rate limiter ran.
 */
@Component
@ConfigurationProperties(prefix = "zero.request")
@Getter
@Setter
public class RequestLimitProperties {

    /**
     * Off is not a supported production setting; it exists so a deployment chasing a body-size
     * incident can isolate this filter without redeploying a different build.
     */
    private boolean enabled = true;

    /**
     * Largest request body accepted on a matched path, in bytes.
     *
     * <p>1 MB because this is a JSON API whose largest legitimate body is a role with its permission
     * list — three orders of magnitude below it. It is not a tuning knob for "how much can we
     * handle"; it is the point past which a body is evidence of something other than ordinary use.
     *
     * <p>Kept in step with {@code spring.servlet.multipart.max-request-size} by
     * {@code application.yml}, which drives both from one placeholder. Two independent size ceilings
     * on one request is how a deployment ends up with an effective limit nobody predicted.
     */
    private int maxBodyBytes = 1024 * 1024;

    /**
     * Paths the bound applies to, as Ant patterns, matched the way {@link ThrottledPathMatcher}
     * matches — decoded lookup path, case-insensitively (B1).
     *
     * <p>{@code /api/**} rather than {@code /**} so the bound is a statement about this application's
     * own API. {@code /actuator/**} is reached by the kubelet with no body at all, and springdoc
     * serves documents rather than accepting them; extending to them would add a control where there
     * is nothing to control and a way to break a liveness probe where there was none.
     */
    private List<String> paths = new ArrayList<>(List.of("/api/**"));
}

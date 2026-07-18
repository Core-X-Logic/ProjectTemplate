package com.mycompanyname.zero.security;

import com.mycompanyname.zero.AbstractIntegrationIT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.health.HealthEndpointGroup;
import org.springframework.boot.actuate.health.HealthEndpointGroups;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PROD-R29 — which dependency is allowed to take traffic away.
 *
 * <p>Found by the first CI run this repository ever had. Three tests that assert
 * {@code /actuator/health} is anonymous failed with <b>503</b>, not 401: the mail health indicator
 * opens a real SMTP connection on every call, the runner has no mail server, so the aggregate went
 * DOWN. They had always passed locally because a developer machine happens to run mailpit on 1025.
 * The tests were not wrong; they were measuring the developer's docker-compose.
 *
 * <p>Two failures met at that point, in opposite directions, and both are pinned here:
 *
 * <ul>
 *   <li><b>A dependency that must not gate traffic did.</b> Email is not needed to serve a request.
 *       Worse, polling it is actively harmful: a 10-second probe opens roughly 8,600 SMTP
 *       connections a day, which is how a provider decides to throttle or blocklist you. The
 *       indicator is off; mail reachability is proven by the forgot-password smoke instead.</li>
 *   <li><b>A dependency that must gate traffic did not.</b> Spring Boot's default readiness group is
 *       {@code readinessState} alone — nothing else. A pod whose database is unreachable reports
 *       READY, receives traffic, and answers every request with a 500. Nothing in the suite noticed,
 *       because nothing asserted what the group contained.</li>
 * </ul>
 *
 * <p>The membership assertions are the load-bearing ones. Asserting only that the endpoints return
 * 200 would keep passing if someone set {@code readiness.include} back to the default, or added
 * {@code mail} to it — the reachable-DB case looks identical either way. The group is a
 * configuration decision, so it is tested as one.
 */
@TestPropertySource(properties = {
        // Point the mail sender at a port nothing listens on. Without this the mail assertion would
        // be vacuous on any machine running the project's mailpit container — which is exactly how
        // the defect survived until a CI runner without one finally executed the suite. Now the
        // property under test is broken deliberately, so the test fails everywhere if the indicator
        // is ever re-enabled, rather than only in the one environment nobody had run.
        "spring.mail.host=localhost",
        "spring.mail.port=1"
})
class HealthProbeContractIT extends AbstractIntegrationIT {

    @Autowired
    private HealthEndpointGroups groups;

    @Test
    void theAggregateHealthEndpointDoesNotDependOnAMailServer() {
        ResponseEntity<String> response = restTemplate.getForEntity("/actuator/health", String.class);

        assertThat(response.getStatusCode())
                .as("no SMTP server runs in CI. This answered 503 there while answering 200 on every "
                        + "developer machine, because mailpit happened to be up. Body: %s", response.getBody())
                .isEqualTo(HttpStatus.OK);
    }

    @Test
    void readinessGatesOnTheDatabase() {
        HealthEndpointGroup readiness = group("readiness");

        assertThat(readiness.isMember("db"))
                .as("Boot's default readiness group is readinessState alone, so a pod with an "
                        + "unreachable database reports READY, takes traffic, and 500s on everything. "
                        + "PROD-R29 pins db into the group; this asserts it stayed there")
                .isTrue();
        assertThat(readiness.isMember("readinessState"))
                .as("the application's own lifecycle state must stay in the group — pinning db must "
                        + "not have replaced it")
                .isTrue();
    }

    @Test
    void readinessDoesNotGateOnDependenciesTheApplicationDegradesPast() {
        HealthEndpointGroup readiness = group("readiness");

        assertThat(readiness.isMember("mail"))
                .as("email is not on the request path; gating readiness on it would pull a healthy "
                        + "instance out of rotation during an SMTP outage")
                .isFalse();
        assertThat(readiness.isMember("redis"))
                .as("PROD-R13's CacheErrorHandler makes a Redis outage degrade to a cache bypass "
                        + "with a WARN rather than a failure, so Redis must not remove the instance "
                        + "from rotation either")
                .isFalse();
    }

    @Test
    void livenessStaysNarrow() {
        HealthEndpointGroup liveness = group("liveness");

        assertThat(liveness.isMember("db"))
                .as("liveness answers 'should this process be killed'. A database outage is not a "
                        + "reason to restart the JVM — it would produce a crash-loop that makes the "
                        + "outage worse and destroys the instance's ability to report anything")
                .isFalse();
    }

    private HealthEndpointGroup group(String name) {
        Optional<HealthEndpointGroup> group = Optional.ofNullable(groups.get(name));
        assertThat(group)
                .as("the '%s' probe group must exist — management.endpoint.health.probes.enabled "
                        + "creates it, and the Dockerfile HEALTHCHECK and the k8s probes call it", name)
                .isPresent();
        return group.get();
    }
}

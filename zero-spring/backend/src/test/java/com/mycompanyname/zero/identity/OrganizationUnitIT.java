package com.mycompanyname.zero.identity;

import com.fasterxml.jackson.databind.JsonNode;
import com.mycompanyname.zero.AbstractIntegrationIT;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 2 parity proof for organization units (CONTRACT-phase2 §4.5).
 *
 * <p>ABP materialized-path parity: 5-digit zero-padded, dot-separated {@code code}. Codes are
 * asserted relatively (child = parentCode + ".00001") so the test is robust to sibling ITs that
 * also create units in the shared {@code default} tenant. Verifies move (whole subtree recoded),
 * delete cascade, and tenant isolation (a tenant's units are invisible to the host scope).
 */
class OrganizationUnitIT extends AbstractIntegrationIT {

    private static final String DEFAULT_TENANT = "default";
    private static final AtomicInteger SEQ = new AtomicInteger();

    private String unique(String prefix) {
        return prefix + "_" + System.nanoTime() + "_" + SEQ.incrementAndGet();
    }

    private HttpHeaders tenantAdmin() {
        return bearerHeaders(accessToken(DEFAULT_TENANT, SEED_ADMIN_USERNAME, SEED_ADMIN_PASSWORD), DEFAULT_TENANT);
    }

    private JsonNode createOu(HttpHeaders headers, String displayName, Long parentId) {
        Map<String, Object> body = new HashMap<>();
        body.put("displayName", displayName);
        if (parentId != null) {
            body.put("parentId", parentId);
        }
        ResponseEntity<JsonNode> created = restTemplate.exchange(
                "/api/organization-units", HttpMethod.POST, new HttpEntity<>(body, headers), JsonNode.class);
        assertThat(created.getStatusCode().is2xxSuccessful())
                .as("create OU must succeed, got %s", created.getStatusCode())
                .isTrue();
        assertThat(created.getBody()).isNotNull();
        return created.getBody();
    }

    private JsonNode findInTenantList(HttpHeaders headers, long id) {
        ResponseEntity<JsonNode> list = restTemplate.exchange(
                "/api/organization-units", HttpMethod.GET, new HttpEntity<>(headers), JsonNode.class);
        assertThat(list.getStatusCode()).isEqualTo(HttpStatus.OK);
        for (JsonNode node : pageContent(list.getBody())) {
            if (node.path("id").asLong() == id) {
                return node;
            }
        }
        return null;
    }

    @Test
    void hierarchyUsesMaterializedPathCodes() {
        HttpHeaders headers = tenantAdmin();

        JsonNode root = createOu(headers, unique("root"), null);
        String rootCode = root.path("code").asText();
        assertThat(rootCode)
                .as("root code must be a 5-digit zero-padded segment (ABP parity, e.g. 00001)")
                .matches("\\d{5}");
        long rootId = root.path("id").asLong();

        JsonNode child = createOu(headers, unique("child"), rootId);
        String childCode = child.path("code").asText();
        assertThat(childCode)
                .as("first child code must be parentCode + \".00001\"")
                .isEqualTo(rootCode + ".00001");
        assertThat(child.path("parentId").asLong()).isEqualTo(rootId);
        long childId = child.path("id").asLong();

        JsonNode grandChild = createOu(headers, unique("grandchild"), childId);
        assertThat(grandChild.path("code").asText()).isEqualTo(childCode + ".00001");
        assertThat(grandChild.path("parentId").asLong()).isEqualTo(childId);
    }

    @Test
    void moveRecodesTheWholeSubtree() {
        HttpHeaders headers = tenantAdmin();

        JsonNode rootA = createOu(headers, unique("A"), null);
        long a1 = createOu(headers, unique("A1"), rootA.path("id").asLong()).path("id").asLong();
        long a1g = createOu(headers, unique("A1g"), a1).path("id").asLong();

        // fresh destination root with no children yet
        JsonNode rootB = createOu(headers, unique("B"), null);
        String bCode = rootB.path("code").asText();

        ResponseEntity<JsonNode> moved = restTemplate.exchange(
                "/api/organization-units/" + a1 + "/move", HttpMethod.PUT,
                new HttpEntity<>(Map.of("newParentId", rootB.path("id").asLong()), headers), JsonNode.class);
        assertThat(moved.getStatusCode().is2xxSuccessful())
                .as("move must succeed, got %s", moved.getStatusCode())
                .isTrue();

        JsonNode movedA1 = findInTenantList(headers, a1);
        JsonNode movedA1g = findInTenantList(headers, a1g);
        assertThat(movedA1).as("moved node must still exist").isNotNull();
        assertThat(movedA1g).as("moved node's child must still exist").isNotNull();
        assertThat(movedA1.path("code").asText())
                .as("moved subtree root recoded under new parent")
                .isEqualTo(bCode + ".00001");
        assertThat(movedA1g.path("code").asText())
                .as("descendant codes recoded transitively")
                .isEqualTo(bCode + ".00001.00001");
        assertThat(movedA1.path("parentId").asLong()).isEqualTo(rootB.path("id").asLong());
    }

    @Test
    void deleteCascadesSubtree() {
        HttpHeaders headers = tenantAdmin();

        long root = createOu(headers, unique("delroot"), null).path("id").asLong();
        long child = createOu(headers, unique("delchild"), root).path("id").asLong();

        ResponseEntity<Void> deleted = restTemplate.exchange(
                "/api/organization-units/" + root, HttpMethod.DELETE, new HttpEntity<>(headers), Void.class);
        assertThat(deleted.getStatusCode().is2xxSuccessful())
                .as("delete must succeed, got %s", deleted.getStatusCode())
                .isTrue();

        assertThat(findInTenantList(headers, root)).as("deleted root must be gone").isNull();
        assertThat(findInTenantList(headers, child)).as("child must be cascade-deleted").isNull();
    }

    @Test
    void tenantUnitsAreInvisibleToHostScope() {
        HttpHeaders tenantHeaders = tenantAdmin();
        long tenantOuId = createOu(tenantHeaders, unique("secret"), null).path("id").asLong();

        // host scope: no X-Tenant header, host admin owns organizationunits.manage via all()
        HttpHeaders hostHeaders = bearerHeaders(accessToken(null, SEED_ADMIN_USERNAME, SEED_ADMIN_PASSWORD), null);
        ResponseEntity<JsonNode> hostList = restTemplate.exchange(
                "/api/organization-units", HttpMethod.GET, new HttpEntity<>(hostHeaders), JsonNode.class);
        assertThat(hostList.getStatusCode()).isEqualTo(HttpStatus.OK);

        List<Long> hostIds = new ArrayList<>();
        pageContent(hostList.getBody()).forEach(n -> hostIds.add(n.path("id").asLong()));
        assertThat(hostIds)
                .as("a tenant's organization units must not leak into the host scope")
                .doesNotContain(tenantOuId);
        // sanity: the unit is visible within its own tenant
        assertThat(Collections.singletonList(findInTenantList(tenantHeaders, tenantOuId)))
                .doesNotContainNull();
    }
}

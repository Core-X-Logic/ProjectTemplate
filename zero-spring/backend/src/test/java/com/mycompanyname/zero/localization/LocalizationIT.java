package com.mycompanyname.zero.localization;

import com.fasterxml.jackson.databind.JsonNode;
import com.mycompanyname.zero.AbstractIntegrationIT;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end coverage of localization.
 *
 * <p>The two built-in cultures (en/tr) each return their key/value dictionary, and the languages
 * endpoint advertises exactly the two supported languages.
 */
class LocalizationIT extends AbstractIntegrationIT {

    // a key guaranteed to exist by the contract (permission display names, §3/§7)
    private static final String KNOWN_KEY = "Permission.Pages.Administration.Users";

    private HttpHeaders auth() {
        return bearerHeaders(accessToken(null, SEED_ADMIN_USERNAME, SEED_ADMIN_PASSWORD), null);
    }

    private JsonNode culture(String culture) {
        ResponseEntity<JsonNode> response = restTemplate.exchange(
                "/api/localization/" + culture, HttpMethod.GET, new HttpEntity<>(auth()), JsonNode.class);
        assertThat(response.getStatusCode())
                .as("culture %s must resolve", culture)
                .isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        return response.getBody();
    }

    @Test
    void englishAndTurkishDictionariesAreReturned() {
        JsonNode en = culture("en");
        JsonNode tr = culture("tr");

        assertThat(en.toString())
                .as("the English dictionary must contain the known permission key")
                .contains(KNOWN_KEY);
        assertThat(tr.toString())
                .as("the Turkish dictionary must contain the known permission key")
                .contains(KNOWN_KEY);
    }

    /**
     * R-38A backstop. Every consistency rule added for the path-binding defect compares two
     * statements to each other — the {@code permitAll} matcher, the handler's
     * {@code @EndpointPolicy(ANONYMOUS)} claim, the {@code INTENTIONALLY_ANONYMOUS} entry. All of
     * them stay green if the statements are removed TOGETHER, consistently and wrongly. Only a real
     * credential-free request notices that.
     *
     * <p>And this is the request that matters: the SPA loads its dictionary BEFORE it can render a
     * login form, so a 401 here means the login screen cannot draw itself — the user-visible failure
     * behind {@code permitAll("/api/localization/**")} being written in {@code identity} while the
     * route is owned by {@code localization}. The suite had no anonymous localization case at all
     * until now; every existing test here sends a bearer token.
     */
    @Test
    void anonymousCallerReadsTheLoginDictionary() {
        ResponseEntity<JsonNode> dictionary = restTemplate.exchange(
                "/api/localization/en", HttpMethod.GET, HttpEntity.EMPTY, JsonNode.class);

        assertThat(dictionary.getStatusCode())
                .as("the login screen fetches its dictionary before it has any credential to send; "
                        + "a 401 here is a login page that cannot render")
                .isEqualTo(HttpStatus.OK);
        assertThat(dictionary.getBody()).isNotNull();
        assertThat(dictionary.getBody().toString()).contains(KNOWN_KEY);
    }

    @Test
    void languagesEndpointAdvertisesTheTwoBuiltInLanguages() {
        ResponseEntity<JsonNode> response = restTemplate.exchange(
                "/api/localization/languages", HttpMethod.GET, new HttpEntity<>(auth()), JsonNode.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);

        JsonNode content = pageContent(response.getBody());
        assertThat(content.isArray()).isTrue();

        List<String> names = new ArrayList<>();
        List<String> displayNames = new ArrayList<>();
        content.forEach(node -> {
            names.add(node.path("name").asText());
            displayNames.add(node.path("displayName").asText());
        });

        assertThat(names).containsExactlyInAnyOrder("en", "tr");
        assertThat(displayNames)
                .as("display names must be non-blank")
                .allSatisfy(dn -> assertThat(dn).isNotBlank());
        assertThat(displayNames).contains("English");
    }
}

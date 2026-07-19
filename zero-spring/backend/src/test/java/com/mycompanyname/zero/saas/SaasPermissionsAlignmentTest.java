package com.mycompanyname.zero.saas;

import com.mycompanyname.zero.identity.domain.AppPermissions;
import com.mycompanyname.zero.identity.domain.PermissionDefinitions;
import com.mycompanyname.zero.identity.domain.Side;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The SaaS permission strings are deliberately duplicated: {@code SaasPermissions} owns them for
 * {@code @PreAuthorize} inside the saas module, while {@code AppPermissions}/{@code PermissionDefinitions}
 * repeat them to register the tree — importing either direction would create a module cycle
 * (see ARCHITECTURE-RULES.md — "Modül bağımlılıkları döngü kurmaz").
 *
 * <p>This test is what makes that duplication safe: it fails the build the moment the two lists drift
 * apart, or if a SaaS permission is ever registered as anything other than {@code Side.HOST}.
 */
class SaasPermissionsAlignmentTest {

    @Test
    void everySaasPermissionIsGrantableAndRegisteredAsHostOnly() {
        assertThat(AppPermissions.all())
                .as("every SaaS permission must be grantable through the identity registry")
                .containsAll(SaasPermissions.all());

        assertThat(PermissionDefinitions.leafPermissionNames())
                .as("every SaaS permission must appear as a leaf of the permission tree")
                .containsAll(SaasPermissions.all());

        assertThat(PermissionDefinitions.hostOnlyPermissionNames())
                .as("a tenant must never be able to hold a SaaS permission")
                .containsAll(SaasPermissions.all());
    }

    @Test
    void theSaasGroupHangsOffAdministrationAndCarriesEverySaasLeaf() {
        assertThat(PermissionDefinitions.isGroup(PermissionDefinitions.GROUP_SAAS)).isTrue();

        assertThat(PermissionDefinitions.childrenOf(PermissionDefinitions.GROUP_SAAS))
                .extracting(definition -> definition.name())
                .containsExactlyInAnyOrderElementsOf(SaasPermissions.all());

        assertThat(PermissionDefinitions.childrenOf(PermissionDefinitions.GROUP_SAAS))
                .allSatisfy(definition -> assertThat(definition.side()).isEqualTo(Side.HOST));
    }
}

package com.mycompanyname.zero.identity.domain;

/**
 * A single node of the permission tree.
 *
 * @param name           unique identifier; for leaf permissions this equals the {@link AppPermissions}
 *                       string value, for group nodes it is a hierarchical key (e.g. {@code Pages.Administration.Users}).
 * @param parent         parent node name, or {@code null} for a root group.
 * @param displayNameKey localization key resolved against the {@code MessageSource}.
 * @param side           host/tenant visibility scope.
 */
public record PermissionDefinition(
        String name,
        String parent,
        String displayNameKey,
        Side side) {
}

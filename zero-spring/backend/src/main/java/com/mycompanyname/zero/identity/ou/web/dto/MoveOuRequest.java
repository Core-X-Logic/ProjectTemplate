package com.mycompanyname.zero.identity.ou.web.dto;

/**
 * Reparents an organization unit. A {@code null} {@code newParentId} promotes the unit to a root.
 */
public record MoveOuRequest(
        Long newParentId) {
}

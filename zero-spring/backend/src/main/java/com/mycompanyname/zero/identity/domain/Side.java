package com.mycompanyname.zero.identity.domain;

/**
 * Scope in which a permission (or permission group) is meaningful.
 * HOST  — only host (no-tenant) users may hold it.
 * TENANT — only tenant users may hold it.
 * BOTH  — visible/assignable to both host and tenant users.
 */
public enum Side {
    HOST,
    TENANT,
    BOTH
}

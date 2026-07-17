package com.mycompanyname.zero.settings.domain;

/**
 * Hierarchical setting scope. Resolution order (most specific first): USER -&gt; TENANT -&gt; APPLICATION.
 */
public enum Scope {
    APPLICATION,
    TENANT,
    USER
}

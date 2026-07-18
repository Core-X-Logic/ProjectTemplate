/**
 * Public surface of the SaaS module. Consumers (e.g. {@code identity} for feature gating and the
 * subscription gate in Slice B) depend on {@code saas :: api} only, never on the edition /
 * feature / subscription internals — the same pattern as {@code notification :: email}.
 */
@NamedInterface("api")
package com.mycompanyname.zero.saas.api;

import org.springframework.modulith.NamedInterface;

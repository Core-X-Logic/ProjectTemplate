/**
 * Public surface of the SaaS module. Consumers ({@code identity} for feature gating, {@code tenancy}
 * for the subscription gate) depend on {@code saas :: api} only, never on the edition /
 * feature / subscription internals — the same pattern as {@code notification :: email}.
 * See ARCHITECTURE-RULES.md — "Modül bağımlılıkları döngü kurmaz".
 */
@NamedInterface("api")
package com.mycompanyname.zero.saas.api;

import org.springframework.modulith.NamedInterface;

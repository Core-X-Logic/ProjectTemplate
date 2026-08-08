// "config" arrived with ADR-0020: managed billing credentials encrypt at rest through
// config.FieldEncryptionService — the identity module's existing pattern (TOTP secret).
@ApplicationModule(allowedDependencies = {"shared", "tenancy", "settings", "config"})
package com.mycompanyname.zero.saas;

import org.springframework.modulith.ApplicationModule;

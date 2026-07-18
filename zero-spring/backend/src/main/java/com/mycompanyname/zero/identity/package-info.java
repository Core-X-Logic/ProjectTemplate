@ApplicationModule(allowedDependencies = {"shared", "tenancy", "config", "settings", "notification",
        "notification :: email", "saas :: api"})
package com.mycompanyname.zero.identity;

import org.springframework.modulith.ApplicationModule;

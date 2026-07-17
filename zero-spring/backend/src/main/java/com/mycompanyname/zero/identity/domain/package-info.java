@NamedInterface("domain")
@FilterDef(name = "tenantFilter", parameters = @ParamDef(name = "tenantId", type = Long.class))
@FilterDef(name = "hostFilter")
package com.mycompanyname.zero.identity.domain;

import org.hibernate.annotations.FilterDef;
import org.hibernate.annotations.ParamDef;
import org.springframework.modulith.NamedInterface;

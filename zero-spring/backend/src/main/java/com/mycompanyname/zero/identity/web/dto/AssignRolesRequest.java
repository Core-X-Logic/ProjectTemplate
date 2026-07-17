package com.mycompanyname.zero.identity.web.dto;

import java.util.Set;

public record AssignRolesRequest(Set<String> roleNames) {
}

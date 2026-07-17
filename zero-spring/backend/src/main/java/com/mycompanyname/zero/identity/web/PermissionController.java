package com.mycompanyname.zero.identity.web;

import com.mycompanyname.zero.identity.auth.CurrentUser;
import com.mycompanyname.zero.identity.permission.PermissionService;
import com.mycompanyname.zero.identity.web.dto.PermissionNodeDto;
import lombok.RequiredArgsConstructor;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/permissions")
@RequiredArgsConstructor
public class PermissionController {

    private final PermissionService permissionService;

    /**
     * Returns the assignable permission tree, filtered to the caller's side (host vs tenant).
     * Any authenticated user may read it so a role editor can display the permissions the user
     * is entitled to grant; editing a grant still requires {@code roles.update}.
     */
    @GetMapping("/tree")
    @PreAuthorize("isAuthenticated()")
    public List<PermissionNodeDto> tree() {
        boolean host = CurrentUser.tenantId() == null;
        return permissionService.tree(host, LocaleContextHolder.getLocale());
    }
}

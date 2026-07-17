package com.mycompanyname.zero.identity.permission;

import com.mycompanyname.zero.identity.domain.PermissionDefinition;
import com.mycompanyname.zero.identity.domain.PermissionDefinitions;
import com.mycompanyname.zero.identity.domain.Side;
import com.mycompanyname.zero.identity.web.dto.PermissionNodeDto;
import lombok.RequiredArgsConstructor;
import org.springframework.context.MessageSource;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Builds the side-filtered permission tree exposed to clients so a role editor can render the
 * assignable permissions. Display names are resolved against the {@link MessageSource}; when a
 * key is missing (e.g. before the localization bundle is populated) the raw permission name is
 * returned so the endpoint never fails.
 */
@Service
@RequiredArgsConstructor
public class PermissionService {

    private final MessageSource messageSource;

    public List<PermissionNodeDto> tree(boolean host, Locale locale) {
        Set<Side> allowed = host
                ? EnumSet.of(Side.HOST, Side.BOTH)
                : EnumSet.of(Side.TENANT, Side.BOTH);
        List<PermissionNodeDto> roots = new ArrayList<>();
        for (PermissionDefinition root : PermissionDefinitions.roots()) {
            build(root, allowed, locale).ifPresent(roots::add);
        }
        return roots;
    }

    private Optional<PermissionNodeDto> build(PermissionDefinition definition, Set<Side> allowed, Locale locale) {
        if (!allowed.contains(definition.side())) {
            return Optional.empty();
        }
        List<PermissionNodeDto> children = new ArrayList<>();
        for (PermissionDefinition child : PermissionDefinitions.childrenOf(definition.name())) {
            build(child, allowed, locale).ifPresent(children::add);
        }
        // Prune group nodes that ended up with no visible children.
        if (PermissionDefinitions.isGroup(definition.name()) && children.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new PermissionNodeDto(
                definition.name(),
                displayName(definition, locale),
                definition.parent(),
                children));
    }

    private String displayName(PermissionDefinition definition, Locale locale) {
        return messageSource.getMessage(
                definition.displayNameKey(),
                null,
                definition.name(),
                locale != null ? locale : Locale.ENGLISH);
    }
}

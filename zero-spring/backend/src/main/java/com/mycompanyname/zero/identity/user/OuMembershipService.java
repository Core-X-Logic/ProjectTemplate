package com.mycompanyname.zero.identity.user;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Read-side helper for user &harr; organization-unit membership.
 *
 * <p><strong>Ownership / boundary note.</strong> Membership <em>writes</em> are owned by
 * {@code UserService} through the {@code User.organizationUnitIds} {@code @ElementCollection}
 * (the {@code PUT /api/users/{id}/organization-units} endpoint delegates there). This service is the
 * read counterpart used by the OU tree: it reports member counts sourced from that same single
 * mapping, so the {@code user_organization_units} table has exactly one owner and no reverse
 * dependency is introduced. OU subtree deletion relies on the table's {@code ON DELETE CASCADE}
 * foreign key to drop stale memberships.
 */
@Service
@RequiredArgsConstructor
public class OuMembershipService {

    private final OuMembershipRepository membershipRepository;

    /** Member counts keyed by organization-unit id; units with no members are omitted. */
    @Transactional(readOnly = true)
    public Map<Long, Long> memberCounts(Collection<Long> ouIds) {
        Map<Long, Long> counts = new HashMap<>();
        if (ouIds == null || ouIds.isEmpty()) {
            return counts;
        }
        List<Object[]> rows = membershipRepository.countMembersByOrganizationUnitIds(ouIds);
        for (Object[] row : rows) {
            Long ouId = ((Number) row[0]).longValue();
            long count = ((Number) row[1]).longValue();
            counts.put(ouId, count);
        }
        return counts;
    }
}

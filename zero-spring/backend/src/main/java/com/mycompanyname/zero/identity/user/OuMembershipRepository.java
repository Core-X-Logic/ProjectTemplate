package com.mycompanyname.zero.identity.user;

import com.mycompanyname.zero.identity.domain.User;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

/**
 * Read-only view over the user &harr; organization-unit membership.
 *
 * <p>The membership is owned by {@code User.organizationUnitIds} ({@code @ElementCollection} on the
 * {@code user_organization_units} table) — the single source of truth, written by
 * {@code UserService}. This repository only <em>reads</em> that relation (member counts for the OU
 * tree) by joining the element collection, so there is exactly one mapping of the table. Counts
 * honour the {@code User} soft-delete restriction and the active tenant/host Hibernate filter.
 */
public interface OuMembershipRepository extends Repository<User, Long> {

    /**
     * Returns one row per organization unit that has at least one member:
     * {@code [0] = organizationUnitId (Long), [1] = memberCount (Long)}.
     */
    @Query("select ou, count(u.id) from User u join u.organizationUnitIds ou "
            + "where ou in :ouIds group by ou")
    List<Object[]> countMembersByOrganizationUnitIds(@Param("ouIds") Collection<Long> ouIds);
}

package com.mycompanyname.zero.identity.repo;

import com.mycompanyname.zero.identity.domain.User;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByTenantIdAndUsernameIgnoreCase(Long tenantId, String username);

    Optional<User> findByUsernameIgnoreCaseAndTenantIdIsNull(String username);

    Optional<User> findByTenantIdAndEmailIgnoreCase(Long tenantId, String email);

    Optional<User> findByEmailIgnoreCaseAndTenantIdIsNull(String email);

    boolean existsByTenantIdAndUsernameIgnoreCase(Long tenantId, String username);

    boolean existsByUsernameIgnoreCaseAndTenantIdIsNull(String username);

    long countByRolesId(Long roleId);

    /**
     * Live user count of a tenant, used to enforce {@code app.maxUserCount}. Soft-deleted rows are
     * excluded automatically by the {@code @SQLRestriction("deleted = false")} on {@code User}, so a
     * tenant that deletes a user regains a seat.
     */
    long countByTenantId(Long tenantId);

    /**
     * Stage 1 of the export, and the reason the export is NOT one query (W5-3).
     *
     * <p>The previous form was {@code @EntityGraph(attributePaths = "roles") List<User>
     * findAllByTenantId(Long)} — no {@code Pageable}, which is what made the collection fetch legal,
     * and also what made the result the whole tenant. Bounding it is therefore not a matter of
     * adding a {@code Pageable} to that method: {@code @EntityGraph} plus {@code Pageable} is the
     * combination Hibernate cannot express (HHH90003004 — it reads every row and applies the limit
     * in memory), so the bound would have been enforced AFTER the very allocation it exists to
     * prevent, and architecture rule 1 would have failed the build for it.
     *
     * <p>So the export borrows the two-stage shape the paged listing already uses: this query
     * selects ids only, has no collection fetch, and lets the database apply
     * {@code fetch first N rows only}. Stage 2 is {@link #findAllByIdIn(Collection)}, which fetches
     * the roles for the ids that survived — a set already known to be within the bound.
     *
     * <p>Returns {@code List} rather than {@code Page} deliberately: a {@code Page} would issue a
     * count query on every export, and {@code BoundedExport} answers "is there more?" from the one
     * extra row it asked for instead.
     */
    @Query("select u.id from User u where u.tenantId = :tenantId")
    List<Long> findExportIdsByTenantId(@Param("tenantId") Long tenantId, Pageable pageable);

    /** Host-scope ({@code tenant_id is null}) counterpart of {@link #findExportIdsByTenantId}. */
    @Query("select u.id from User u where u.tenantId is null")
    List<Long> findExportIdsByTenantIdIsNull(Pageable pageable);

    /**
     * Stage 1 of the two-stage listing: page the IDS of a tenant's users, with an optional
     * case-insensitive search over username/email/name. A null (or blank, normalized to null by the
     * caller) {@code search} disables the filter and returns every user in the tenant. The
     * {@code @SQLRestriction("deleted = false")} on User keeps soft-deleted rows hidden here as
     * everywhere else.
     *
     * <p><b>Why ids and not entities.</b> There is no collection fetch in this query, so Hibernate
     * can push {@code fetch first ... rows only} into SQL and the database does the paging. The
     * previous form combined {@code @EntityGraph} with {@code Pageable}, which Hibernate cannot
     * express in one statement: it logged {@code HHH90003004}, read every matching row and sliced
     * the list in memory. Stage 2 is {@link #findAllByIdIn(Collection)}.
     *
     * <p>The count query is derived by Spring Data from this one;
     * {@code PagedListingIsNotSlicedInMemoryIT.pageSizeTotalsAndBoundariesAreExact} asserts the
     * totals rather than trusting the derivation.
     */
    @Query("""
            select u.id from User u
            where u.tenantId = :tenantId
              and (cast(:search as string) is null
                   or lower(u.username) like lower(concat('%', cast(:search as string), '%'))
                   or lower(u.email) like lower(concat('%', cast(:search as string), '%'))
                   or lower(u.name) like lower(concat('%', cast(:search as string), '%')))
            """)
    Page<Long> searchIdsByTenantId(@Param("tenantId") Long tenantId,
                                   @Param("search") String search,
                                   Pageable pageable);

    /** Host-scope ({@code tenant_id is null}) counterpart of {@link #searchIdsByTenantId}. */
    @Query("""
            select u.id from User u
            where u.tenantId is null
              and (cast(:search as string) is null
                   or lower(u.username) like lower(concat('%', cast(:search as string), '%'))
                   or lower(u.email) like lower(concat('%', cast(:search as string), '%'))
                   or lower(u.name) like lower(concat('%', cast(:search as string), '%')))
            """)
    Page<Long> searchIdsByTenantIdIsNull(@Param("search") String search, Pageable pageable);

    /**
     * Stage 2 of the two-stage listing: hydrate one page's worth of ids together with their roles.
     * No {@code Pageable} — the page has already been chosen — so the fetch join may multiply rows
     * freely and Hibernate collapses them without paginating anything.
     *
     * <p><b>Returns the rows in an UNSPECIFIED order.</b> {@code where id in (:ids)} says nothing
     * about ordering, so the caller must restore stage 1's order; see
     * {@code UserService.inOrderOf}. Skipping that step yields a page holding the right rows, the
     * right count and the right totals, shuffled — which no count-based test can detect.
     */
    @EntityGraph(attributePaths = "roles")
    List<User> findAllByIdIn(Collection<Long> ids);

    /**
     * Members of a tenant-side role by name — the recipient set of the SaaS notification bridge
     * (a subscription event notifies the tenant's {@code Admin} role members). Derived across the
     * {@code user_roles} join; active-only so a deactivated admin is not notified.
     */
    List<User> findByTenantIdAndActiveTrueAndRoles_NameIgnoreCase(Long tenantId, String roleName);
}

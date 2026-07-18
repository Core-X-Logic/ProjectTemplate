package com.mycompanyname.zero.identity.repo;

import com.mycompanyname.zero.identity.domain.User;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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

    @EntityGraph(attributePaths = "roles")
    Page<User> findAllByTenantId(Long tenantId, Pageable pageable);

    @EntityGraph(attributePaths = "roles")
    Page<User> findAllByTenantIdIsNull(Pageable pageable);

    /**
     * Tenant-scoped listing with an optional case-insensitive search over username/email/name.
     * A null (or blank, normalized to null by the caller) {@code search} disables the filter and
     * returns every user in the tenant. The {@code @SQLRestriction("deleted = false")} on User keeps
     * soft-deleted rows hidden here as everywhere else.
     */
    @EntityGraph(attributePaths = "roles")
    @Query("""
            select u from User u
            where u.tenantId = :tenantId
              and (cast(:search as string) is null
                   or lower(u.username) like lower(concat('%', cast(:search as string), '%'))
                   or lower(u.email) like lower(concat('%', cast(:search as string), '%'))
                   or lower(u.name) like lower(concat('%', cast(:search as string), '%')))
            """)
    Page<User> searchByTenantId(@Param("tenantId") Long tenantId,
                                @Param("search") String search,
                                Pageable pageable);

    /** Host-scope ({@code tenant_id is null}) counterpart of {@link #searchByTenantId}. */
    @EntityGraph(attributePaths = "roles")
    @Query("""
            select u from User u
            where u.tenantId is null
              and (cast(:search as string) is null
                   or lower(u.username) like lower(concat('%', cast(:search as string), '%'))
                   or lower(u.email) like lower(concat('%', cast(:search as string), '%'))
                   or lower(u.name) like lower(concat('%', cast(:search as string), '%')))
            """)
    Page<User> searchByTenantIdIsNull(@Param("search") String search, Pageable pageable);
}

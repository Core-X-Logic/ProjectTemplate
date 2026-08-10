package com.mycompanyname.zero.identity.user;

import com.mycompanyname.zero.identity.domain.Role;
import com.mycompanyname.zero.identity.domain.User;
import com.mycompanyname.zero.identity.password.PasswordHistoryService;
import com.mycompanyname.zero.identity.repo.RefreshTokenRepository;
import com.mycompanyname.zero.identity.repo.RoleRepository;
import com.mycompanyname.zero.identity.repo.UserRepository;
import com.mycompanyname.zero.identity.web.dto.CreateUserRequest;
import com.mycompanyname.zero.identity.web.dto.UpdateUserRequest;
import com.mycompanyname.zero.identity.web.dto.UserDto;
import com.mycompanyname.zero.notification.NotificationLevel;
import com.mycompanyname.zero.notification.NotificationService;
import com.mycompanyname.zero.notification.email.EmailSender;
import com.mycompanyname.zero.notification.email.EmailTemplateService;
import com.mycompanyname.zero.saas.api.FeatureChecker;
import com.mycompanyname.zero.saas.api.SaasFeatures;
import com.mycompanyname.zero.shared.BoundedExport;
import com.mycompanyname.zero.shared.domain.DomainException;
import com.mycompanyname.zero.shared.domain.ErrorCode;
import com.mycompanyname.zero.shared.tenant.TenantContext;
import com.mycompanyname.zero.tenancy.HibernateTenantFilterAspect;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import org.hibernate.Session;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

@Service
@RequiredArgsConstructor
@Transactional
public class UserService {

    private static final String[] EXPORT_COLUMNS = {
            "Id", "Username", "Email", "Name", "Surname", "PhoneNumber", "Active", "EmailConfirmed", "Roles"
    };

    /** Names this export in the refusal message; a constant, never anything the caller sent. */
    private static final String EXPORT_SUBJECT = "user";

    @PersistenceContext
    private EntityManager entityManager;

    private final BoundedExport boundedExport;
    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final PasswordHistoryService passwordHistoryService;
    private final EmailSender emailSender;
    private final EmailTemplateService emailTemplateService;
    private final NotificationService notificationService;
    private final MessageSource messageSource;
    private final FeatureChecker featureChecker;

    public UserDto createUser(CreateUserRequest request) {
        Long tenantId = TenantContext.getTenantId();
        enforceMaxUserCount(tenantId);
        boolean usernameTaken = tenantId == null
                ? userRepository.existsByUsernameIgnoreCaseAndTenantIdIsNull(request.username())
                : userRepository.existsByTenantIdAndUsernameIgnoreCase(tenantId, request.username());
        if (usernameTaken) {
            throw new DomainException(ErrorCode.CONFLICT, "Username already exists: " + request.username());
        }
        User user = new User();
        user.setTenantId(tenantId);
        user.setUsername(request.username());
        user.setEmail(request.email());
        user.setName(request.name());
        user.setSurname(request.surname());
        user.setPhoneNumber(request.phoneNumber());
        user.setPasswordHash(passwordEncoder.encode(request.password()));
        user.setActive(true);
        user.getRoles().addAll(resolveRoles(tenantId, request.roleNames()));
        if (request.organizationUnitIds() != null) {
            user.getOrganizationUnitIds().addAll(request.organizationUnitIds());
        }
        User saved = userRepository.save(user);
        // Track the initial password so it counts toward the reuse window, and greet the new user.
        passwordHistoryService.record(saved.getId(), saved.getPasswordHash());
        sendWelcomeEmail(saved);
        publishWelcomeNotification(saved);
        return toDto(saved);
    }

    public UserDto update(Long id, UpdateUserRequest request) {
        User user = getInCurrentTenantOrThrow(id);
        if (request.email() != null && !request.email().isBlank()) {
            user.setEmail(request.email());
        }
        if (request.password() != null && !request.password().isBlank()) {
            // Retire the replaced hash into history so the reuse window is enforced on admin resets too.
            String previousHash = user.getPasswordHash();
            user.setPasswordHash(passwordEncoder.encode(request.password()));
            if (previousHash != null) {
                passwordHistoryService.record(user.getId(), previousHash);
            }
        }
        if (request.active() != null) {
            user.setActive(request.active());
        }
        if (request.roleNames() != null) {
            Set<Role> roles = resolveRoles(user.getTenantId(), request.roleNames());
            user.getRoles().clear();
            user.getRoles().addAll(roles);
        }
        return toDto(userRepository.save(user));
    }

    public void delete(Long id) {
        User user = getInCurrentTenantOrThrow(id);
        // Soft delete. The @SQLRestriction("deleted = false") on User hides the row from
        // every subsequent query; tokens are revoked so the deleted account cannot refresh a session.
        user.setDeleted(true);
        user.setDeletedAt(Instant.now());
        user.setActive(false);
        userRepository.save(user);
        refreshTokenRepository.revokeAllByUserId(user.getId());
    }

    public UserDto unlock(Long id) {
        User user = getInCurrentTenantOrThrow(id);
        user.setFailedLoginAttempts(0);
        user.setLockoutEndAt(null);
        return toDto(userRepository.save(user));
    }

    public UserDto activate(Long id) {
        User user = getInCurrentTenantOrThrow(id);
        user.setActive(true);
        return toDto(userRepository.save(user));
    }

    public UserDto deactivate(Long id) {
        User user = getInCurrentTenantOrThrow(id);
        user.setActive(false);
        userRepository.save(user);
        refreshTokenRepository.revokeAllByUserId(user.getId());
        return toDto(user);
    }

    /**
     * Q-03. Paged in TWO queries, on purpose.
     *
     * <p>The obvious single-query form — {@code @EntityGraph("roles")} on a method taking a
     * {@code Pageable} — asks for a {@code LIMIT} over a join that multiplies rows, which SQL cannot
     * express. Hibernate does not refuse it: it logs {@code HHH90003004}, reads EVERY matching row
     * and paginates the list in Java. At demo scale the response is identical, so nothing catches it;
     * at fifty thousand users every page request pulls the table through the heap and {@code size}
     * stops bounding anything.
     *
     * <p>So: stage 1 pages the ids (no collection fetch, the database applies the limit), stage 2
     * hydrates just those ids with their roles (no limit, the join is free to multiply).
     * {@code totalElements} comes from stage 1, which counted the matching rows — not from the
     * hydrated list, which only ever holds one page.
     *
     * <p>Between the two queries a row can be deleted by someone else; that id simply drops out of
     * the page. Inherent to any two-query pagination, and preferable to failing the request.
     */
    @Transactional(readOnly = true)
    public Page<UserDto> list(Pageable pageable, String search) {
        Long tenantId = TenantContext.getTenantId();
        // Normalize blank/whitespace-only input to null so the repository's (:search is null) branch
        // short-circuits the LIKE filter and returns the full tenant-scoped page.
        String term = (search == null || search.isBlank()) ? null : search.trim();
        Page<Long> idPage = tenantId == null
                ? userRepository.searchIdsByTenantIdIsNull(term, pageable)
                : userRepository.searchIdsByTenantId(tenantId, term, pageable);

        List<Long> ids = idPage.getContent();
        if (ids.isEmpty()) {
            return new PageImpl<>(List.of(), pageable, idPage.getTotalElements());
        }
        List<UserDto> content = inOrderOf(ids, userRepository.findAllByIdIn(ids)).stream()
                .map(this::toDto)
                .toList();
        return new PageImpl<>(content, pageable, idPage.getTotalElements());
    }

    /**
     * Host-side listing of ONE tenant's users — the picker behind "view this tenant's users /
     * impersonate one" on the tenants screen.
     *
     * <p>Host cross-tenant READ visibility of {@code users} is product behaviour (ADR-0018;
     * {@code TenantFilterCoverageIT} pins the filter side), but {@link #list} deliberately answers
     * for the CALLER's scope only — in host context that is the {@code tenant_id is null} rows. This
     * method is the explicit opt-in for "a different tenant's users", and it refuses to exist for a
     * tenant caller: the JWT {@code tenant} claim is authoritative, so a tenant operator passing a
     * foreign {@code tenantId} is a spoof attempt and gets {@code FORBIDDEN}, not an empty page —
     * an empty page would read as "that tenant has no users".
     *
     * <p>Same two-stage pagination as {@link #list}, same reasons (Q-03).
     *
     * <p><b>Why the session filter is suspended.</b> The aspect armed {@code hostFilter}
     * ({@code tenant_id is null}) at method entry — correct for every other read in this service,
     * but here it would AND itself onto the explicit {@code tenantId = :tenantId} predicate and
     * silently answer an EMPTY page (measured: the IT's happy path failed exactly this way before
     * this block existed). The explicit predicate is the primary defense and names the one tenant
     * this method may see; the RLS floor stays in place either way (host context, ADR-0018: host
     * reads {@code users} cross-tenant). Restore mirrors {@code TenantAdminBootstrapper}: the
     * finally re-arms {@code hostFilter} — the guard above proved the caller is host — so the rest
     * of the transaction runs behind the same filter it started with.
     */
    @Transactional(readOnly = true)
    public Page<UserDto> listForTenant(Long tenantId, Pageable pageable, String search) {
        if (TenantContext.getTenantId() != null) {
            throw new DomainException(ErrorCode.FORBIDDEN,
                    "Cross-tenant user listing is host-only");
        }
        String term = (search == null || search.isBlank()) ? null : search.trim();
        Session session = entityManager.unwrap(Session.class);
        session.disableFilter(HibernateTenantFilterAspect.HOST_FILTER);
        try {
            Page<Long> idPage = userRepository.searchIdsByTenantId(tenantId, term, pageable);
            List<Long> ids = idPage.getContent();
            if (ids.isEmpty()) {
                return new PageImpl<>(List.of(), pageable, idPage.getTotalElements());
            }
            List<UserDto> content = inOrderOf(ids, userRepository.findAllByIdIn(ids)).stream()
                    .map(this::toDto)
                    .toList();
            return new PageImpl<>(content, pageable, idPage.getTotalElements());
        } finally {
            session.enableFilter(HibernateTenantFilterAspect.HOST_FILTER);
        }
    }

    /**
     * Restores stage 1's ordering over stage 2's result, and is the whole reason the two-stage split
     * is not a free lunch.
     *
     * <p>{@code where id in (:ids)} makes NO promise about row order — the database returns them in
     * whatever order suits its plan, which is typically physical/insertion order and therefore
     * usually <em>not</em> the {@code ORDER BY} the caller asked for. Drop this step and the page
     * still holds exactly the right rows, in exactly the right number, with exactly the right
     * totals; only the order is wrong. Every count-based assertion in the suite stays green and the
     * user sees a sorted table that is not sorted, so this is pinned by a test of its own
     * ({@code PagedListingIsNotSlicedInMemoryIT.theRequestedSortOrderSurvivesTheSecondQuery}).
     *
     * <p>The map also collapses the duplicate references a collection fetch join can produce.
     */
    static List<User> inOrderOf(List<Long> ids, List<User> rows) {
        Map<Long, User> byId = new HashMap<>();
        for (User row : rows) {
            byId.put(row.getId(), row);
        }
        List<User> ordered = new ArrayList<>(ids.size());
        for (Long id : ids) {
            User row = byId.get(id);
            if (row != null) {
                ordered.add(row);
            }
        }
        return ordered;
    }

    @Transactional(readOnly = true)
    public UserDto getById(Long id) {
        return toDto(getInCurrentTenantOrThrow(id));
    }

    public UserDto assignRoles(Long id, Set<String> roleNames) {
        User user = getInCurrentTenantOrThrow(id);
        Set<Role> roles = resolveRoles(user.getTenantId(), roleNames);
        user.getRoles().clear();
        user.getRoles().addAll(roles);
        return toDto(userRepository.save(user));
    }

    public UserDto assignOrganizationUnits(Long id, Set<Long> ouIds) {
        User user = getInCurrentTenantOrThrow(id);
        user.getOrganizationUnitIds().clear();
        if (ouIds != null) {
            user.getOrganizationUnitIds().addAll(ouIds);
        }
        return toDto(userRepository.save(user));
    }

    /**
     * W5-3. Bounded by {@code BoundedExport}, in two stages for the reason given on
     * {@code UserRepository.findExportIdsByTenantId}: the roles fetch cannot sit under a
     * {@code LIMIT}, so the limit is applied to a query that selects ids only and the roles are
     * fetched afterwards for the ids that survived it.
     *
     * <p>This used to read the caller's entire scope and then resolve every user's roles. Refusing
     * over the limit rather than truncating is deliberate — see {@code ExportLimitProperties}.
     */
    @Transactional(readOnly = true)
    public byte[] exportToExcel() {
        Long tenantId = TenantContext.getTenantId();
        List<Long> ids = boundedExport.fetch(EXPORT_SUBJECT, Sort.by(Sort.Direction.ASC, "id"),
                pageable -> tenantId == null
                        ? userRepository.findExportIdsByTenantIdIsNull(pageable)
                        : userRepository.findExportIdsByTenantId(tenantId, pageable));
        // where id in (:ids) carries no order guarantee, exactly as in list(); restore stage 1's.
        List<User> users = ids.isEmpty()
                ? List.of()
                : inOrderOf(ids, userRepository.findAllByIdIn(ids));

        try (Workbook workbook = new XSSFWorkbook();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("Users");
            Row header = sheet.createRow(0);
            for (int i = 0; i < EXPORT_COLUMNS.length; i++) {
                header.createCell(i).setCellValue(EXPORT_COLUMNS[i]);
            }
            int rowIndex = 1;
            for (User user : users) {
                Row row = sheet.createRow(rowIndex++);
                row.createCell(0).setCellValue(user.getId() == null ? 0 : user.getId());
                row.createCell(1).setCellValue(nullSafe(user.getUsername()));
                row.createCell(2).setCellValue(nullSafe(user.getEmail()));
                row.createCell(3).setCellValue(nullSafe(user.getName()));
                row.createCell(4).setCellValue(nullSafe(user.getSurname()));
                row.createCell(5).setCellValue(nullSafe(user.getPhoneNumber()));
                row.createCell(6).setCellValue(user.isActive());
                row.createCell(7).setCellValue(user.isEmailConfirmed());
                row.createCell(8).setCellValue(String.join(", ", roleNamesOf(user)));
            }
            // Fixed column widths (autoSizeColumn is avoided: it triggers AWT font metrics that
            // are unreliable in headless CI environments).
            for (int i = 0; i < EXPORT_COLUMNS.length; i++) {
                sheet.setColumnWidth(i, 24 * 256);
            }
            workbook.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new DomainException(ErrorCode.INTERNAL, "Failed to generate the user export");
        }
    }

    /**
     * Enforces the {@code app.maxUserCount} feature of the tenant's package.
     *
     * <p>Two semantics are deliberate: {@code 0} means <em>unlimited</em> (not "no users allowed"),
     * and host-scope users are not governed by a tenant feature at all. The limit is a numeric one,
     * so it cannot be expressed with {@code @RequiresFeature} — it needs the current usage, which is
     * why the check is programmatic.
     */
    private void enforceMaxUserCount(Long tenantId) {
        if (tenantId == null) {
            return;
        }
        int maxUserCount = featureChecker.intValue(SaasFeatures.MAX_USER_COUNT);
        if (maxUserCount <= 0) {
            return;
        }
        long currentUserCount = userRepository.countByTenantId(tenantId);
        if (currentUserCount >= maxUserCount) {
            throw new DomainException(ErrorCode.VALIDATION,
                    "The tenant has reached the maximum number of users allowed by its package ("
                            + maxUserCount + ")");
        }
    }

    private void sendWelcomeEmail(User user) {
        if (user.getEmail() == null || user.getEmail().isBlank()) {
            return;
        }
        String name = user.getName() != null && !user.getName().isBlank() ? user.getName() : user.getUsername();
        String body = emailTemplateService.welcome(name, user.getUsername());
        emailSender.send(user.getEmail(), subject("Email.Welcome.Subject"), body);
    }

    private void publishWelcomeNotification(User user) {
        // In-app companion to the welcome email. Runs inside createUser's transaction; identity may
        // depend on the notification module (the reverse would create a cycle).
        String title = subject("Email.Welcome.Subject");
        notificationService.publish(user.getId(), user.getTenantId(), "welcome",
                NotificationLevel.SUCCESS, title, null, null);
    }

    private String subject(String key) {
        return messageSource.getMessage(key, null, key, LocaleContextHolder.getLocale());
    }

    private User getInCurrentTenantOrThrow(Long id) {
        Long tenantId = TenantContext.getTenantId();
        return userRepository.findById(id)
                .filter(user -> Objects.equals(user.getTenantId(), tenantId))
                .orElseThrow(() -> new DomainException(ErrorCode.NOT_FOUND, "User not found: " + id));
    }

    private Set<Role> resolveRoles(Long tenantId, Set<String> roleNames) {
        Set<Role> roles = new LinkedHashSet<>();
        if (roleNames == null) {
            return roles;
        }
        for (String roleName : roleNames) {
            Role role = (tenantId == null
                    ? roleRepository.findByNameIgnoreCaseAndTenantIdIsNull(roleName)
                    : roleRepository.findByTenantIdAndNameIgnoreCase(tenantId, roleName))
                    .orElseThrow(() -> new DomainException(ErrorCode.NOT_FOUND, "Role not found: " + roleName));
            roles.add(role);
        }
        return roles;
    }

    private Set<String> roleNamesOf(User user) {
        Set<String> roleNames = new LinkedHashSet<>();
        for (Role role : user.getRoles()) {
            roleNames.add(role.getName());
        }
        return roleNames;
    }

    private UserDto toDto(User user) {
        return new UserDto(
                user.getId(),
                user.getUsername(),
                user.getEmail(),
                user.getName(),
                user.getSurname(),
                user.getPhoneNumber(),
                user.isActive(),
                user.isEmailConfirmed(),
                user.getLockoutEndAt(),
                user.getTenantId(),
                roleNamesOf(user));
    }

    private static String nullSafe(String value) {
        return value == null ? "" : value;
    }
}

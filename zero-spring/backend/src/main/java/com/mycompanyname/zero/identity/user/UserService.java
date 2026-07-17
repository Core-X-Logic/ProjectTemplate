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
import com.mycompanyname.zero.shared.domain.DomainException;
import com.mycompanyname.zero.shared.domain.ErrorCode;
import com.mycompanyname.zero.shared.tenant.TenantContext;
import lombok.RequiredArgsConstructor;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

@Service
@RequiredArgsConstructor
@Transactional
public class UserService {

    private static final String[] EXPORT_COLUMNS = {
            "Id", "Username", "Email", "Name", "Surname", "PhoneNumber", "Active", "EmailConfirmed", "Roles"
    };

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final PasswordHistoryService passwordHistoryService;
    private final EmailSender emailSender;
    private final EmailTemplateService emailTemplateService;
    private final NotificationService notificationService;
    private final MessageSource messageSource;

    public UserDto createUser(CreateUserRequest request) {
        Long tenantId = TenantContext.getTenantId();
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
        // Phase 2: soft delete. The @SQLRestriction("deleted = false") on User hides the row from
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

    @Transactional(readOnly = true)
    public Page<UserDto> list(Pageable pageable, String search) {
        Long tenantId = TenantContext.getTenantId();
        // Normalize blank/whitespace-only input to null so the repository's (:search is null) branch
        // short-circuits the LIKE filter and returns the full tenant-scoped page.
        String term = (search == null || search.isBlank()) ? null : search.trim();
        Page<User> page = tenantId == null
                ? userRepository.searchByTenantIdIsNull(term, pageable)
                : userRepository.searchByTenantId(tenantId, term, pageable);
        return page.map(this::toDto);
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

    @Transactional(readOnly = true)
    public byte[] exportToExcel() {
        Long tenantId = TenantContext.getTenantId();
        List<User> users = (tenantId == null
                ? userRepository.findAllByTenantIdIsNull(Pageable.unpaged())
                : userRepository.findAllByTenantId(tenantId, Pageable.unpaged())).getContent();

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

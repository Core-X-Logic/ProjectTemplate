package com.mycompanyname.zero.identity.web;

import com.mycompanyname.zero.identity.user.UserService;
import com.mycompanyname.zero.identity.web.dto.AssignOuRequest;
import com.mycompanyname.zero.identity.web.dto.AssignRolesRequest;
import com.mycompanyname.zero.identity.web.dto.CreateUserRequest;
import com.mycompanyname.zero.identity.web.dto.UpdateUserRequest;
import com.mycompanyname.zero.identity.web.dto.UserDto;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
public class UserController {

    private static final String XLSX_CONTENT_TYPE =
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    private final UserService userService;

    @GetMapping
    @PreAuthorize("hasAuthority('users.read')")
    public Page<UserDto> list(Pageable pageable,
                              @RequestParam(required = false) String search) {
        return userService.list(pageable, search);
    }

    @GetMapping("/export")
    @PreAuthorize("hasAuthority('users.read')")
    public ResponseEntity<byte[]> export() {
        byte[] data = userService.exportToExcel();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"users.xlsx\"")
                .contentType(MediaType.parseMediaType(XLSX_CONTENT_TYPE))
                .body(data);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('users.read')")
    public UserDto getById(@PathVariable Long id) {
        return userService.getById(id);
    }

    @PostMapping
    @PreAuthorize("hasAuthority('users.create')")
    @ResponseStatus(HttpStatus.CREATED)
    public UserDto create(@Valid @RequestBody CreateUserRequest request) {
        return userService.createUser(request);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('users.update')")
    public UserDto update(@PathVariable Long id, @Valid @RequestBody UpdateUserRequest request) {
        return userService.update(id, request);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('users.delete')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id) {
        userService.delete(id);
    }

    @PostMapping("/{id}/unlock")
    @PreAuthorize("hasAuthority('users.unlock')")
    public UserDto unlock(@PathVariable Long id) {
        return userService.unlock(id);
    }

    @PutMapping("/{id}/roles")
    @PreAuthorize("hasAuthority('users.update')")
    public UserDto assignRoles(@PathVariable Long id, @RequestBody AssignRolesRequest request) {
        return userService.assignRoles(id, request.roleNames());
    }

    @PutMapping("/{id}/organization-units")
    @PreAuthorize("hasAuthority('users.update')")
    public UserDto assignOrganizationUnits(@PathVariable Long id, @RequestBody AssignOuRequest request) {
        return userService.assignOrganizationUnits(id, request.ouIds());
    }

    @PostMapping("/{id}/activate")
    @PreAuthorize("hasAuthority('users.update')")
    public UserDto activate(@PathVariable Long id) {
        return userService.activate(id);
    }

    @PostMapping("/{id}/deactivate")
    @PreAuthorize("hasAuthority('users.update')")
    public UserDto deactivate(@PathVariable Long id) {
        return userService.deactivate(id);
    }
}

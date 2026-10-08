package com.graphify.auth;

import com.graphify.api.Page;
import com.graphify.api.PagingResolver;
import com.graphify.common.exception.InvalidRequestException;
import java.net.URI;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Users and directory lookup for admins (spec §10.6 "Kullanıcılar", "LDAP" users search). */
@RestController
@RequestMapping("/api/v1/admin")
public class UserAdminController {

    public record Registration(String username, Role role) {
    }

    public record RoleChange(Role role) {
    }

    public record ActiveChange(Boolean active) {
    }

    public record DirectoryUserView(String username, String displayName, String email, Long appUserId) {
    }

    private final UserAdministration administration;
    private final AppUsers users;
    private final LdapDirectory directory;
    private final PagingResolver paging;

    public UserAdminController(UserAdministration administration, AppUsers users, LdapDirectory directory,
            PagingResolver paging) {
        this.administration = administration;
        this.users = users;
        this.directory = directory;
        this.paging = paging;
    }

    @GetMapping("/users")
    public Page<AppUser> list(@RequestParam(required = false) String q, @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return users.list(q, paging.resolve(page, size));
    }

    @PostMapping("/users")
    public ResponseEntity<AppUser> register(@RequestBody(required = false) Registration registration,
            Authentication authentication) {
        if (registration == null) {
            throw new InvalidRequestException("A body {username, role} is required");
        }
        AppUser created = administration.register(registration.username(), registration.role(),
                authentication.getName());
        return ResponseEntity.created(URI.create("/api/v1/admin/users/" + created.id())).body(created);
    }

    @PutMapping("/users/{id}/role")
    public AppUser changeRole(@PathVariable long id, @RequestBody(required = false) RoleChange change,
            Authentication authentication) {
        return administration.changeRole(id, change == null ? null : change.role(), authentication.getName());
    }

    @PutMapping("/users/{id}/active")
    public AppUser changeActive(@PathVariable long id, @RequestBody(required = false) ActiveChange change,
            Authentication authentication) {
        if (change == null || change.active() == null) {
            throw new InvalidRequestException("A body {active: true|false} is required");
        }
        return administration.changeActive(id, change.active(), authentication.getName());
    }

    @GetMapping("/ldap/users")
    public List<DirectoryUserView> searchDirectory(@RequestParam(required = false) String q) {
        return directory.search(q).stream().map(user -> new DirectoryUserView(user.username(), user.displayName(),
                user.email(), users.findByUsername(user.username()).map(AppUser::id).orElse(null))).toList();
    }
}

package com.graphify.auth;

import com.graphify.audit.AuditAction;
import com.graphify.audit.AuditLog;
import com.graphify.common.exception.ConflictException;
import com.graphify.common.exception.InvalidRequestException;
import com.graphify.common.exception.NotFoundException;
import java.util.List;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Admin changes to users (spec §7.3–7.4). At least one active ADMIN always remains (while LDAP is disabled, one that
 * is local), and the local admin is only deactivated while an active LDAP admin exists; both checks lock the active
 * admin rows, so concurrent changes are serialized.
 */
@Service
public class UserAdministration {

    private final AppUsers users;
    private final LocalAccounts accounts;
    private final LdapDirectory directory;
    private final LdapConfigRepository ldapConfig;
    private final AuditLog auditLog;
    private final TransactionTemplate transaction;

    public UserAdministration(AppUsers users, LocalAccounts accounts, LdapDirectory directory,
            LdapConfigRepository ldapConfig, AuditLog auditLog, PlatformTransactionManager transactionManager) {
        this.users = users;
        this.accounts = accounts;
        this.directory = directory;
        this.ldapConfig = ldapConfig;
        this.auditLog = auditLog;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    /** The directory lookup runs outside the database transaction; only the insert and audit are transactional. */
    public AppUser register(String username, Role role, String actor) {
        if (role == null) {
            throw new InvalidRequestException("role is required (ADMIN or USER)");
        }
        String name = AppUsers.normalize(username);
        if (name == null || name.isEmpty()) {
            throw new InvalidRequestException("username is required");
        }
        if (users.findByUsername(name).isPresent()) {
            throw new ConflictException("User " + name + " is already registered");
        }
        DirectoryUser entry = directory.find(name)
                .orElseThrow(() -> new NotFoundException("No directory user named " + name));
        try {
            return transaction.execute(status -> {
                if (users.findByUsername(entry.username()).isPresent()) {
                    throw new ConflictException("User " + entry.username() + " is already registered");
                }
                AppUser created = users.create(entry.username(), UserSource.LDAP, entry.displayName(), entry.email(),
                        role, actor);
                auditLog.record(actor, AuditAction.USER_REGISTERED, created.username(), role.name());
                return created;
            });
        } catch (DataIntegrityViolationException e) {
            throw new ConflictException("User " + entry.username() + " is already registered");
        }
    }

    @Transactional
    public AppUser changeRole(long userId, Role role, String actor) {
        if (role == null) {
            throw new InvalidRequestException("role is required (ADMIN or USER)");
        }
        List<AppUser> locked = users.lockAdminsAnd(userId);
        AppUser user = target(locked, userId);
        if (user.role() == role) {
            return user;
        }
        if (user.role() == Role.ADMIN && user.active()) {
            requireAnotherActiveAdmin(locked, user);
        }
        users.setRole(userId, role, actor);
        auditLog.record(actor, AuditAction.USER_ROLE_CHANGED, user.username(), user.role() + " -> " + role);
        return find(userId);
    }

    @Transactional
    public AppUser changeActive(long userId, boolean active, String actor) {
        List<AppUser> locked = users.lockAdminsAnd(userId);
        AppUser user = target(locked, userId);
        if (user.active() == active) {
            return user;
        }
        if (!active) {
            if (user.role() == Role.ADMIN) {
                requireAnotherActiveAdmin(locked, user);
            }
            // The bootstrap account stays guarded after a demotion, so demoting first is no way around the rule.
            if (user.source() == UserSource.LOCAL
                    && (user.role() == Role.ADMIN || BootstrapAdmin.USERNAME.equals(user.username()))) {
                requireAnotherDirectoryAdmin(locked, user);
            }
        }
        users.setActive(userId, active);
        if (user.source() == UserSource.LOCAL) {
            accounts.setEnabled(user.username(), active);
        }
        auditLog.record(actor, AuditAction.USER_ACTIVE_CHANGED, user.username(), active ? "activated" : "deactivated");
        return find(userId);
    }

    private static AppUser target(List<AppUser> locked, long userId) {
        return locked.stream().filter(candidate -> candidate.id() == userId).findFirst()
                .orElseThrow(() -> new NotFoundException("No user with id " + userId));
    }

    /**
     * While LDAP is disabled a directory admin cannot sign in, so only local admins count. The flag is read after the
     * admin rows are locked; disabling LDAP takes the same lock, so the two changes serialize.
     */
    private void requireAnotherActiveAdmin(List<AppUser> locked, AppUser leaving) {
        boolean ldapEnabled = ldapConfig.enabled();
        boolean another = locked.stream().anyMatch(admin -> admin.id() != leaving.id()
                && admin.role() == Role.ADMIN && admin.active()
                && (ldapEnabled || admin.source() == UserSource.LOCAL));
        if (!another) {
            throw new ConflictException(ldapEnabled ? "At least one active admin must remain"
                    : "While LDAP is disabled at least one active local admin must remain");
        }
    }

    private static void requireAnotherDirectoryAdmin(List<AppUser> locked, AppUser leaving) {
        boolean another = locked.stream().anyMatch(admin -> admin.id() != leaving.id()
                && admin.role() == Role.ADMIN && admin.active() && admin.source() == UserSource.LDAP);
        if (!another) {
            throw new ConflictException(
                    "The local admin can only be deactivated while another active LDAP admin exists");
        }
    }

    private AppUser find(long userId) {
        return users.find(userId).orElseThrow(() -> new NotFoundException("No user with id " + userId));
    }
}

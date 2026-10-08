package com.graphify.testsupport;

import com.graphify.auth.AppUser;
import com.graphify.auth.AppUsers;
import com.graphify.auth.LocalAccounts;
import com.graphify.auth.Role;
import com.graphify.auth.UserSource;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;

/** Users for authentication tests; the bootstrap 'admin' is normally kept. */
public final class AuthFixtures {

    private AuthFixtures() {
    }

    public static void deleteUsersExcept(JdbcTemplate jdbc, String... keep) {
        List<String> kept = List.of(keep);
        jdbc.queryForList("SELECT username FROM app_user", String.class).stream()
                .filter(username -> !kept.contains(username))
                .forEach(username -> {
                    jdbc.update("DELETE FROM local_account WHERE username = ?", username);
                    jdbc.update("DELETE FROM app_user WHERE username = ?", username);
                });
    }

    public static AppUser createLocal(AppUsers users, LocalAccounts accounts, String passwordHash, String username,
            Role role, boolean mustChange) {
        AppUser user = users.create(username, UserSource.LOCAL, username, null, role, "test");
        accounts.create(user.username(), passwordHash, mustChange);
        return user;
    }
}

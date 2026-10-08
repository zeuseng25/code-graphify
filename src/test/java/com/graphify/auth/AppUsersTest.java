package com.graphify.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.graphify.OracleIntegrationTest;
import com.graphify.api.Paging;
import com.graphify.testsupport.AuthFixtures;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

class AppUsersTest extends OracleIntegrationTest {

    @Autowired
    AppUsers users;

    @BeforeEach
    @AfterEach
    void clean() {
        AuthFixtures.deleteUsersExcept(jdbc, "admin");
    }

    @Test
    void storesNormalizedUniqueUsernames() {
        AppUser created = users.create("  Ayse.Yilmaz ", UserSource.LDAP, "Ayşe Yılmaz", "ayse@corp.com", Role.USER,
                "ldap-login");

        assertThat(created.username()).isEqualTo("ayse.yilmaz");
        assertThat(created.displayName()).isEqualTo("Ayşe Yılmaz");
        assertThat(created.active()).isTrue();
        assertThat(created.roleGrantedAt()).isNotNull();
        assertThat(users.findByUsername("AYSE.YILMAZ")).contains(created);
        assertThatThrownBy(() -> users.create("ayse.yilmaz", UserSource.LOCAL, null, null, Role.USER, "t"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(AppUsers.normalize(null)).isNull();
    }

    @Test
    void changesRoleActivityAndLoginAttributes() {
        AppUser user = users.create("mehmet", UserSource.LDAP, "M", null, Role.USER, "ldap-login");

        users.setRole(user.id(), Role.ADMIN, "boss");
        users.setActive(user.id(), false);
        users.recordLogin(user.id(), "Mehmet Kaya", null);

        AppUser changed = users.find(user.id()).orElseThrow();
        assertThat(changed.role()).isEqualTo(Role.ADMIN);
        assertThat(changed.roleGrantedBy()).isEqualTo("boss");
        assertThat(changed.active()).isFalse();
        assertThat(changed.displayName()).isEqualTo("Mehmet Kaya");
        assertThat(changed.email()).isNull();
        assertThat(changed.lastLoginAt()).isNotNull();
    }

    @Test
    void listsUsersFilteredByNameOrDisplayName() {
        users.create("ayse", UserSource.LDAP, "Ayşe Yılmaz", null, Role.USER, "t");
        users.create("mehmet", UserSource.LDAP, "Mehmet Kaya", null, Role.USER, "t");

        assertThat(users.list("YIL", new Paging(0, 10)).items()).extracting(AppUser::username).containsExactly("ayse");
        assertThat(users.list(null, new Paging(0, 10)).items()).extracting(AppUser::username)
                .containsExactly("admin", "ayse", "mehmet");
    }
}

package com.graphify.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.graphify.OracleIntegrationTest;
import com.graphify.common.crypto.SecretCipher;
import com.graphify.common.exception.ConflictException;
import com.graphify.common.exception.InvalidRequestException;
import com.graphify.common.exception.NotFoundException;
import com.graphify.testsupport.AuthFixtures;
import com.graphify.testsupport.TestLdap;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

class UserAdministrationTest extends OracleIntegrationTest {

    @Autowired
    UserAdministration administration;

    @Autowired
    AppUsers users;

    @Autowired
    LocalAccounts accounts;

    @Autowired
    SecretCipher cipher;

    @Autowired
    PlatformTransactionManager transactionManager;

    @Autowired
    LdapAdministration ldap;

    private long bootstrapId;

    @BeforeEach
    void setUp() {
        AuthFixtures.deleteUsersExcept(jdbc, "admin");
        TestLdap.configure(jdbc, cipher);
        bootstrapId = users.findByUsername("admin").orElseThrow().id();
    }

    @AfterEach
    void tearDown() {
        users.setRole(bootstrapId, Role.ADMIN, "test");
        users.setActive(bootstrapId, true);
        accounts.setEnabled("admin", true);
        TestLdap.disable(jdbc);
        AuthFixtures.deleteUsersExcept(jdbc, "admin");
    }

    @Test
    void registersADirectoryUserWithARole() {
        AppUser ayse = administration.register("Ayse", Role.ADMIN, "admin");

        assertThat(ayse.username()).isEqualTo("ayse");
        assertThat(ayse.source()).isEqualTo(UserSource.LDAP);
        assertThat(ayse.displayName()).isEqualTo("Ayşe Yılmaz");
        assertThat(ayse.role()).isEqualTo(Role.ADMIN);
        assertThat(ayse.roleGrantedBy()).isEqualTo("admin");
        assertThatThrownBy(() -> administration.register("ayse", Role.USER, "admin"))
                .isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> administration.register("ghost", Role.USER, "admin"))
                .isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> administration.register("mehmet", null, "admin"))
                .isInstanceOf(InvalidRequestException.class);
    }

    @Test
    void theLastActiveAdminCannotBeDemotedOrDeactivated() {
        assertThatThrownBy(() -> administration.changeRole(bootstrapId, Role.USER, "admin"))
                .isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> administration.changeActive(bootstrapId, false, "admin"))
                .isInstanceOf(ConflictException.class);

        AppUser ayse = administration.register("ayse", Role.ADMIN, "admin");

        assertThat(administration.changeRole(bootstrapId, Role.USER, "ayse").role()).isEqualTo(Role.USER);
        assertThatThrownBy(() -> administration.changeRole(ayse.id(), Role.USER, "ayse"))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void theLocalAdminNeedsAnActiveDirectoryAdminToBeDeactivated() {
        long localOps = AuthFixtures.createLocal(users, accounts, "$2a$10$x", "ops", Role.ADMIN, false).id();

        assertThatThrownBy(() -> administration.changeActive(bootstrapId, false, "ops"))
                .isInstanceOf(ConflictException.class).hasMessageContaining("LDAP");

        administration.register("mehmet", Role.ADMIN, "admin");
        AppUser deactivated = administration.changeActive(bootstrapId, false, "mehmet");

        assertThat(deactivated.active()).isFalse();
        assertThat(accounts.find("admin").orElseThrow().enabled()).isFalse();
        assertThat(administration.changeActive(localOps, false, "mehmet").active()).isFalse();
    }

    @Test
    void concurrentDemotionsNeverRemoveTheLastAdmin() throws Exception {
        long ayse = administration.register("ayse", Role.ADMIN, "admin").id();
        users.setRole(bootstrapId, Role.USER, "test");
        long mehmet = administration.register("mehmet", Role.ADMIN, "admin").id();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Boolean>> results = new ArrayList<>();
            for (long id : List.of(ayse, mehmet)) {
                Callable<Boolean> demote = () -> {
                    start.await();
                    try {
                        administration.changeRole(id, Role.USER, "race");
                        return true;
                    } catch (ConflictException e) {
                        return false;
                    }
                };
                results.add(pool.submit(demote));
            }
            start.countDown();
            int demoted = 0;
            for (Future<Boolean> result : results) {
                if (result.get()) {
                    demoted++;
                }
            }
            assertThat(demoted).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM app_user WHERE role = 'ADMIN' AND active = 1",
                    Integer.class)).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void theLastAdminDecisionUsesTheStateLockedAfterWaiting() throws Exception {
        long ayse = administration.register("ayse", Role.USER, "admin").id();
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<AppUser> deactivation = new TransactionTemplate(transactionManager).execute(status -> {
                users.lockAdminsAnd(ayse);
                // Inside this open transaction ayse becomes the only active admin.
                users.setRole(ayse, Role.ADMIN, "t");
                users.setRole(bootstrapId, Role.USER, "t");
                Future<AppUser> racing = pool.submit(() -> administration.changeActive(ayse, false, "race"));
                try {
                    for (int i = 0; i < 6; i++) {
                        Thread.sleep(100);
                        assertThat(racing).isNotDone();
                    }
                } catch (InterruptedException e) {
                    throw new AssertionError(e);
                }
                return racing;
            });

            assertThatThrownBy(() -> deactivation.get(10, TimeUnit.SECONDS))
                    .hasCauseInstanceOf(ConflictException.class);
            assertThat(jdbc.queryForList("SELECT username FROM app_user WHERE role = 'ADMIN' AND active = 1",
                    String.class)).containsExactly("ayse");
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void demotingTheBootstrapAccountDoesNotLiftTheDirectoryAdminRule() {
        AuthFixtures.createLocal(users, accounts, "$2a$10$x", "ops", Role.ADMIN, false);

        assertThat(administration.changeRole(bootstrapId, Role.USER, "ops").role()).isEqualTo(Role.USER);
        assertThatThrownBy(() -> administration.changeActive(bootstrapId, false, "ops"))
                .isInstanceOf(ConflictException.class).hasMessageContaining("LDAP");
    }

    @Test
    void whileLdapIsDisabledOnlyLocalAdminsCountAsAnotherActiveAdmin() {
        long mehmet = administration.register("mehmet", Role.ADMIN, "admin").id();
        // allowed: the bootstrap admin is an active local admin
        ldap.update(new LdapConfigUpdate(false, null, null, null, "(uid={0})", "(uid={0}*)", "uid", "cn", "mail", null,
                ""), "admin");

        assertThatThrownBy(() -> administration.changeActive(bootstrapId, false, "admin"))
                .isInstanceOf(ConflictException.class).hasMessageContaining("local admin");
        assertThatThrownBy(() -> administration.changeRole(bootstrapId, Role.USER, "admin"))
                .isInstanceOf(ConflictException.class).hasMessageContaining("local admin");
        assertThat(users.find(bootstrapId).orElseThrow()).satisfies(admin -> {
            assertThat(admin.role()).isEqualTo(Role.ADMIN);
            assertThat(admin.active()).isTrue();
        });
        assertThat(administration.changeRole(mehmet, Role.USER, "admin").role()).isEqualTo(Role.USER);

        long ops = AuthFixtures.createLocal(users, accounts, "$2a$10$x", "ops", Role.ADMIN, false).id();
        assertThat(administration.changeRole(bootstrapId, Role.USER, "ops").role()).isEqualTo(Role.USER);
        assertThatThrownBy(() -> administration.changeActive(ops, false, "ops"))
                .isInstanceOf(ConflictException.class).hasMessageContaining("local admin");
    }

    @Test
    void withLdapEnabledADirectoryAdminStillCountsAsAnotherActiveAdmin() {
        administration.register("mehmet", Role.ADMIN, "admin");

        assertThat(administration.changeRole(bootstrapId, Role.USER, "mehmet").role()).isEqualTo(Role.USER);
        assertThat(administration.changeActive(bootstrapId, false, "mehmet").active()).isFalse();
    }
}

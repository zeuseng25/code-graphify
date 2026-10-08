package com.graphify.auth;

import java.util.Map;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** LDAP connection management (spec §10.6 "LDAP"). */
@RestController
@RequestMapping("/api/v1/admin/ldap")
public class LdapAdminController {

    private final LdapAdministration administration;

    public LdapAdminController(LdapAdministration administration) {
        this.administration = administration;
    }

    @GetMapping
    public LdapConfigView get() {
        return administration.get();
    }

    @PutMapping
    public LdapConfigView update(@RequestBody(required = false) LdapConfigUpdate update,
            Authentication authentication) {
        return administration.update(update, authentication.getName());
    }

    /** Tests the submitted configuration (stored bind password when none is given), or the stored one without a body. */
    @PostMapping("/test")
    public Map<String, Boolean> test(@RequestBody(required = false) LdapConfigUpdate candidate) {
        administration.test(candidate);
        return Map.of("ok", true);
    }
}

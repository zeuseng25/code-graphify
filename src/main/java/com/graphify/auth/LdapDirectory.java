package com.graphify.auth;

import com.graphify.common.exception.ConflictException;
import com.graphify.common.exception.ExternalSystemException;
import com.graphify.scm.UrlMasking;
import com.graphify.settings.AppSettings;
import com.graphify.settings.SettingKeys;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javax.naming.NamingException;
import javax.naming.directory.Attribute;
import javax.naming.directory.Attributes;
import javax.naming.directory.SearchControls;
import org.springframework.ldap.core.AttributesMapper;
import org.springframework.ldap.core.DirContextOperations;
import org.springframework.ldap.core.LdapTemplate;
import org.springframework.ldap.core.support.LdapContextSource;
import org.springframework.ldap.support.LdapEncoder;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.ldap.authentication.BindAuthenticator;
import org.springframework.security.ldap.search.FilterBasedLdapUserSearch;
import org.springframework.stereotype.Component;

/**
 * Talks to the LDAP/Active Directory configured in ldap_config (spec §7.1). Every call builds its context from the
 * current row, so a configuration change applies at once. User input is always filter-encoded.
 */
@Component
public class LdapDirectory {

    /** JNDI LDAP provider properties for the connect and read timeouts, in milliseconds. */
    private static final String CONNECT_TIMEOUT = "com.sun.jndi.ldap.connect.timeout";
    private static final String READ_TIMEOUT = "com.sun.jndi.ldap.read.timeout";

    /** Placeholder for the (encoded) user input in the configured filters. */
    private static final String PLACEHOLDER = "{0}";

    private final LdapConfigRepository config;
    private final AppSettings settings;

    public LdapDirectory(LdapConfigRepository config, AppSettings settings) {
        this.config = config;
        this.settings = settings;
    }

    public boolean enabled() {
        return config.enabled();
    }

    public Optional<DirectoryUser> authenticate(String username, String password) {
        LdapSettings ldap = requireEnabled();
        if (password == null || password.isEmpty()) {
            return Optional.empty(); // an empty password would be an anonymous bind
        }
        LdapContextSource source = contextSource(ldap);
        BindAuthenticator authenticator = new BindAuthenticator(source);
        authenticator.setUserSearch(new FilterBasedLdapUserSearch(searchBase(ldap), ldap.userSearchFilter(), source));
        try {
            DirContextOperations entry = authenticator.authenticate(
                    UsernamePasswordAuthenticationToken.unauthenticated(username, password));
            String found = entry.getStringAttribute(ldap.usernameAttr());
            return Optional.of(new DirectoryUser(AppUsers.normalize(found == null ? username : found),
                    entry.getStringAttribute(ldap.displayNameAttr()), entry.getStringAttribute(ldap.emailAttr())));
        } catch (BadCredentialsException | UsernameNotFoundException e) {
            return Optional.empty();
        } catch (RuntimeException e) {
            throw unusable(e);
        }
    }

    public Optional<DirectoryUser> find(String username) {
        LdapSettings ldap = requireEnabled();
        if (username == null || username.isBlank()) {
            return Optional.empty();
        }
        String filter = ldap.userSearchFilter()
                .replace(PLACEHOLDER, LdapEncoder.filterEncode(AppUsers.normalize(username)));
        return query(ldap, filter, 1).stream().findFirst();
    }

    public List<DirectoryUser> search(String query) {
        LdapSettings ldap = requireEnabled();
        if (query == null || query.isBlank()) {
            return List.of();
        }
        String filter = ldap.userQueryFilter().replace(PLACEHOLDER, LdapEncoder.filterEncode(query.strip()));
        return query(ldap, filter, settings.getInt(SettingKeys.AUTH_LDAP_SEARCH_MAX_RESULTS));
    }

    /** Binds with the given settings (the bind account, or anonymously); throws when the directory is unusable. */
    public void test(LdapSettings ldap) {
        try {
            contextSource(ldap).getReadOnlyContext().close();
        } catch (NamingException | RuntimeException e) {
            throw unusable(e);
        }
    }

    private List<DirectoryUser> query(LdapSettings ldap, String filter, int limit) {
        LdapTemplate template = new LdapTemplate(contextSource(ldap));
        template.setIgnoreSizeLimitExceededException(true);
        SearchControls controls = new SearchControls();
        controls.setSearchScope(SearchControls.SUBTREE_SCOPE);
        controls.setCountLimit(limit);
        controls.setTimeLimit((int) settings.getDuration(SettingKeys.AUTH_LDAP_READ_TIMEOUT).toMillis());
        controls.setReturningAttributes(new String[] {ldap.usernameAttr(), ldap.displayNameAttr(), ldap.emailAttr()});
        try {
            return template.search(searchBase(ldap), filter, controls, (AttributesMapper<DirectoryUser>) attributes ->
                            new DirectoryUser(AppUsers.normalize(text(attributes, ldap.usernameAttr())),
                                    text(attributes, ldap.displayNameAttr()), text(attributes, ldap.emailAttr())))
                    .stream().filter(user -> user.username() != null)
                    .sorted(Comparator.comparing(DirectoryUser::username)).toList();
        } catch (RuntimeException e) {
            throw unusable(e);
        }
    }

    private LdapSettings requireEnabled() {
        LdapSettings ldap = config.load();
        if (!ldap.enabled()) {
            throw new ConflictException("LDAP is not configured");
        }
        return ldap;
    }

    private LdapContextSource contextSource(LdapSettings ldap) {
        LdapContextSource source = new LdapContextSource();
        source.setUrl(ldap.url());
        source.setBase(ldap.baseDn());
        if (ldap.bindDn() == null || ldap.bindDn().isBlank()) {
            source.setAnonymousReadOnly(true);
        } else {
            source.setUserDn(ldap.bindDn());
            source.setPassword(ldap.bindPassword() == null ? "" : ldap.bindPassword());
        }
        source.setPooled(false);
        source.setBaseEnvironmentProperties(Map.of(
                CONNECT_TIMEOUT, Long.toString(settings.getDuration(SettingKeys.AUTH_LDAP_CONNECT_TIMEOUT).toMillis()),
                READ_TIMEOUT, Long.toString(settings.getDuration(SettingKeys.AUTH_LDAP_READ_TIMEOUT).toMillis())));
        source.afterPropertiesSet();
        return source;
    }

    private static String searchBase(LdapSettings ldap) {
        return ldap.userSearchBase() == null ? "" : ldap.userSearchBase();
    }

    private static String text(Attributes attributes, String name) throws NamingException {
        Attribute attribute = attributes.get(name);
        return attribute == null || attribute.get() == null ? null : attribute.get().toString();
    }

    /** Never includes credentials: the message is the exception type plus its masked message. */
    private static ExternalSystemException unusable(Exception e) {
        return new ExternalSystemException("The directory could not be used: " + e.getClass().getSimpleName()
                + (e.getMessage() == null ? "" : ": " + UrlMasking.mask(e.getMessage())));
    }
}

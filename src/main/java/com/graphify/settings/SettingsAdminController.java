package com.graphify.settings;

import com.graphify.common.exception.InvalidRequestException;
import java.util.Comparator;
import java.util.List;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Settings for admins (spec §10.6 "Ayarlar"); type checks and audit live in AppSettings, server-side checks in
 * SettingsAdministration.
 */
@RestController
@RequestMapping("/api/v1/admin/settings")
public class SettingsAdminController {

    public record ValueChange(String value) {
    }

    private final AppSettings settings;
    private final SettingsAdministration administration;

    public SettingsAdminController(AppSettings settings, SettingsAdministration administration) {
        this.settings = settings;
        this.administration = administration;
    }

    @GetMapping
    public List<Setting> list() {
        return settings.all().stream().sorted(Comparator.comparing(Setting::key)).toList();
    }

    @PutMapping("/{key}")
    public Setting update(@PathVariable String key, @RequestBody(required = false) ValueChange change,
            Authentication authentication) {
        if (change == null || change.value() == null) {
            throw new InvalidRequestException("A body {value} is required");
        }
        return administration.update(key, change.value(), authentication.getName());
    }
}

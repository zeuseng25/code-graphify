package com.graphify.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.graphify.OracleIntegrationTest;
import com.graphify.auth.Role;
import com.graphify.settings.AppSettings;
import com.graphify.settings.SettingKeys;
import com.graphify.testsupport.SettingsOverride;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class UiConfigApiTest extends OracleIntegrationTest {

    @Autowired
    AppSettings settings;

    @Test
    void givesSignedInUsersTheUiLimitsFromSettings() {
        SettingsOverride overrides = new SettingsOverride(settings);
        overrides.set(SettingKeys.UI_POLL_INTERVAL, "PT7S");
        try {
            assertThat(as("viewer", Role.USER).get().uri("/api/v1/ui-config")).hasStatusOk().bodyJson()
                    .satisfies(json -> {
                        assertThat(json).extractingPath("$.pollIntervalMillis").isEqualTo(7000);
                        assertThat(json).extractingPath("$.pageDefaultSize")
                                .isEqualTo(settings.getInt(SettingKeys.API_PAGE_DEFAULT_SIZE));
                        assertThat(json).extractingPath("$.graphMaxNodes")
                                .isEqualTo(settings.getInt(SettingKeys.GRAPH_MAX_NODES));
                        assertThat(json).extractingPath("$.impactMaxDepth")
                                .isEqualTo(settings.getInt(SettingKeys.IMPACT_MAX_DEPTH));
                    });
        } finally {
            overrides.restore();
        }
        assertThat(anonymous().get().uri("/api/v1/ui-config")).hasStatus(401);
        assertThat(jdbc.queryForObject("SELECT setting_value FROM app_setting WHERE setting_key = 'ui.poll_interval'",
                String.class)).isEqualTo("PT5S");
    }
}

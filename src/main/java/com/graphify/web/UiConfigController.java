package com.graphify.web;

import com.graphify.settings.AppSettings;
import com.graphify.settings.SettingKeys;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** GET /api/v1/ui-config: no secret and no admin-only setting is ever added here. */
@RestController
public class UiConfigController {

    private final AppSettings settings;

    public UiConfigController(AppSettings settings) {
        this.settings = settings;
    }

    @GetMapping("/api/v1/ui-config")
    public UiConfig uiConfig() {
        return new UiConfig(settings.getInt(SettingKeys.API_PAGE_DEFAULT_SIZE),
                settings.getInt(SettingKeys.API_PAGE_MAX_SIZE), settings.getInt(SettingKeys.GRAPH_MAX_NODES),
                settings.getInt(SettingKeys.IMPACT_DEFAULT_DEPTH), settings.getInt(SettingKeys.IMPACT_MAX_DEPTH),
                settings.getDuration(SettingKeys.UI_POLL_INTERVAL).toMillis());
    }
}

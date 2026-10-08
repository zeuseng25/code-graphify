package com.graphify.settings;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class SettingsAdministrationTest {

    private record Fixed(String key) implements SettingValidator {
        @Override
        public Set<String> keys() {
            return Set.of(key);
        }

        @Override
        public Optional<String> problem(String key, String value) {
            return Optional.empty();
        }
    }

    @Test
    void twoValidatorsForOneKeyFailAtStartup() {
        assertThatThrownBy(() -> new SettingsAdministration(null,
                List.of(new Fixed("index.workspace_dir"), new Fixed("index.workspace_dir"))))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("index.workspace_dir");
    }
}

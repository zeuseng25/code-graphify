package com.graphify.settings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class SettingTypeTest {

    @Test
    void parsesEachTypesValidForm() {
        assertThat(SettingType.INT.parse(" 42 ")).isEqualTo(42);
        assertThat(SettingType.STRING.parse("/data/repos")).isEqualTo("/data/repos");
        assertThat(SettingType.BOOL.parse("TRUE")).isEqualTo(true);
        assertThat(SettingType.CRON.parse("0 0 2 * * *")).isEqualTo("0 0 2 * * *");
        assertThat(SettingType.LIST.parse(" a, b ,,c ")).isEqualTo(List.of("a", "b", "c"));
        assertThat(SettingType.LIST.parse("")).isEqualTo(List.of());
        assertThat(SettingType.DURATION.parse("PT10M")).isEqualTo(Duration.ofMinutes(10));
    }

    @Test
    void rejectsInvalidValues() {
        assertThatIllegalArgumentException().isThrownBy(() -> SettingType.INT.parse("four"));
        assertThatIllegalArgumentException().isThrownBy(() -> SettingType.STRING.parse("  "));
        assertThatIllegalArgumentException().isThrownBy(() -> SettingType.BOOL.parse("yes"));
        assertThatIllegalArgumentException().isThrownBy(() -> SettingType.CRON.parse("0 2 * * *"));
        assertThatIllegalArgumentException().isThrownBy(() -> SettingType.DURATION.parse("10 minutes"));
        assertThatIllegalArgumentException().isThrownBy(() -> SettingType.DURATION.parse("PT0S"));
        assertThatIllegalArgumentException().isThrownBy(() -> SettingType.DURATION.parse("-PT1M"));
    }
}

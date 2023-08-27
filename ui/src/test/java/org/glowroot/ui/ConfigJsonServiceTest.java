package org.glowroot.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ConfigJsonServiceTest {

    @Test
    void sanitizeUiDefaultsConfigDto_xss() {
        ConfigJsonService.SanitizationService sanitizationService = new ConfigJsonService.SanitizationService();

        // given
        ConfigJsonService.UiDefaultsConfigDto configDto = ImmutableUiDefaultsConfigDto.builder()
                .defaultTransactionType("<script>alert('xss')</script>Some Text")
                .version("version")
                .build();
        // when
        ConfigJsonService.UiDefaultsConfigDto ret = sanitizationService.sanitize(configDto);

        // then
        assertEquals("Some Text", ret.defaultTransactionType());
    }

    @Test
    void sanitizeUiDefaultsConfigDto_nominalCase() {
        ConfigJsonService.SanitizationService sanitizationService = new ConfigJsonService.SanitizationService();

        // given
        ConfigJsonService.UiDefaultsConfigDto configDto = ImmutableUiDefaultsConfigDto.builder()
                .defaultTransactionType("Web")
                .version("version")
                .build();
        // when
        ConfigJsonService.UiDefaultsConfigDto ret = sanitizationService.sanitize(configDto);

        // then
        assertEquals("Web", ret.defaultTransactionType());
    }
}
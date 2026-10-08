package com.graphify.web;

import io.swagger.v3.oas.annotations.media.Schema;

/** The settings the web UI needs to behave, readable by every signed-in user (web UI spec §3.6). */
public record UiConfig(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int pageDefaultSize,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int pageMaxSize,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int graphMaxNodes,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int impactDefaultDepth,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int impactMaxDepth,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long pollIntervalMillis) {
}

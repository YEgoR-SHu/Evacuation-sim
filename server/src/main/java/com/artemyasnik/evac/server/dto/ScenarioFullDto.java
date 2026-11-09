package com.artemyasnik.evac.server.dto;

import java.util.List;

/**
 * Полное представление сценария с метками.
 * Используется в {@code GET /api/buildings/{bid}/scenarios/{sid}}.
 */
public record ScenarioFullDto(
        long id,
        String name,
        boolean isDefault,
        ParamsDto params,
        FireDto fire,
        List<MarkDto> marks,
        String createdAt,
        String updatedAt
) {
}
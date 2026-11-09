package com.artemyasnik.evac.server.dto;

/**
 * Полное представление сценария. Без меток — они в Фазе 7'.
 */
public record ScenarioDto(
        long id,
        String name,
        boolean isDefault,
        ParamsDto params,
        FireDto fire,
        String createdAt,
        String updatedAt
) {
}
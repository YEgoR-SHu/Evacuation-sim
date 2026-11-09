package com.artemyasnik.evac.server.dto;

/**
 * Краткая информация о сценарии для списка внутри BuildingDto.
 */
public record ScenarioSummaryDto(
        long id,
        String name,
        boolean isDefault,
        String createdAt,
        String updatedAt
) {
}
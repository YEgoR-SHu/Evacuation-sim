package com.artemyasnik.evac.server.dto;

import java.util.List;

public record BuildingDto(
        long id,
        String name,
        String description,
        List<FloorDto> floors,
        List<ScenarioSummaryDto> scenarios,
        String createdAt,
        String updatedAt
) {
}
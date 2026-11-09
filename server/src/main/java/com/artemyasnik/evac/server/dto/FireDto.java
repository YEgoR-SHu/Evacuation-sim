package com.artemyasnik.evac.server.dto;

/**
 * Очаг пожара. Хранится в JSON-колонке scenarios.fire_json (или null).
 */
public record FireDto(
        Long floorId,
        Double x,
        Double y
) {
}
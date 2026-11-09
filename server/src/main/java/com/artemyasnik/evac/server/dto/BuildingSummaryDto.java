package com.artemyasnik.evac.server.dto;

public record BuildingSummaryDto(
        long id,
        String name,
        String description,
        int floorCount,
        int scenarioCount,
        String createdAt,
        String updatedAt
) {
}
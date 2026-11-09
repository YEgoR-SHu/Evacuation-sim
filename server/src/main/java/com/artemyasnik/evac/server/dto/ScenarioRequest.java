package com.artemyasnik.evac.server.dto;

/**
 * Тело POST/PUT для сценария.
 */
public record ScenarioRequest(
        String name,
        ParamsDto params,
        FireDto fire
) {
}
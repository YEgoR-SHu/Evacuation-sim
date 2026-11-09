package com.artemyasnik.evac.server.dto;

/**
 * Тело POST/PUT для метки.
 * Для stair/exit width обязателен; для spawn — должен быть null.
 */
public record MarkRequest(
        Long floorId,
        String kind,
        Double x,
        Double y,
        Double width
) {
}
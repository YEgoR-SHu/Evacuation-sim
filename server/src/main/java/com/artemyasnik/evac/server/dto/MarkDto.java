package com.artemyasnik.evac.server.dto;

/**
 * Метка сценария: лестница (stair), выход (exit) или точка спавна (spawn).
 * width обязателен для stair/exit, у spawn — null.
 */
public record MarkDto(
        long id,
        long floorId,
        String kind,
        double x,
        double y,
        Double width
) {
}
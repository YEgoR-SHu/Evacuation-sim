package com.artemyasnik.evac.server.dto;

public record FloorDto(
        long id,
        String name,
        int level,
        String svg,
        double[] vb
) {
}
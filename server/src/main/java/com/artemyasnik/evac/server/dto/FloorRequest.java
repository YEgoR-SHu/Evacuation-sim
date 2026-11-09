package com.artemyasnik.evac.server.dto;

public record FloorRequest(
        String name,
        Integer level,
        String svg,
        double[] vb
) {
}
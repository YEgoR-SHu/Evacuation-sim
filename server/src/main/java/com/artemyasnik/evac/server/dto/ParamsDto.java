package com.artemyasnik.evac.server.dto;

/**
 * Параметры симуляции. Хранятся в JSON-колонке scenarios.params_json.
 */
public record ParamsDto(
        Integer people,
        Double speed,
        Double fireRate,
        Double scale,
        Double stairTime,
        Double exitFlow,
        Double stairFlow
) {

    public static ParamsDto defaultParams() {
        return new ParamsDto(40, 1.3, 0.4, 20.0, 12.0, 1.3, 1.0);
    }

    /**
     * Заполняет null-поля значениями по умолчанию.
     */
    public ParamsDto withDefaults() {
        ParamsDto d = defaultParams();
        return new ParamsDto(
                people    != null ? people    : d.people,
                speed     != null ? speed     : d.speed,
                fireRate  != null ? fireRate  : d.fireRate,
                scale     != null ? scale     : d.scale,
                stairTime != null ? stairTime : d.stairTime,
                exitFlow  != null ? exitFlow  : d.exitFlow,
                stairFlow != null ? stairFlow : d.stairFlow
        );
    }
}
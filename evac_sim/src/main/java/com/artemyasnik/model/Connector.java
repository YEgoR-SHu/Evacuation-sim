package com.artemyasnik.model;

import java.util.List;
import java.util.Map;

/**
 * Общая база для связей между узлами плана (дверь, аварийный выход, лестница).
 * connectsIds — список id узлов, которые соединяет элемент (обычно два:
 * "откуда" и "куда"; для выхода наружу используется специальный id "OUTSIDE").
 */
public abstract class Connector extends BuildingElement {

    private final List<String> connectsIds;
    private final double throughputPersonsPerSec; // пропускная способность, чел/с
    private final Double widthM; // опционально, ширина проёма/марша

    protected Connector(String id, FloorElementType type, List<Point2D> geometry,
                        Map<String, String> rawAttributes, List<String> connectsIds,
                        double throughputPersonsPerSec, Double widthM) {
        super(id, type, geometry, rawAttributes);
        if (connectsIds == null || connectsIds.size() < 2) {
            throw new IllegalArgumentException(
                    "Connector '" + id + "' должен иметь data-connects с двумя id (через запятую)");
        }
        this.connectsIds = List.copyOf(connectsIds);
        this.throughputPersonsPerSec = throughputPersonsPerSec;
        this.widthM = widthM;
    }

    public List<String> getConnectsIds() {
        return connectsIds;
    }

    public double getThroughput() {
        return throughputPersonsPerSec;
    }

    public Double getWidthM() {
        return widthM;
    }
}
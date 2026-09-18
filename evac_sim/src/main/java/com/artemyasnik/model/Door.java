package com.artemyasnik.model;

import java.util.List;
import java.util.Map;

public class Door extends Connector {

    public Door(String id, List<Point2D> geometry, Map<String, String> rawAttributes,
                List<String> connectsIds, double throughputPersonsPerSec, Double widthM) {
        super(id, FloorElementType.DOOR, geometry, rawAttributes, connectsIds,
                throughputPersonsPerSec, widthM);
    }
}
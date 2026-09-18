package com.artemyasnik.model;

import java.util.List;
import java.util.Map;

public class EmergencyExit extends Connector {

    public static final String OUTSIDE_NODE_ID = "OUTSIDE";

    public EmergencyExit(String id, List<Point2D> geometry, Map<String, String> rawAttributes,
                         List<String> connectsIds, double throughputPersonsPerSec, Double widthM) {
        super(id, FloorElementType.EXIT, geometry, rawAttributes, connectsIds,
                throughputPersonsPerSec, widthM);
    }
}
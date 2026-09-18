package com.artemyasnik.model;

import java.util.List;
import java.util.Map;

public class Room extends BuildingElement {

    private final int capacity;     // максимальная вместимость, чел.
    private final double areaSqM;   // площадь, м²
    private final String name;      // опционально, для читаемости

    public Room(String id, List<Point2D> geometry, Map<String, String> rawAttributes,
                int capacity, double areaSqM, String name) {
        super(id, FloorElementType.ROOM, geometry, rawAttributes);
        this.capacity = capacity;
        this.areaSqM = areaSqM;
        this.name = name;
    }

    public int getCapacity() {
        return capacity;
    }

    public double getAreaSqM() {
        return areaSqM;
    }

    public String getName() {
        return name;
    }
}
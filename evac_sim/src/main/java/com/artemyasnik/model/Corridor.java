package com.artemyasnik.model;

import java.util.List;
import java.util.Map;

public class Corridor extends BuildingElement {

    private final int capacity;   // вместимость (может использоваться как ограничение по площади)
    private final double widthM;  // ширина, м — ключевой параметр для пропускной способности

    public Corridor(String id, List<Point2D> geometry, Map<String, String> rawAttributes,
                    int capacity, double widthM) {
        super(id, FloorElementType.CORRIDOR, geometry, rawAttributes);
        this.capacity = capacity;
        this.widthM = widthM;
    }

    public int getCapacity() {
        return capacity;
    }

    public double getWidthM() {
        return widthM;
    }
}
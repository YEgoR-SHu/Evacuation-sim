package com.artemyasnik.model;

import java.util.LinkedHashMap;
import java.util.Map;

/** Здание = набор этажей, распознанных по data-floor в каждом SVG. */
public class Building {

    private final Map<Integer, FloorPlan> floors = new LinkedHashMap<>();

    public void addFloor(FloorPlan floor) {
        floors.put(floor.getFloorNumber(), floor);
    }

    public FloorPlan getFloor(int number) {
        return floors.get(number);
    }

    public Map<Integer, FloorPlan> getFloors() {
        return floors;
    }
}
package com.artemyasnik.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class FloorPlan {

    private final int floorNumber;
    private final double scaleMetersPerUnit; // сколько метров в одной единице SVG

    private final Map<String, Room> rooms = new LinkedHashMap<>();
    private final Map<String, Corridor> corridors = new LinkedHashMap<>();
    private final List<Door> doors = new ArrayList<>();
    private final List<EmergencyExit> exits = new ArrayList<>();
    private final List<Stairs> stairs = new ArrayList<>();

    public FloorPlan(int floorNumber, double scaleMetersPerUnit) {
        this.floorNumber = floorNumber;
        this.scaleMetersPerUnit = scaleMetersPerUnit;
    }

    public int getFloorNumber() {
        return floorNumber;
    }

    public double getScaleMetersPerUnit() {
        return scaleMetersPerUnit;
    }

    public void addRoom(Room r) {
        rooms.put(r.getId(), r);
    }

    public void addCorridor(Corridor c) {
        corridors.put(c.getId(), c);
    }

    public void addDoor(Door d) {
        doors.add(d);
    }

    public void addExit(EmergencyExit e) {
        exits.add(e);
    }

    public void addStairs(Stairs s) {
        stairs.add(s);
    }

    public Map<String, Room> getRooms() {
        return rooms;
    }

    public Map<String, Corridor> getCorridors() {
        return corridors;
    }

    public List<Door> getDoors() {
        return doors;
    }

    public List<EmergencyExit> getExits() {
        return exits;
    }

    public List<Stairs> getStairs() {
        return stairs;
    }

    /** Ищет узел (комнату или коридор) по id — для сборки графа и валидации. */
    public Optional<BuildingElement> findNode(String id) {
        if (rooms.containsKey(id)) return Optional.of(rooms.get(id));
        if (corridors.containsKey(id)) return Optional.of(corridors.get(id));
        return Optional.empty();
    }
}
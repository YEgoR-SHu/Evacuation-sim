package com.artemyasnik.graph;

public class EvacGraphNode {
    private final String id;
    private final String kind; // "room" | "corridor" | "outside"
    private final int capacity;
    private int currentOccupancy;

    public EvacGraphNode(String id, String kind, int capacity) {
        this.id = id;
        this.kind = kind;
        this.capacity = capacity;
        this.currentOccupancy = 0;
    }

    public String getId() {
        return id;
    }

    public String getKind() {
        return kind;
    }

    public int getCapacity() {
        return capacity;
    }

    public int getCurrentOccupancy() {
        return currentOccupancy;
    }

    public void setCurrentOccupancy(int currentOccupancy) {
        this.currentOccupancy = currentOccupancy;
    }

    @Override
    public String toString() {
        return id + "(" + kind + ", cap=" + capacity + ")";
    }
}
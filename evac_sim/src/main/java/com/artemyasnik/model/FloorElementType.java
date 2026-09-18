package com.artemyasnik.model;

public enum FloorElementType {
    ROOM,
    CORRIDOR,
    DOOR,
    EXIT,
    STAIRS;

    public static FloorElementType fromAttribute(String value) {
        if (value == null) {
            throw new IllegalArgumentException("data-type отсутствует");
        }
        return switch (value.trim().toLowerCase()) {
            case "room" -> ROOM;
            case "corridor" -> CORRIDOR;
            case "door" -> DOOR;
            case "exit" -> EXIT;
            case "stairs" -> STAIRS;
            default -> throw new IllegalArgumentException("Неизвестный data-type: " + value);
        };
    }
}
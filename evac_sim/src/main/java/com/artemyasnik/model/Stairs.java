package com.artemyasnik.model;

import java.util.List;
import java.util.Map;

/**
 * Лестница соединяет узлы на разных этажах. Id узлов в data-connects задаются
 * в формате "baseId@floor", например "C1@1,C1@2" — коридор C1 на этаже 1
 * соединяется с коридором C1 на этаже 2 через эту лестницу.
 */
public class Stairs extends Connector {

    private final int floorFrom;
    private final int floorTo;
    private final String direction; // "up" | "down" | "both"

    public Stairs(String id, List<Point2D> geometry, Map<String, String> rawAttributes,
                  List<String> connectsIds, double throughputPersonsPerSec, Double widthM,
                  int floorFrom, int floorTo, String direction) {
        super(id, FloorElementType.STAIRS, geometry, rawAttributes, connectsIds,
                throughputPersonsPerSec, widthM);
        this.floorFrom = floorFrom;
        this.floorTo = floorTo;
        this.direction = direction;
    }

    public int getFloorFrom() {
        return floorFrom;
    }

    public int getFloorTo() {
        return floorTo;
    }

    public String getDirection() {
        return direction;
    }
}
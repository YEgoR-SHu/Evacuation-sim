package com.artemyasnik.model;

import java.util.List;

public record BoundingBox(double minX, double minY, double maxX, double maxY) {

    public double width() {
        return maxX - minX;
    }

    public double height() {
        return maxY - minY;
    }

    /** Меньшая сторона bbox — удобно как "ширина" коридора/двери по умолчанию. */
    public double shorterSide() {
        return Math.min(width(), height());
    }

    public static BoundingBox of(List<Point2D> points) {
        if (points == null || points.isEmpty()) {
            return new BoundingBox(0, 0, 0, 0);
        }
        double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE, maxY = -Double.MAX_VALUE;
        for (Point2D p : points) {
            minX = Math.min(minX, p.x());
            minY = Math.min(minY, p.y());
            maxX = Math.max(maxX, p.x());
            maxY = Math.max(maxY, p.y());
        }
        return new BoundingBox(minX, minY, maxX, maxY);
    }
}
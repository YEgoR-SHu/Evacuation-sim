package com.artemyasnik.model;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/** Общая база для комнат, коридоров, дверей, выходов и лестниц. */
public abstract class BuildingElement {

    private final String id;
    private final FloorElementType type;
    private final List<Point2D> geometry; // контур в единицах SVG
    private final Map<String, String> rawAttributes; // все data-* как есть, для расширения формата

    protected BuildingElement(String id, FloorElementType type, List<Point2D> geometry,
                              Map<String, String> rawAttributes) {
        this.id = id;
        this.type = type;
        this.geometry = List.copyOf(geometry);
        this.rawAttributes = Collections.unmodifiableMap(rawAttributes);
    }

    public String getId() {
        return id;
    }

    public FloorElementType getType() {
        return type;
    }

    public List<Point2D> getGeometry() {
        return geometry;
    }

    public Map<String, String> getRawAttributes() {
        return rawAttributes;
    }

    public BoundingBox getBoundingBox() {
        return BoundingBox.of(geometry);
    }

    @Override
    public String toString() {
        return type + "[" + id + "]";
    }
}
package com.artemyasnik.simulation;

import java.util.List;

/**
 * Geometry-independent input to the shared evacuation engine.
 * Coordinates are SVG units on the named floor; all durations are seconds.
 * OUTSIDE is a reserved destination and is not included in nodes.
 */
public record EvacuationNetwork(List<Node> nodes, List<Passage> passages) {
    public static final String OUTSIDE = "OUTSIDE";

    public record Position(String floorId, double x, double y) { }

    public record Node(String id, String floorId, String kind, String name,
                       int capacity, double x, double y) {
        public Position position() { return new Position(floorId, x, y); }
    }

    /**
     * A physical passage is one shared resource even when it permits both directions.
     * throughput is people/second, or null for an unconstrained walking connection.
     * beforePath runs from the source to the entrance; afterPath runs from the exit
     * to the destination. A stair's time inside the passage is traversalSeconds.
     */
    public record Passage(String id, String kind, String from, String to,
                          Double throughput, boolean directed,
                          double beforeSeconds, double traversalSeconds, double afterSeconds,
                          List<Position> beforePath, List<Position> afterPath) { }
}

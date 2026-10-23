package com.artemyasnik.simulation;

import java.util.List;

/** Результат расчёта для консольного и браузерного интерфейсов. Координаты — единицы SVG. */
public record AnalysisResult(
        int floorNumber,
        double scaleMetersPerUnit,
        int totalPeople,
        int evacuatedPeople,
        int unreachablePeople,
        double evacuationTimeSeconds,
        List<NodeView> nodes,
        List<ConnectionView> connections,
        List<RouteView> routes,
        List<ConnectionUsage> bottlenecks,
        List<String> warnings) {

    public record NodeView(String id, String kind, String name, int capacity, double x, double y) { }

    public record ConnectionView(String id, String kind, String from, String to,
                                 double throughput, Double width, double x, double y, boolean blocked) { }

    public record RouteView(String sourceNodeId, List<String> nodeIds, List<String> connectionIds,
                            int people, double firstArrivalSeconds, double lastArrivalSeconds) { }

    public record ConnectionUsage(String connectionId, int people, double maxWaitSeconds, double busySeconds) { }
}

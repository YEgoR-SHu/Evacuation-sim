package com.artemyasnik.simulation;

import java.util.List;

/** One complete, replayable run of the shared engine. */
public record NetworkResult(
        int totalPeople,
        int evacuatedPeople,
        int unreachablePeople,
        double evacuationTimeSeconds,
        String strategy,
        List<StrategyComparison> strategyComparisons,
        List<EvacuationNetwork.Node> nodes,
        List<EvacuationNetwork.Passage> passages,
        List<Route> routes,
        List<Bottleneck> bottlenecks,
        List<PersonResult> people,
        List<String> warnings) {

    public record StrategyComparison(String strategy, int evacuatedPeople,
                                     int unreachablePeople, double evacuationTimeSeconds) { }

    public record Route(String sourceNodeId, List<String> nodeIds, List<String> connectionIds,
                        int people, double firstArrivalSeconds, double lastArrivalSeconds) { }

    public record Bottleneck(String connectionId, int people,
                             double maxWaitSeconds, double busySeconds) { }

    public record PersonResult(int id, String sourceNodeId, String status, double finishSeconds,
                               List<String> nodeIds, List<String> connectionIds,
                               List<Segment> timeline) { }

    /**
     * kind: move, wait, passage, stairs, evacuated, unreachable.
     * A terminal segment has equal start/end times. Playback uses these exact
     * timestamps; it must not simulate movement or queues a second time.
     */
    public record Segment(String kind, double startSeconds, double endSeconds,
                          List<EvacuationNetwork.Position> path, String connectionId) { }
}

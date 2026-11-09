package com.artemyasnik.simulation;

import org.junit.jupiter.api.Test;

import java.util.*;

import static com.artemyasnik.simulation.EvacuationNetwork.*;
import static org.junit.jupiter.api.Assertions.*;

class NetworkSimulationServiceTest {
    private final NetworkSimulationService engine = new NetworkSimulationService();

    @Test
    void independentClocksForWalkingQueueServiceAndStairTraversalAreRecorded() {
        Node room = node("R", "upper", 0, 10);
        Node lobby = node("L", "lower", 10, 10);
        Passage stair = new Passage("S", "stairs", "R", "L", .5, true, 2, 4, 3,
                List.of(room.position(), p("upper", 2)), List.of(p("lower", 7), lobby.position()));
        Passage exit = new Passage("E", "exit", "L", OUTSIDE, null, true, 0, 0, 0,
                List.of(lobby.position()), List.of(lobby.position()));
        NetworkResult result = engine.analyze(new EvacuationNetwork(List.of(room, lobby), List.of(stair, exit)),
                Map.of("R", 2), Set.of());
        assertEquals(2, result.evacuatedPeople());
        assertEquals(13, result.evacuationTimeSeconds());
        assertEquals(2, result.bottlenecks().getFirst().maxWaitSeconds());
        NetworkResult.PersonResult first = result.people().getFirst();
        assertEquals(11, first.finishSeconds());
        assertEquals(List.of("move", "passage", "stairs", "move", "evacuated"),
                first.timeline().stream().map(NetworkResult.Segment::kind).toList());
        assertEquals(List.of(0.0, 2.0, 4.0, 8.0, 11.0), first.timeline().stream().map(NetworkResult.Segment::startSeconds).toList());
        assertEquals(List.of(2.0, 4.0, 8.0, 11.0, 11.0), first.timeline().stream().map(NetworkResult.Segment::endSeconds).toList());
        assertEquals("upper", first.timeline().get(2).path().getFirst().floorId());
        assertEquals("lower", first.timeline().get(2).path().getLast().floorId());
        assertEquals(1, result.people().get(1).timeline().stream().filter(s -> s.kind().equals("wait")).count());
    }

    @Test
    void bidirectionalStairReversesGeometryDurationsAndFloorTransition() {
        Node upper = node("U", "2", 20, 10);
        Node lower = node("L", "1", 10, 10);
        Passage stair = new Passage("S", "stairs", "U", "L", 1.0, false, 2, 7, 4,
                List.of(upper.position(), p("2", 18)), List.of(p("1", 14), lower.position()));
        Passage exit = new Passage("E", "exit", "U", OUTSIDE, 1.0, true, 0, 0, 0, List.of(upper.position()), List.of(upper.position()));
        NetworkResult result = engine.analyze(new EvacuationNetwork(List.of(upper, lower), List.of(stair, exit)), Map.of("L", 1), Set.of());
        assertEquals(15, result.evacuationTimeSeconds());
        var person = result.people().getFirst();
        assertEquals(List.of("L", "U", OUTSIDE), person.nodeIds());
        var walk = person.timeline().getFirst();
        assertEquals(4, walk.endSeconds());
        assertEquals(List.of(lower.position(), p("1", 14)), walk.path());
        var crossing = person.timeline().stream().filter(s -> s.kind().equals("stairs")).findFirst().orElseThrow();
        assertEquals(5, crossing.startSeconds());
        assertEquals(12, crossing.endSeconds());
        assertEquals(List.of(p("1", 14), p("2", 18)), crossing.path());
    }

    @Test
    void selectionKeepsBetterOfBaselineAndQueueAwareAndPublishesBothScores() {
        Node room = node("R", "1", 0, 100);
        var network = new EvacuationNetwork(List.of(room), List.of(exit("E1", "R", 1.0), exit("E2", "R", 1.0)));
        NetworkResult result = engine.analyze(network, Map.of("R", 10), Set.of());
        assertEquals("QUEUE_AWARE", result.strategy());
        assertEquals(5, result.evacuationTimeSeconds());
        assertEquals(2, result.strategyComparisons().size());
        assertEquals(10, result.strategyComparisons().get(1).evacuationTimeSeconds());
        assertEquals(result.strategyComparisons().stream().mapToDouble(NetworkResult.StrategyComparison::evacuationTimeSeconds).min().orElseThrow(),
                result.evacuationTimeSeconds());
        assertEquals(2, result.routes().size());
    }

    @Test
    void selectionFallsBackToShortestPathsWhenQueueEstimateMakesWholeRunWorse() {
        List<Node> nodes = List.of(node("R0", "1", 0, 100), node("R1", "1", 0, 100), node("R2", "1", 0, 100));
        List<Passage> passages = List.of(
                new Passage("D01", "door", "R0", "R1", 3.0, false, 3, 0, 5, null, null),
                new Passage("D02", "door", "R0", "R2", 2.0, false, 2, 0, 0, null, null),
                new Passage("D12", "door", "R1", "R2", 2.0, false, 5, 0, 4, null, null),
                new Passage("E0", "exit", "R0", OUTSIDE, 1.5, true, 5, 0, 0, null, null),
                new Passage("E1", "exit", "R1", OUTSIDE, 2.5, true, 2, 0, 0, null, null));
        NetworkResult result = engine.analyze(new EvacuationNetwork(nodes, passages), Map.of("R0", 10, "R1", 8, "R2", 2), Set.of());
        assertEquals("SHORTEST_PATH", result.strategy());
        assertEquals(13, result.evacuationTimeSeconds(), 1e-9);
        assertEquals(20, result.evacuatedPeople());
        assertEquals(13.733333333333, result.strategyComparisons().getFirst().evacuationTimeSeconds(), 1e-9);
    }

    @Test
    void blockingStairPreservesEveryPersonOnUpperFloorAndReplayIsRepeatable() {
        Node upper = node("U", "2", 0, 20), lower = node("L", "1", 0, 20);
        Passage stair = new Passage("S", "stairs", "U", "L", 2.0, true, 0, 12, 0,
                List.of(upper.position()), List.of(lower.position()));
        EvacuationNetwork network = new EvacuationNetwork(List.of(upper, lower), List.of(stair, exit("E", "L", 1.0)));
        NetworkResult blocked = engine.analyze(network, Map.of("U", 4, "L", 2), Set.of("S"));
        assertEquals(6, blocked.totalPeople());
        assertEquals(2, blocked.evacuatedPeople());
        assertEquals(4, blocked.unreachablePeople());
        assertEquals(6, blocked.people().size());
        assertEquals(4, blocked.people().stream().filter(p -> p.status().equals("unreachable")).count());
        assertEquals(blocked, engine.analyze(network, Map.of("L", 2, "U", 4), Set.of("S")));
        NetworkResult open = engine.analyze(network, Map.of("U", 4, "L", 2), Set.of());
        assertEquals(6, open.evacuatedPeople());
        assertEquals(0, open.unreachablePeople());
        assertEquals(blocked, engine.analyze(network, Map.of("U", 4, "L", 2), Set.of("S")));
    }

    @Test
    void terminalTimelineAlwaysContainsCoordinateEvenForFreeInstantaneousExit() {
        Node room = node("R", "1", 0, 10);
        Passage exit = new Passage("E", "exit", "R", OUTSIDE, null, true, 0, 0, 0, null, null);
        NetworkResult result = engine.analyze(new EvacuationNetwork(List.of(room), List.of(exit)), Map.of("R", 1), Set.of());
        var person = result.people().getFirst();
        assertEquals(0, person.finishSeconds());
        assertEquals(List.of(room.position()), person.timeline().getLast().path());
        assertEquals("evacuated", person.timeline().getLast().kind());
    }

    @Test
    void invalidNetworkAndScenarioDataAreRejectedBeforeRouting() {
        Node room = node("R", "1", 0, 10);
        EvacuationNetwork valid = new EvacuationNetwork(List.of(room), List.of(exit("E", "R", 1.0)));
        assertThrows(IllegalArgumentException.class, () -> engine.analyze(valid, Map.of("R", 11), Set.of()));
        assertThrows(IllegalArgumentException.class, () -> engine.analyze(valid, Map.of("R", -1), Set.of()));
        assertThrows(IllegalArgumentException.class, () -> engine.analyze(valid, Map.of("missing", 1), Set.of()));
        assertThrows(IllegalArgumentException.class, () -> engine.analyze(valid, Map.of(), Set.of("missing")));
        assertThrows(IllegalArgumentException.class, () -> engine.analyze(new EvacuationNetwork(List.of(room, room), List.of()), Map.of(), Set.of()));
        assertThrows(IllegalArgumentException.class, () -> engine.analyze(new EvacuationNetwork(List.of(room), List.of(exit("E", "R", Double.NaN))), Map.of(), Set.of()));
        assertThrows(IllegalArgumentException.class, () -> engine.analyze(new EvacuationNetwork(List.of(room), List.of(exit("E", "R", Double.MIN_VALUE))), Map.of(), Set.of()));
        Passage invalidTime = new Passage("E", "exit", "R", OUTSIDE, 1.0, true, -1, 0, 0, List.of(), List.of());
        assertThrows(IllegalArgumentException.class, () -> engine.analyze(new EvacuationNetwork(List.of(room), List.of(invalidTime)), Map.of(), Set.of()));
        Passage unknownNode = new Passage("E", "exit", "missing", OUTSIDE, 1.0, true, 0, 0, 0, List.of(), List.of());
        assertThrows(IllegalArgumentException.class, () -> engine.analyze(new EvacuationNetwork(List.of(room), List.of(unknownNode)), Map.of(), Set.of()));
        Passage wrongGeometry = new Passage("E", "exit", "R", OUTSIDE, 1.0, true, 1, 0, 0,
                List.of(p("1", 20)), List.of());
        assertThrows(IllegalArgumentException.class, () -> engine.analyze(new EvacuationNetwork(List.of(room), List.of(wrongGeometry)), Map.of(), Set.of()));
    }

    @Test
    void everySuccessfulTraceIsChronologicalAndItsCountsMatchRouteAndQueueTotals() {
        Node room = node("R", "1", 0, 100);
        NetworkResult result = engine.analyze(new EvacuationNetwork(List.of(room), List.of(exit("E", "R", .5))), Map.of("R", 20), Set.of());
        assertEquals(40, result.evacuationTimeSeconds());
        assertEquals(20, result.routes().stream().mapToInt(NetworkResult.Route::people).sum());
        assertEquals(20, result.bottlenecks().getFirst().people());
        assertEquals(40, result.bottlenecks().getFirst().busySeconds());
        assertEquals(38, result.bottlenecks().getFirst().maxWaitSeconds());
        Set<Integer> ids = new HashSet<>();
        for (var person : result.people()) {
            assertTrue(ids.add(person.id()));
            double previousEnd = 0;
            for (var segment : person.timeline()) {
                assertEquals(previousEnd, segment.startSeconds());
                assertTrue(segment.endSeconds() >= segment.startSeconds());
                assertFalse(segment.path().isEmpty());
                previousEnd = segment.endSeconds();
            }
            assertEquals(person.finishSeconds(), previousEnd);
        }
    }

    private Node node(String id, String floor, double x, int capacity) {
        return new Node(id, floor, "room", id, capacity, x, 0);
    }
    private Position p(String floor, double x) { return new Position(floor, x, 0); }
    private Passage exit(String id, String from, Double throughput) {
        return new Passage(id, "exit", from, OUTSIDE, throughput, true, 0, 0, 0, null, null);
    }
}

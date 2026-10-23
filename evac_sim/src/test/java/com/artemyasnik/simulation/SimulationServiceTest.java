package com.artemyasnik.simulation;

import com.artemyasnik.model.*;
import com.artemyasnik.parser.SvgFloorPlanParser;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class SimulationServiceTest {
    private final SimulationService service = new SimulationService();

    @Test
    void singleQueueHasAnalyticalServiceTimeAndWait() {
        FloorPlan plan = singleRoom(1.0);
        AnalysisResult result = service.analyze(plan, Map.of("R", 5), Set.of(), 1);
        assertEquals(5, result.evacuatedPeople());
        assertEquals(0, result.unreachablePeople());
        assertEquals(5, result.evacuationTimeSeconds(), 1e-9);
        assertEquals(4, result.bottlenecks().getFirst().maxWaitSeconds(), 1e-9);
        assertEquals(5, result.bottlenecks().getFirst().busySeconds(), 1e-9);
    }

    @Test
    void parallelExitsShareLoadAndBlockedExitReroutesEveryone() {
        FloorPlan plan = singleRoom(1.0);
        plan.addExit(exit("E2", "R", 0, 1.0));
        AnalysisResult both = service.analyze(plan, Map.of("R", 10), Set.of(), 1);
        AnalysisResult blocked = service.analyze(plan, Map.of("R", 10), Set.of("E"), 1);
        assertEquals(5, both.evacuationTimeSeconds(), 1e-9);
        assertEquals(10, blocked.evacuationTimeSeconds(), 1e-9);
        assertEquals(2, both.routes().size());
        assertEquals(List.of("E2"), blocked.routes().getFirst().connectionIds());
        assertEquals(10, both.routes().stream().mapToInt(AnalysisResult.RouteView::people).sum());
    }

    @Test
    void travelAndServiceFinishBeforePersonIsCountedOutside() {
        FloorPlan plan = new FloorPlan(1, 2);
        plan.addRoom(room("R", 0, 10));
        plan.addCorridor(corridor("C", 10, 10));
        plan.addDoor(door("D", "R", "C", 5, 2));
        plan.addExit(exit("E", "C", 20, 1));
        AnalysisResult result = service.analyze(plan, Map.of("R", 1), Set.of(), 2);
        // 0 -> 5 -> 10 -> 20: 20 seconds of walking, .5 at door and 1 at exit.
        assertEquals(21.5, result.evacuationTimeSeconds(), 1e-9);
        assertEquals(List.of("R", "C", "OUTSIDE"), result.routes().getFirst().nodeIds());
        assertEquals(List.of("D", "E"), result.routes().getFirst().connectionIds());
    }

    @Test
    void fractionalThroughputAndReversedExitEndpointsAreRespected() {
        FloorPlan plan = new FloorPlan(1, 1);
        plan.addRoom(room("R", 0, 10));
        plan.addExit(new EmergencyExit("E", point(0), Map.of(), List.of("OUTSIDE", "R"), .5, 1.0));
        assertEquals(6, service.analyze(plan, Map.of("R", 3), Set.of(), 1).evacuationTimeSeconds(), 1e-9);
    }

    @Test
    void mergingSourcesShareOneDownstreamResource() {
        FloorPlan plan = new FloorPlan(1, 1);
        plan.addRoom(room("R1", 0, 10));
        plan.addRoom(room("R2", 0, 10));
        plan.addCorridor(corridor("C", 0, 20));
        plan.addDoor(door("D1", "R1", "C", 0, 10));
        plan.addDoor(door("D2", "R2", "C", 0, 10));
        plan.addExit(exit("E", "C", 0, 1));
        AnalysisResult result = service.analyze(plan, Map.of("R1", 3, "R2", 3), Set.of(), 1);
        assertEquals(6.1, result.evacuationTimeSeconds(), 1e-9);
        assertEquals(6, result.bottlenecks().getFirst().people());
        assertEquals("E", result.bottlenecks().getFirst().connectionId());
    }

    @Test
    void blockedAndDisconnectedPeopleArePreserved() {
        FloorPlan plan = singleRoom(1);
        plan.addRoom(room("ISOLATED", 20, 20));
        AnalysisResult result = service.analyze(plan, Map.of("R", 3, "ISOLATED", 7), Set.of(), 1);
        assertEquals(10, result.totalPeople());
        assertEquals(3, result.evacuatedPeople());
        assertEquals(7, result.unreachablePeople());
        assertEquals(result.totalPeople(), result.evacuatedPeople() + result.unreachablePeople());
        AnalysisResult allBlocked = service.analyze(plan, Map.of("R", 3), Set.of("E"), 1);
        assertEquals(3, allBlocked.unreachablePeople());
        assertEquals(0, allBlocked.evacuatedPeople());
        assertTrue(allBlocked.routes().isEmpty());
    }

    @Test
    void zeroOccupancyDoesNotAssumeFullCapacity() {
        AnalysisResult result = service.analyze(singleRoom(1), Map.of(), Set.of(), 1);
        assertEquals(0, result.totalPeople());
        assertEquals(0, result.evacuationTimeSeconds());
        assertTrue(result.routes().isEmpty());
    }

    @Test
    void invalidScenarioValuesAreRejected() {
        FloorPlan plan = singleRoom(1);
        assertThrows(IllegalArgumentException.class, () -> service.analyze(plan, Map.of("missing", 1), Set.of(), 1));
        assertThrows(IllegalArgumentException.class, () -> service.analyze(plan, Map.of("R", -1), Set.of(), 1));
        assertThrows(IllegalArgumentException.class, () -> service.analyze(plan, Map.of("R", 11), Set.of(), 1));
        assertThrows(IllegalArgumentException.class, () -> service.analyze(plan, Map.of(), Set.of("missing"), 1));
        assertThrows(IllegalArgumentException.class, () -> service.analyze(plan, Map.of(), Set.of(), Double.NaN));
        assertThrows(IllegalArgumentException.class, () -> service.analyze(plan, Map.of(), Set.of(), 0));
        assertThrows(IllegalArgumentException.class, () -> service.analyze(singleRoom(Double.MIN_VALUE), Map.of(), Set.of(), 1));
    }

    @Test
    void serviceTimeMustAdvanceClockAtCurrentTravelTime() {
        FloorPlan plan = new FloorPlan(1, 1);
        plan.addRoom(room("R", 0, 10));
        plan.addExit(exit("E", "R", 1, 1e308));
        assertThrows(IllegalArgumentException.class, () -> service.analyze(plan, Map.of("R", 2), Set.of(), 1));
    }

    @Test
    void largeRoutingWorkIsRejectedBeforeSimulation() {
        FloorPlan plan = new FloorPlan(1, 1);
        for (int i = 0; i < 100; i++) plan.addRoom(room("R" + i, i, 10_000));
        plan.addExit(exit("E", "R0", 0, 1));
        assertThrows(IllegalArgumentException.class, () -> service.analyze(plan, Map.of("R0", 10_000), Set.of(), 1));
    }

    @Test
    void repeatedCallsAreDeterministicAndHaveNoPersistedQueues() {
        FloorPlan plan = singleRoom(1);
        plan.addExit(exit("E2", "R", 0, 1));
        AnalysisResult first = service.analyze(plan, Map.of("R", 8), Set.of(), 1);
        AnalysisResult second = service.analyze(plan, Map.of("R", 8), Set.of(), 1);
        assertEquals(first, second);
        assertEquals(8, service.analyze(plan, Map.of("R", 8), Set.of("E2"), 1).evacuationTimeSeconds(), 1e-9);
        assertEquals(first, service.analyze(plan, Map.of("R", 8), Set.of(), 1));
    }

    @Test
    void sharedConnectorLimitsBothDirectionsTogether() {
        FloorPlan plan = new FloorPlan(1, 1);
        plan.addRoom(room("A", 0, 200));
        plan.addRoom(room("B", 0, 200));
        plan.addDoor(door("D", "A", "B", 0, 10));
        plan.addExit(exit("EA", "A", 0, 1));
        plan.addExit(exit("EB", "B", 0, 2));
        AnalysisResult result = service.analyze(plan, Map.of("A", 30, "B", 100), Set.of(), 1);
        assertEquals(130, result.evacuatedPeople());
        for (var route : result.routes()) {
            assertEquals(route.nodeIds().size(), Set.copyOf(route.nodeIds()).size());
            assertEquals(route.nodeIds().size() - 1, route.connectionIds().size());
        }
        var usage = result.bottlenecks().stream().filter(b -> b.connectionId().equals("D")).findFirst().orElseThrow();
        assertEquals(usage.people() / 10.0, usage.busySeconds(), 1e-9);
        assertTrue(result.routes().stream().anyMatch(r -> r.nodeIds().equals(List.of("A", "B", "OUTSIDE"))));
        assertTrue(result.routes().stream().anyMatch(r -> r.nodeIds().equals(List.of("B", "A", "OUTSIDE"))));
    }

    @Test
    void providedSvgPlansIntegrateParserGeometryAndRouting() throws Exception {
        for (String name : List.of("example.svg", "example_office.svg", "example_defaults.svg",
                "example_floor1.svg", "example_floor2.svg", "example_polygon.svg",
                "example_curve.svg", "example_star.svg", "example_circle.svg")) {
            try (var input = getClass().getResourceAsStream("/examples/" + name)) {
                assertNotNull(input, name);
                FloorPlan plan = new SvgFloorPlanParser().parse(input);
                Map<String, Integer> occupancy = new LinkedHashMap<>();
                plan.getRooms().values().forEach(r -> occupancy.put(r.getId(), r.getCapacity()));
                AnalysisResult result = service.analyze(plan, occupancy, Set.of(), 1.3);
                assertEquals(result.totalPeople(), result.evacuatedPeople() + result.unreachablePeople(), name);
                assertEquals(result.evacuatedPeople(), result.routes().stream().mapToInt(AnalysisResult.RouteView::people).sum(), name);
                assertTrue(Double.isFinite(result.evacuationTimeSeconds()), name);
                if (!plan.getStairs().isEmpty()) assertTrue(result.warnings().stream().anyMatch(w -> w.contains("лестницы")), name);
            }
        }
    }

    private FloorPlan singleRoom(double throughput) {
        FloorPlan plan = new FloorPlan(1, 1);
        plan.addRoom(room("R", 0, 10));
        plan.addExit(exit("E", "R", 0, throughput));
        return plan;
    }

    private Room room(String id, double x, int capacity) {
        return new Room(id, point(x), Map.of(), capacity, 1, id);
    }

    private Corridor corridor(String id, double x, int capacity) {
        return new Corridor(id, point(x), Map.of(), capacity, 1);
    }

    private Door door(String id, String from, String to, double x, double throughput) {
        return new Door(id, point(x), Map.of(), List.of(from, to), throughput, 1.0);
    }

    private EmergencyExit exit(String id, String from, double x, double throughput) {
        return new EmergencyExit(id, point(x), Map.of(), List.of(from, "OUTSIDE"), throughput, 1.0);
    }

    private List<Point2D> point(double x) { return List.of(new Point2D(x, 0)); }
}

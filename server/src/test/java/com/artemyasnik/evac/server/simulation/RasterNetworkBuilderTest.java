package com.artemyasnik.evac.server.simulation;

import com.artemyasnik.evac.server.dto.*;
import com.artemyasnik.simulation.NetworkSimulationService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RasterNetworkBuilderTest {
    private final RasterNetworkBuilder builder = new RasterNetworkBuilder();

    @Test void convertsSvgUnitsToMetersBeforeCalculatingTravelTime() {
        var prepared = builder.build(List.of(floor(1, 1, "")), scenario(3,
                spawn(1, 1, 20, 50), exit(2, 1, 180, 50)), List.of());
        var walk = prepared.network().passages().stream().filter(p -> p.kind().equals("walk")).findFirst().orElseThrow();
        assertEquals(4.0, walk.beforeSeconds(), 1e-8); // 160 SVG units / 20 units/m / 2 m/s.
        var result = new NetworkSimulationService().analyze(prepared.network(), prepared.occupancy(), java.util.Set.of());
        assertEquals(3, result.totalPeople());
        assertEquals(3, result.evacuatedPeople());
        assertEquals(0, result.unreachablePeople());
        assertEquals(7.0, result.evacuationTimeSeconds(), 1e-8); // Walk + three one-second exit slots.
    }

    @Test void closedWallMakesAllPeopleUnreachableWithoutDroppingThem() {
        var prepared = builder.build(List.of(floor(1, 1, "<rect x=\"96\" y=\"0\" width=\"8\" height=\"100\" fill=\"black\"/>")),
                scenario(8, spawn(1, 1, 40, 50), exit(2, 1, 160, 50)), List.of());
        assertTrue(prepared.network().passages().stream().noneMatch(p -> p.kind().equals("walk")));
        var result = new NetworkSimulationService().analyze(prepared.network(), prepared.occupancy(), java.util.Set.of());
        assertEquals(8, result.totalPeople());
        assertEquals(0, result.evacuatedPeople());
        assertEquals(8, result.unreachablePeople());
        assertEquals(8, result.people().size());
    }

    @Test void pathGoesAroundAnObstacleAndKeepsItsPolyline() {
        var prepared = builder.build(List.of(floor(1, 1, "<rect x=\"96\" y=\"20\" width=\"8\" height=\"60\" fill=\"black\"/>")),
                scenario(1, spawn(1, 1, 40, 50), exit(2, 1, 160, 50)), List.of());
        var walk = prepared.network().passages().stream().filter(p -> p.kind().equals("walk")).findFirst().orElseThrow();
        assertTrue(walk.beforeSeconds() > 3.0);
        assertTrue(walk.beforePath().size() > 2);
        for (var point : walk.beforePath()) assertFalse(point.x() >= 96 && point.x() <= 104 && point.y() >= 20 && point.y() <= 80);
    }

    @Test void diagonalCannotCutBetweenTwoBlockedCells() {
        var small = new FloorDto(1, "Угол", 1,
                "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 16 16\"><rect x=\"8\" y=\"0\" width=\"8\" height=\"8\"/><rect x=\"0\" y=\"8\" width=\"8\" height=\"8\"/></svg>",
                new double[]{0, 0, 16, 16});
        var prepared = builder.build(List.of(small), scenario(1, spawn(1, 1, 4, 4), exit(2, 1, 12, 12)), List.of());
        assertTrue(prepared.network().passages().stream().noneMatch(p -> p.kind().equals("walk")));
    }

    @Test void explicitStairsConnectDifferentCoordinatesAndAllPeopleLeaveUpperFloor() {
        var prepared = builder.build(List.of(floor(1, 1, ""), floor(2, 2, "")), scenario(2,
                        spawn(1, 2, 20, 50), stair(2, 2, 180, 50), stair(3, 1, 20, 50), exit(4, 1, 180, 50)),
                List.of(new RasterNetworkBuilder.StairLink(2, 3)));
        var stairs = prepared.network().passages().stream().filter(p -> p.kind().equals("stairs")).findFirst().orElseThrow();
        assertEquals("mark:2", stairs.from());
        assertEquals("mark:3", stairs.to());
        assertEquals("2", stairs.beforePath().getFirst().floorId());
        assertEquals("1", stairs.afterPath().getFirst().floorId());
        assertEquals(12, stairs.traversalSeconds());
        var result = new NetworkSimulationService().analyze(prepared.network(), prepared.occupancy(), java.util.Set.of());
        assertEquals(2, result.evacuatedPeople());
        assertEquals(0, result.unreachablePeople());
        assertTrue(result.people().stream().allMatch(p -> p.timeline().stream().anyMatch(segment -> segment.kind().equals("stairs"))));
    }

    @Test void mismatchedUnlinkedStairsDoNotTeleportPeopleToLowerFloor() {
        var prepared = builder.build(List.of(floor(1, 1, ""), floor(2, 2, "")), scenario(2,
                spawn(1, 2, 20, 50), stair(2, 2, 180, 50), stair(3, 1, 20, 50), exit(4, 1, 180, 50)), List.of());
        assertTrue(prepared.network().passages().stream().noneMatch(p -> p.kind().equals("stairs")));
        var result = new NetworkSimulationService().analyze(prepared.network(), prepared.occupancy(), java.util.Set.of());
        assertEquals(2, result.unreachablePeople());
        assertTrue(prepared.warnings().stream().anyMatch(w -> w.contains("не связана")));
    }

    @Test void implicitStairPairingIsReportedAndCannotOverrideExplicitLinks() {
        var marks = new MarkDto[]{spawn(1, 2, 20, 50), stair(2, 2, 100, 50), stair(3, 1, 100, 50), exit(4, 1, 180, 50)};
        var prepared = builder.build(List.of(floor(1, 1, ""), floor(2, 2, "")), scenario(1, marks), List.of());
        assertEquals(1, prepared.network().passages().stream().filter(p -> p.kind().equals("stairs")).count());
        assertTrue(prepared.warnings().stream().anyMatch(w -> w.contains("одинаковые координаты")));
        assertThrows(IllegalArgumentException.class, () -> builder.build(List.of(floor(1, 1, ""), floor(2, 2, "")), scenario(1, marks),
                List.of(new RasterNetworkBuilder.StairLink(3, 2))));
    }

    @Test void spawnCountsAreDeterministicAndConserveTheRequestedTotal() {
        var floors = List.of(floor(1, 1, ""));
        var scenario = scenario(7, spawn(3, 1, 20, 50), spawn(2, 1, 40, 50), spawn(1, 1, 60, 50), exit(4, 1, 180, 50));
        var prepared = builder.build(floors, scenario, List.of());
        assertEquals(3, prepared.occupancy().get("mark:1"));
        assertEquals(2, prepared.occupancy().get("mark:2"));
        assertEquals(2, prepared.occupancy().get("mark:3"));
        assertEquals(7, prepared.occupancy().values().stream().mapToInt(Integer::intValue).sum());
        assertEquals(prepared.occupancy(), builder.build(floors, scenario, List.of()).occupancy());
    }

    @Test void refusesMissingSpawnOutsideMarkAndExcessiveRaster() {
        assertThrows(IllegalArgumentException.class, () -> builder.build(List.of(floor(1, 1, "")), scenario(2, exit(1, 1, 180, 50)), List.of()));
        assertThrows(IllegalArgumentException.class, () -> builder.build(List.of(floor(1, 1, "")), scenario(2, spawn(1, 1, -10, 50)), List.of()));
        var huge = new FloorDto(1, "Huge", 1, "<svg xmlns=\"http://www.w3.org/2000/svg\"/>", new double[]{0, 0, 1e8, 1e8});
        assertThrows(IllegalArgumentException.class, () -> builder.build(List.of(huge), scenario(0), List.of()));
    }

    private static FloorDto floor(long id, int level, String content) {
        return new FloorDto(id, "Этаж " + id, level,
                "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 200 100\">" + content + "</svg>", new double[]{0, 0, 200, 100});
    }
    private static ScenarioFullDto scenario(int count, MarkDto... marks) {
        return new ScenarioFullDto(1, "Тест", true, new ParamsDto(count, 2.0, .4, 20.0, 12.0, 1.0, 1.0), null, List.of(marks), "", "");
    }
    private static MarkDto spawn(long id, long floor, double x, double y) { return new MarkDto(id, floor, "spawn", x, y, null); }
    private static MarkDto exit(long id, long floor, double x, double y) { return new MarkDto(id, floor, "exit", x, y, 1.0); }
    private static MarkDto stair(long id, long floor, double x, double y) { return new MarkDto(id, floor, "stair", x, y, 1.0); }
}

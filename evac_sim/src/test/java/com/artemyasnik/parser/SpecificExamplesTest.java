package com.artemyasnik.parser;

import com.artemyasnik.model.*;
import org.junit.jupiter.api.Test;

import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.*;

class SpecificExamplesTest {

    private FloorPlan parse(String resource) throws Exception {
        try (InputStream is = getClass().getResourceAsStream(resource)) {
            assertNotNull(is, "Ресурс не найден: " + resource);
            return new SvgFloorPlanParser().parse(is);
        }
    }

    // ---------- example_defaults.svg ----------

    @Test
    void defaultsAreComputedFromBboxAndScale() throws Exception {
        FloorPlan plan = parse("/examples/example_defaults.svg");

        // scale = 0.05, комната 140×140 → 49 м²
        Room r1 = plan.getRooms().get("R1");
        assertNotNull(r1);
        assertEquals(49.0, r1.getAreaSqM(), 1e-6);

        // коридор 300×100, меньшая сторона 100 × 0.05 = 5 м
        Corridor c1 = plan.getCorridors().get("C1");
        assertNotNull(c1);
        assertEquals(5.0, c1.getWidthM(), 1e-6);

        // дверь без data-throughput → дефолт 1.0
        Door d1 = plan.getDoors().stream()
                .filter(d -> d.getId().equals("D1"))
                .findFirst().orElseThrow();
        assertEquals(1.0, d1.getThroughput(), 1e-9);

        // ширина двери: 50 × 0.05 = 2.5 м (bbox.shorterSide * scale)
        assertEquals(2.5, d1.getWidthM(), 1e-6);
    }

    // ---------- example_office.svg ----------

    @Test
    void officeHasTwoEmergencyExits() throws Exception {
        FloorPlan plan = parse("/examples/example_office.svg");

        assertEquals(2, plan.getExits().size());
        for (EmergencyExit e : plan.getExits()) {
            assertEquals(EmergencyExit.OUTSIDE_NODE_ID, e.getConnectsIds().get(1),
                    "Второй узел выхода должен быть OUTSIDE");
        }
    }

    @Test
    void officeHasTenRoomsAndOneCorridor() throws Exception {
        FloorPlan plan = parse("/examples/example_office.svg");

        assertEquals(10, plan.getRooms().size());
        assertEquals(1, plan.getCorridors().size());
        assertEquals(10, plan.getDoors().size());
    }

    // ---------- example_floor1.svg ----------

    @Test
    void stairsHaveFloorsAndDirection() throws Exception {
        FloorPlan plan = parse("/examples/example_floor1.svg");

        assertEquals(1, plan.getStairs().size());
        Stairs st = plan.getStairs().get(0);
        assertEquals("S_cell1@1", st.getConnectsIds().get(0));
        assertEquals("S_cell1@2", st.getConnectsIds().get(1));
        assertEquals(1, st.getFloorFrom());
        assertEquals(2, st.getFloorTo());
        assertEquals("both", st.getDirection());
        assertEquals(1.5, st.getThroughput(), 1e-9);
    }

    // ---------- example_polygon.svg ----------

    @Test
    void polygonIsParsedAndBboxComputed() throws Exception {
        FloorPlan plan = parse("/examples/example_polygon.svg");

        // Г-образный коридор: bbox от (100,100) до (700,500)
        Corridor c1 = plan.getCorridors().get("C1");
        assertNotNull(c1);
        BoundingBox bbox = c1.getBoundingBox();
        assertEquals(100.0, bbox.minX(), 1e-9);
        assertEquals(100.0, bbox.minY(), 1e-9);
        assertEquals(700.0, bbox.maxX(), 1e-9);
        assertEquals(500.0, bbox.maxY(), 1e-9);
    }

    // ---------- example_curve.svg ----------

    @Test
    void curveFallsBackToExplicitArea() throws Exception {
        FloorPlan plan = parse("/examples/example_curve.svg");

        Room hall = plan.getRooms().get("R_hall");
        assertNotNull(hall);
        // data-area задан явно — парсер не должен его пересчитывать
        assertEquals(85.5, hall.getAreaSqM(), 1e-9);
    }

    // ---------- example_star.svg ----------

    @Test
    void starHallHasFourDoorsAndTwoExits() throws Exception {
        FloorPlan plan = parse("/examples/example_star.svg");

        assertEquals(4, plan.getRooms().size());
        assertEquals(1, plan.getCorridors().size());
        assertEquals(4, plan.getDoors().size());
        assertEquals(2, plan.getExits().size());

        // все двери ведут в один холл
        for (Door d : plan.getDoors()) {
            assertTrue(d.getConnectsIds().contains("C_hall"),
                    "Дверь " + d.getId() + " должна соединять с C_hall");
        }
    }

    // ---------- example_circle.svg ----------

    @Test
    void circleAndEllipseAreParsed() throws Exception {
        FloorPlan plan = parse("/examples/example_circle.svg");

        Room round = plan.getRooms().get("R_round");
        assertNotNull(round);
        // bbox круга r=100 → 200×200, но data-area задан явно
        assertEquals(31.4, round.getAreaSqM(), 1e-9);

        Room ellipse = plan.getRooms().get("R_ellipse");
        assertNotNull(ellipse);
        assertEquals(37.7, ellipse.getAreaSqM(), 1e-9);
    }
}
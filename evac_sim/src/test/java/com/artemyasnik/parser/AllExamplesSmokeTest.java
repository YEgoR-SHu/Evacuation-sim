package com.artemyasnik.parser;

import com.artemyasnik.graph.EvacGraphBuilder;
import com.artemyasnik.model.FloorPlan;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Прогоняет все рабочие примеры из src/main/resources/examples/.
 * Задача — поймать регрессии: любой пример должен парситься и
 * собираться в граф без исключений.
 */
class AllExamplesSmokeTest {

    private static final String[] EXAMPLES = {
            "/examples/example.svg",
            "/examples/example_office.svg",
            "/examples/example_floor1.svg",
            "/examples/example_floor2.svg",
            "/examples/example_polygon.svg",
            "/examples/example_curve.svg",
            "/examples/example_defaults.svg",
            "/examples/example_star.svg",
            "/examples/example_circle.svg",
    };

    @ParameterizedTest(name = "парсится: {0}")
    @ValueSource(strings = {
            "/examples/example.svg",
            "/examples/example_office.svg",
            "/examples/example_floor1.svg",
            "/examples/example_floor2.svg",
            "/examples/example_polygon.svg",
            "/examples/example_curve.svg",
            "/examples/example_defaults.svg",
            "/examples/example_star.svg",
            "/examples/example_circle.svg",
    })
    void parsesWithoutError(String resource) throws Exception {
        try (InputStream is = getClass().getResourceAsStream(resource)) {
            assertNotNull(is, "Ресурс не найден на classpath: " + resource);
            FloorPlan plan = new SvgFloorPlanParser().parse(is);
            assertNotNull(plan);
            assertTrue(plan.getFloorNumber() >= 1, "Этаж должен быть >= 1");
            assertTrue(plan.getScaleMetersPerUnit() > 0, "Масштаб должен быть > 0");
        }
    }

    @ParameterizedTest(name = "граф собирается: {0}")
    @ValueSource(strings = {
            "/examples/example.svg",
            "/examples/example_office.svg",
            "/examples/example_floor1.svg",
            "/examples/example_floor2.svg",
            "/examples/example_polygon.svg",
            "/examples/example_curve.svg",
            "/examples/example_defaults.svg",
            "/examples/example_star.svg",
            "/examples/example_circle.svg",
    })
    void buildsGraphWithoutDanglingReferences(String resource) throws Exception {
        try (InputStream is = getClass().getResourceAsStream(resource)) {
            assertNotNull(is);
            FloorPlan plan = new SvgFloorPlanParser().parse(is);
            assertDoesNotThrow(() -> new EvacGraphBuilder().build(plan));
        }
    }
}
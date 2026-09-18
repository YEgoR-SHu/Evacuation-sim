package com.artemyasnik;

import com.artemyasnik.graph.EvacGraph;
import com.artemyasnik.graph.EvacGraphBuilder;
import com.artemyasnik.model.*;
import com.artemyasnik.parser.SvgFloorPlanParser;

import java.io.File;
import java.io.InputStream;

public class Main {

    /** Путь к дефолтному примеру на classpath (src/main/resources/examples/example.svg). */
    private static final String DEFAULT_RESOURCE = "/examples/example.svg";

    public static void main(String[] args) throws Exception {
        FloorPlan plan = loadPlan(args);

        System.out.println("Этаж " + plan.getFloorNumber() + ", масштаб " + plan.getScaleMetersPerUnit() + " м/ед.");
        System.out.println();

        System.out.println("Комнаты:");
        for (Room r : plan.getRooms().values()) {
            System.out.printf("  %-4s %-12s вместимость=%-4d площадь=%.1f м²%n",
                    r.getId(), r.getName(), r.getCapacity(), r.getAreaSqM());
        }

        System.out.println("Коридоры:");
        for (Corridor c : plan.getCorridors().values()) {
            System.out.printf("  %-4s вместимость=%-4d ширина=%.2f м%n",
                    c.getId(), c.getCapacity(), c.getWidthM());
        }

        System.out.println("Двери:");
        for (Door d : plan.getDoors()) {
            System.out.printf("  %-4s %s -> %s, пропускная способность=%.2f чел/с%n",
                    d.getId(), d.getConnectsIds().get(0), d.getConnectsIds().get(1), d.getThroughput());
        }

        System.out.println("Аварийные выходы:");
        for (EmergencyExit e : plan.getExits()) {
            System.out.printf("  %-4s %s -> %s, пропускная способность=%.2f чел/с%n",
                    e.getId(), e.getConnectsIds().get(0), e.getConnectsIds().get(1), e.getThroughput());
        }

        System.out.println("Лестницы:");
        for (Stairs s : plan.getStairs()) {
            System.out.printf("  %-4s этаж %d -> %d, пропускная способность=%.2f чел/с%n",
                    s.getId(), s.getFloorFrom(), s.getFloorTo(), s.getThroughput());
        }

        System.out.println();
        System.out.println("Граф эвакуации:");
        EvacGraph graph = new EvacGraphBuilder().build(plan);
        graph.getEdges().forEach(edge -> System.out.println("  " + edge));

        int totalPeople = plan.getRooms().values().stream().mapToInt(Room::getCapacity).sum();
        System.out.println();
        System.out.println("Суммарная вместимость комнат на этаже: " + totalPeople + " чел.");
    }

    /**
     * Загружает план: если передан аргумент — читает файл с диска,
     * иначе берёт встроенный пример с classpath.
     */
    private static FloorPlan loadPlan(String[] args) throws Exception {
        SvgFloorPlanParser parser = new SvgFloorPlanParser();

        if (args.length > 0) {
            File file = new File(args[0]);
            if (!file.isFile()) {
                throw new IllegalArgumentException(
                        "Файл не найден: " + file.getAbsolutePath());
            }
            System.out.println("Источник: " + file.getAbsolutePath());
            System.out.println();
            return parser.parse(file);
        }

        try (InputStream is = Main.class.getResourceAsStream(DEFAULT_RESOURCE)) {
            if (is == null) {
                throw new IllegalStateException(
                        "Ресурс " + DEFAULT_RESOURCE + " не найден на classpath. "
                                + "Положи файл в src/main/resources/examples/example.svg "
                                + "или передай путь к своему SVG аргументом командной строки.");
            }
            System.out.println("Источник: встроенный пример " + DEFAULT_RESOURCE);
            System.out.println();
            return parser.parse(is);
        }
    }
}
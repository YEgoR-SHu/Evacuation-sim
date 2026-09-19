package com.artemyasnik;

import com.artemyasnik.graph.EvacGraph;
import com.artemyasnik.graph.EvacGraphBuilder;
import com.artemyasnik.model.*;
import com.artemyasnik.parser.SvgFloorPlanParser;

import java.io.File;
import java.io.InputStream;
import java.util.List;

public class Main {

    /** Путь к дефолтному примеру на classpath (src/main/resources/examples/example.svg). */
    private static final String DEFAULT_RESOURCE = "/examples/example.svg";

    public static void main(String[] args) throws Exception {
        FloorPlan plan = loadPlan(args);

        System.out.println("Этаж " + plan.getFloorNumber() + ", масштаб "
                + plan.getScaleMetersPerUnit() + " м/ед.");
        System.out.println();

        System.out.println("Комнаты:");
        for (Room r : plan.getRooms().values()) {
            System.out.printf("  %-4s %-14s вместимость=%-4d площадь=%.1f м²%n",
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
            System.out.printf("  %-4s этаж %d -> %d, направление=%s, пропускная способность=%.2f чел/с%n",
                    s.getId(), s.getFloorFrom(), s.getFloorTo(),
                    s.getDirection(), s.getThroughput());
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
     * Загружает план. Порядок поиска:
     * <ol>
     *     <li>Если передан аргумент и он указывает на существующий файл на диске — читаем файл.</li>
     *     <li>Если передан аргумент и он не файл — пробуем найти его как ресурс на classpath
     *         (например, {@code examples/example_office.svg} или {@code /examples/example_office.svg}).</li>
     *     <li>Если аргументов нет — берём встроенный пример {@link #DEFAULT_RESOURCE}.</li>
     * </ol>
     *
     * @param args аргументы командной строки; {@code args[0]} — путь к файлу или
     *             имя ресурса на classpath
     * @return разобранный план этажа
     * @throws Exception если план не найден или не может быть разобран
     */
    private static FloorPlan loadPlan(String[] args) throws Exception {
        SvgFloorPlanParser parser = new SvgFloorPlanParser();

        if (args.length > 0) {
            String source = args[0];

            // 1. Пробуем как файл на диске — с относительным или абсолютным путём.
            File file = new File(source);
            if (file.isFile()) {
                System.out.println("Источник: файл " + file.getAbsolutePath());
                System.out.println();
                return parser.parse(file);
            }

            // 2. Пробуем как ресурс на classpath.
            String resourcePath = source.startsWith("/") ? source : "/" + source;
            try (InputStream is = Main.class.getResourceAsStream(resourcePath)) {
                if (is != null) {
                    System.out.println("Источник: ресурс " + resourcePath);
                    System.out.println();
                    return parser.parse(is);
                }
            }

            // 3. Ничего не нашли — падаем с понятным сообщением.
            throw new IllegalArgumentException(
                    "Не найден ни файл, ни ресурс: '" + source + "'.\n"
                            + "  — как файл: " + file.getAbsolutePath() + "\n"
                            + "  — как ресурс: " + resourcePath + "\n"
                            + "Убедись, что файл существует, или укажи имя примера из "
                            + "src/main/resources/examples/ (например: examples/example_office.svg).");
        }

        // 4. Ничего не передано — берём встроенный пример.
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
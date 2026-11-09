package com.artemyasnik.evac.server.simulation;

import com.artemyasnik.evac.server.dto.FloorDto;
import com.artemyasnik.evac.server.dto.MarkDto;
import com.artemyasnik.evac.server.dto.ParamsDto;
import com.artemyasnik.evac.server.dto.ScenarioFullDto;
import com.artemyasnik.evac.server.parser.SvgSafety;
import com.artemyasnik.simulation.EvacuationNetwork;
import com.artemyasnik.simulation.EvacuationNetwork.Node;
import com.artemyasnik.simulation.EvacuationNetwork.Passage;
import com.artemyasnik.simulation.EvacuationNetwork.Position;
import org.apache.batik.transcoder.TranscoderInput;
import org.apache.batik.transcoder.TranscoderOutput;
import org.apache.batik.transcoder.image.ImageTranscoder;
import org.w3c.dom.Document;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.*;
import org.xml.sax.InputSource;

/**
 * Adapts the platform's annotated floor images to the shared Java engine.
 * Black raster pixels are obstacles. Movement distances come from walkable
 * cell paths; exits and stairs are the capacity-constrained resources.
 */
public final class RasterNetworkBuilder {
    private static final double CELL_METERS = 0.4;
    private static final double MAX_SNAP_METERS = 0.8;
    private static final int SAMPLES = 3;
    private static final int MAX_FLOORS = 20;
    private static final int MAX_MARKS = 64;
    private static final int MAX_FLOOR_CELLS = 100_000;
    private static final int MAX_TOTAL_CELLS = 500_000;
    private static final int MAX_IMAGE_DIMENSION = 2048;

    public record StairLink(long fromMarkId, long toMarkId) { }
    public record Prepared(EvacuationNetwork network, Map<String, Integer> occupancy, List<String> warnings) { }

    public Prepared build(List<FloorDto> floors, ScenarioFullDto scenario, List<StairLink> stairLinks) {
        if (floors == null || floors.isEmpty() || floors.size() > MAX_FLOORS) {
            throw new IllegalArgumentException("Для растрового расчёта нужно от 1 до " + MAX_FLOORS + " этажей.");
        }
        if (scenario == null) throw new IllegalArgumentException("Не передан сценарий.");
        ParamsDto params = (scenario.params() == null ? ParamsDto.defaultParams() : scenario.params().withDefaults());
        validateParams(params);
        if (scenario.fire() != null) {
            throw new IllegalArgumentException("Расчёт пожара ещё не поддерживается; удалите очаг для базового сценария эвакуации.");
        }
        List<MarkDto> marks = scenario.marks() == null ? List.of() : scenario.marks();
        if (marks.size() > MAX_MARKS) throw new IllegalArgumentException("В сценарии допускается не более " + MAX_MARKS + " меток.");
        List<String> warnings = new ArrayList<>();
        warnings.add("Геометрия рассчитана по растровой сетке с ячейкой до 0,4 м. Проверьте проходимость и масштаб плана.");
        warnings.add("Число людей распределено поровну между метками «Люди»; это заданный сценарий, а не измеренная заполненность.");
        warnings.add("Коридоры между метками учитывают длину пути и стены, но плотность и слияния потоков в них пока не моделируются.");

        Map<Long, FloorDto> byFloor = new LinkedHashMap<>();
        Set<Integer> levels = new HashSet<>();
        if (floors.stream().anyMatch(Objects::isNull)) throw new IllegalArgumentException("Не передан этаж.");
        List<FloorDto> orderedFloors = floors.stream().sorted(Comparator.comparingInt(FloorDto::level).thenComparingLong(FloorDto::id)).toList();
        Map<Long, Grid> grids = new LinkedHashMap<>();
        int totalCells = 0;
        for (FloorDto floor : orderedFloors) {
            if (floor == null || floor.id() <= 0 || byFloor.put(floor.id(), floor) != null || !levels.add(floor.level())) {
                throw new IllegalArgumentException("Этажи должны иметь уникальные id и уровни.");
            }
            Grid grid = rasterize(floor, params.scale());
            totalCells += grid.wall.length;
            if (totalCells > MAX_TOTAL_CELLS) throw new IllegalArgumentException("Слишком большая суммарная растровая сетка; уменьшите план или проверьте масштаб.");
            grids.put(floor.id(), grid);
        }
        Map<Long, LocatedMark> located = new LinkedHashMap<>();
        if (marks.stream().anyMatch(Objects::isNull)) throw new IllegalArgumentException("Не передана метка.");
        List<MarkDto> orderedMarks = marks.stream().sorted(Comparator.comparingLong(MarkDto::id)).toList();
        List<Node> nodes = new ArrayList<>();
        List<Passage> passages = new ArrayList<>();
        for (MarkDto mark : orderedMarks) {
            if (mark == null || mark.id() <= 0 || located.containsKey(mark.id())) throw new IllegalArgumentException("Метки должны иметь уникальные положительные id.");
            Grid grid = grids.get(mark.floorId());
            if (grid == null) throw new IllegalArgumentException("Метка " + mark.id() + " ссылается на неизвестный этаж.");
            if (mark.kind() == null || !Set.of("spawn", "exit", "stair").contains(mark.kind())) throw new IllegalArgumentException("Неизвестный тип метки " + mark.id());
            if (!Double.isFinite(mark.x()) || !Double.isFinite(mark.y())) throw new IllegalArgumentException("Некорректные координаты метки " + mark.id());
            if (!mark.kind().equals("spawn")) positive(mark.width(), "Ширина метки " + mark.id());
            int cell = snap(grid, mark.x(), mark.y());
            if (cell < 0) throw new IllegalArgumentException("Метка " + mark.id() + " находится вне плана или дальше 0,8 м от проходимой области.");
            Position position = grid.position(cell);
            int original = grid.cell(mark.x(), mark.y());
            if (original >= 0 && grid.wall[original]) warnings.add("Метка " + mark.id() + " перенесена с препятствия в ближайшую проходимую ячейку (не далее 0,8 м).");
            LocatedMark lm = new LocatedMark(mark, grid, cell, position);
            located.put(mark.id(), lm);
            nodes.add(new Node(nodeId(mark.id()), String.valueOf(mark.floorId()), mark.kind(), label(mark), 10_000, position.x(), position.y()));
            if (mark.kind().equals("exit")) {
                passages.add(new Passage("exit:" + mark.id(), "exit", nodeId(mark.id()), "OUTSIDE",
                        mark.width() * params.exitFlow(), true, 0, 0, 0, List.of(position), List.of(position)));
            }
        }

        // Compress the walkable grid to a graph of annotated points. A shortest
        // path is retained as a polyline so playback uses the exact same geometry.
        for (FloorDto floor : orderedFloors) {
            List<LocatedMark> floorMarks = located.values().stream().filter(m -> m.mark.floorId() == floor.id()).toList();
            for (int i = 0; i + 1 < floorMarks.size(); i++) {
                LocatedMark from = floorMarks.get(i);
                Search search = search(from.grid, from.cell);
                for (int j = i + 1; j < floorMarks.size(); j++) {
                    LocatedMark to = floorMarks.get(j);
                    if (!Double.isFinite(search.distances[to.cell])) continue;
                    List<Position> path = path(from.grid, search.previous, from.cell, to.cell);
                    passages.add(new Passage("walk:" + from.mark.id() + ":" + to.mark.id(), "walk",
                            nodeId(from.mark.id()), nodeId(to.mark.id()), null, false,
                            search.distances[to.cell] / params.scale() / params.speed(), 0, 0, path, List.of(to.position)));
                }
            }
        }

        if (stairLinks != null && !stairLinks.isEmpty()) {
            Set<Long> linkedFrom = new HashSet<>();
            Set<String> pairs = new HashSet<>();
            for (StairLink link : stairLinks) {
                if (link == null || !linkedFrom.add(link.fromMarkId())) throw new IllegalArgumentException("У лестницы может быть только одна явная связь вниз.");
                LocatedMark from = located.get(link.fromMarkId()), to = located.get(link.toMarkId());
                requireStairLink(from, to, byFloor);
                String pair = from.mark.id() + ":" + to.mark.id();
                if (!pairs.add(pair)) throw new IllegalArgumentException("Повторная связь лестницы.");
                passages.add(stair(from, to, params));
            }
            for (LocatedMark mark : located.values()) {
                if (mark.mark.kind().equals("stair") && !linkedFrom.contains(mark.mark.id()) &&
                        orderedFloors.get(0).level() < byFloor.get(mark.mark.floorId()).level()) {
                    warnings.add("Для лестницы " + mark.mark.id() + " не задана явная связь вниз; автоматическая связь не создавалась.");
                }
            }
        } else {
            boolean inferred = false;
            for (int i = 1; i < orderedFloors.size(); i++) {
                long upperFloor = orderedFloors.get(i).id(), lowerFloor = orderedFloors.get(i - 1).id();
                for (LocatedMark from : located.values()) {
                    if (from.mark.floorId() != upperFloor || !from.mark.kind().equals("stair")) continue;
                    List<LocatedMark> targets = located.values().stream().filter(to -> to.mark.floorId() == lowerFloor && to.mark.kind().equals("stair") &&
                            Math.abs(to.mark.x() - from.mark.x()) < 1e-6 && Math.abs(to.mark.y() - from.mark.y()) < 1e-6).toList();
                    if (targets.size() == 1) {
                        passages.add(stair(from, targets.get(0), params));
                        inferred = true;
                    } else warnings.add("Лестница " + from.mark.id() + " не связана: укажите пару лестниц между этажами явно.");
                }
            }
            if (inferred) warnings.add("Лестницы связаны по прежнему соглашению: одинаковые координаты на соседних уровнях. Подтвердите пары лестниц в настройках.");
        }
        List<LocatedMark> spawns = located.values().stream().filter(m -> m.mark.kind().equals("spawn")).toList();
        if (params.people() > 0 && spawns.isEmpty()) throw new IllegalArgumentException("Добавьте хотя бы одну метку «Люди», чтобы задать начальное размещение.");
        Map<String, Integer> occupancy = new LinkedHashMap<>();
        for (int i = 0; i < spawns.size(); i++) {
            int count = params.people() / spawns.size() + (i < params.people() % spawns.size() ? 1 : 0);
            occupancy.put(nodeId(spawns.get(i).mark.id()), count);
        }
        return new Prepared(new EvacuationNetwork(List.copyOf(nodes), List.copyOf(passages)), Collections.unmodifiableMap(occupancy), List.copyOf(warnings));
    }

    private static Passage stair(LocatedMark from, LocatedMark to, ParamsDto params) {
        return new Passage("stairs:" + from.mark.id() + ":" + to.mark.id(), "stairs", nodeId(from.mark.id()), nodeId(to.mark.id()),
                Math.min(from.mark.width(), to.mark.width()) * params.stairFlow(), true, 0, params.stairTime(), 0,
                List.of(from.position), List.of(to.position));
    }

    private static void requireStairLink(LocatedMark from, LocatedMark to, Map<Long, FloorDto> floors) {
        if (from == null || to == null || !from.mark.kind().equals("stair") || !to.mark.kind().equals("stair")) {
            throw new IllegalArgumentException("Связь лестницы должна указывать две существующие метки «Лестница».");
        }
        if (floors.get(from.mark.floorId()).level() <= floors.get(to.mark.floorId()).level()) {
            throw new IllegalArgumentException("Лестница должна вести на более низкий уровень.");
        }
    }

    private static String nodeId(long id) { return "mark:" + id; }
    private static String label(MarkDto mark) {
        return switch (mark.kind()) { case "spawn" -> "Люди " + mark.id(); case "exit" -> "Выход " + mark.id(); default -> "Лестница " + mark.id(); };
    }
    private static void validateParams(ParamsDto p) {
        if (p.people() < 0 || p.people() > 10_000) throw new IllegalArgumentException("Количество людей должно быть от 0 до 10000.");
        positive(p.scale(), "Масштаб"); positive(p.speed(), "Скорость"); positive(p.stairTime(), "Время лестницы");
        positive(p.exitFlow(), "Пропускная способность выхода"); positive(p.stairFlow(), "Пропускная способность лестницы");
    }
    private static void positive(Double number, String label) {
        if (number == null || !Double.isFinite(number) || number <= 0) throw new IllegalArgumentException(label + " должна быть конечной и положительной.");
    }

    private static Grid rasterize(FloorDto floor, double unitsPerMeter) {
        double[] vb = floor.vb();
        if (vb == null || vb.length != 4 || Arrays.stream(vb).anyMatch(v -> !Double.isFinite(v)) || vb[2] <= 0 || vb[3] <= 0) {
            throw new IllegalArgumentException("Некорректный viewBox этажа " + floor.id());
        }
        double rawWidth = Math.max(1, Math.ceil(vb[2] / (CELL_METERS * unitsPerMeter)));
        double rawHeight = Math.max(1, Math.ceil(vb[3] / (CELL_METERS * unitsPerMeter)));
        if (!Double.isFinite(rawWidth) || !Double.isFinite(rawHeight) || rawWidth < 1 || rawHeight < 1 ||
                rawWidth * rawHeight > MAX_FLOOR_CELLS || rawWidth * SAMPLES > MAX_IMAGE_DIMENSION || rawHeight * SAMPLES > MAX_IMAGE_DIMENSION) {
            throw new IllegalArgumentException("Растровая сетка этажа слишком велика; проверьте масштаб или разделите план.");
        }
        int width = (int) rawWidth, height = (int) rawHeight;
        try {
            String safe = SvgSafety.sanitize(floor.svg());
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            Document document = factory.newDocumentBuilder().parse(new InputSource(new StringReader(safe)));
            var root = document.getDocumentElement();
            root.setAttribute("viewBox", vb[0] + " " + vb[1] + " " + vb[2] + " " + vb[3]);
            root.setAttribute("width", String.valueOf(width * SAMPLES));
            root.setAttribute("height", String.valueOf(height * SAMPLES));
            root.setAttribute("preserveAspectRatio", "none");
            TransformerFactory transformers = TransformerFactory.newInstance();
            transformers.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            transformers.setAttribute(XMLConstants.ACCESS_EXTERNAL_STYLESHEET, "");
            StringWriter output = new StringWriter();
            transformers.newTransformer().transform(new DOMSource(document), new StreamResult(output));
            ImageRenderer renderer = new ImageRenderer();
            renderer.addTranscodingHint(ImageTranscoder.KEY_WIDTH, (float) width * SAMPLES);
            renderer.addTranscodingHint(ImageTranscoder.KEY_HEIGHT, (float) height * SAMPLES);
            renderer.addTranscodingHint(ImageTranscoder.KEY_BACKGROUND_COLOR, Color.WHITE);
            renderer.addTranscodingHint(ImageTranscoder.KEY_EXECUTE_ONLOAD, false);
            renderer.transcode(new TranscoderInput(new StringReader(output.toString())), null);
            boolean[] wall = new boolean[width * height];
            for (int y = 0; y < renderer.image.getHeight(); y++) {
                for (int x = 0; x < renderer.image.getWidth(); x++) {
                    int rgba = renderer.image.getRGB(x, y);
                    int alpha = rgba >>> 24;
                    double red = ((rgba >> 16) & 255) * alpha / 255.0 + 255 - alpha;
                    double green = ((rgba >> 8) & 255) * alpha / 255.0 + 255 - alpha;
                    double blue = (rgba & 255) * alpha / 255.0 + 255 - alpha;
                    if (0.3 * red + 0.59 * green + 0.11 * blue < 150) wall[(y / SAMPLES) * width + x / SAMPLES] = true;
                }
            }
            return new Grid(String.valueOf(floor.id()), width, height, vb[0], vb[1], vb[2] / width, vb[3] / height, unitsPerMeter, wall);
        } catch (IllegalArgumentException e) { throw e; }
        catch (Exception e) { throw new IllegalArgumentException("Не удалось построить растровую геометрию этажа " + floor.id() + ": " + e.getMessage(), e); }
    }

    private static int snap(Grid grid, double x, double y) {
        int original = grid.cell(x, y);
        if (original < 0) return -1;
        if (!grid.wall[original]) return original;
        int centerX = original % grid.width, centerY = original / grid.width;
        int radiusX = (int) Math.ceil(MAX_SNAP_METERS * grid.unitsPerMeter / grid.stepX);
        int radiusY = (int) Math.ceil(MAX_SNAP_METERS * grid.unitsPerMeter / grid.stepY);
        int best = -1;
        double bestDistance = MAX_SNAP_METERS * grid.unitsPerMeter;
        for (int yy = Math.max(0, centerY - radiusY); yy <= Math.min(grid.height - 1, centerY + radiusY); yy++) {
            for (int xx = Math.max(0, centerX - radiusX); xx <= Math.min(grid.width - 1, centerX + radiusX); xx++) {
                int index = yy * grid.width + xx;
                if (grid.wall[index]) continue;
                Position p = grid.position(index);
                double distance = Math.hypot(p.x() - x, p.y() - y);
                if (distance < bestDistance || (distance == bestDistance && (best < 0 || index < best))) { bestDistance = distance; best = index; }
            }
        }
        return best;
    }

    private static Search search(Grid grid, int start) {
        double[] distances = new double[grid.wall.length];
        Arrays.fill(distances, Double.POSITIVE_INFINITY);
        int[] previous = new int[grid.wall.length]; Arrays.fill(previous, -1);
        PriorityQueue<Visit> queue = new PriorityQueue<>(Comparator.comparingDouble(Visit::distance).thenComparingInt(Visit::cell));
        distances[start] = 0;
        queue.add(new Visit(start, 0));
        while (!queue.isEmpty()) {
            Visit visit = queue.remove();
            if (visit.distance != distances[visit.cell]) continue;
            int x = visit.cell % grid.width, y = visit.cell / grid.width;
            for (int dy = -1; dy <= 1; dy++) for (int dx = -1; dx <= 1; dx++) {
                if (dx == 0 && dy == 0) continue;
                int nx = x + dx, ny = y + dy;
                if (nx < 0 || ny < 0 || nx >= grid.width || ny >= grid.height) continue;
                int next = ny * grid.width + nx;
                if (grid.wall[next]) continue;
                if (dx != 0 && dy != 0 && (grid.wall[y * grid.width + nx] || grid.wall[ny * grid.width + x])) continue;
                double distance = visit.distance + Math.hypot(dx * grid.stepX, dy * grid.stepY);
                if (distance < distances[next]) {
                    distances[next] = distance; previous[next] = visit.cell;
                    queue.add(new Visit(next, distance));
                }
            }
        }
        return new Search(distances, previous);
    }

    private static List<Position> path(Grid grid, int[] previous, int start, int end) {
        List<Integer> reverse = new ArrayList<>();
        for (int current = end; current != -1; current = previous[current]) { reverse.add(current); if (current == start) break; }
        Collections.reverse(reverse);
        List<Position> result = new ArrayList<>();
        for (int i = 0; i < reverse.size(); i++) {
            if (i > 0 && i + 1 < reverse.size()) {
                int a = reverse.get(i - 1), b = reverse.get(i), c = reverse.get(i + 1);
                int dx1 = b % grid.width - a % grid.width, dy1 = b / grid.width - a / grid.width;
                int dx2 = c % grid.width - b % grid.width, dy2 = c / grid.width - b / grid.width;
                if (dx1 == dx2 && dy1 == dy2) continue;
            }
            result.add(grid.position(reverse.get(i)));
        }
        return List.copyOf(result);
    }

    private static final class ImageRenderer extends ImageTranscoder {
        BufferedImage image;
        @Override public BufferedImage createImage(int width, int height) { return new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB); }
        @Override public void writeImage(BufferedImage image, TranscoderOutput output) { this.image = image; }
    }
    private record Grid(String floorId, int width, int height, double x, double y, double stepX, double stepY, double unitsPerMeter, boolean[] wall) {
        int cell(double px, double py) {
            int xx = (int) Math.floor((px - x) / stepX), yy = (int) Math.floor((py - y) / stepY);
            return xx < 0 || yy < 0 || xx >= width || yy >= height ? -1 : yy * width + xx;
        }
        Position position(int index) { return new Position(floorId, x + (index % width + .5) * stepX, y + (index / width + .5) * stepY); }
    }
    private record LocatedMark(MarkDto mark, Grid grid, int cell, Position position) { }
    private record Search(double[] distances, int[] previous) { }
    private record Visit(int cell, double distance) { }
}

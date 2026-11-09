package com.artemyasnik.evac.server.simulation;

import com.artemyasnik.evac.server.dto.FloorDto;
import com.artemyasnik.evac.server.parser.SvgSafety;
import com.artemyasnik.model.*;
import com.artemyasnik.parser.SvgFloorPlanParser;
import com.artemyasnik.simulation.EvacuationNetwork;
import com.artemyasnik.simulation.EvacuationNetwork.*;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Adapts all annotated floors to the same network used by marked raster plans. */
public final class SemanticNetworkBuilder {
    public record Prepared(EvacuationNetwork network, List<String> warnings) { }
    private record Parsed(FloorDto floor, FloorPlan plan) { }

    public boolean hasModel(FloorDto floor) {
        var doc = SvgSafety.read(floor.svg());
        var all = doc.getElementsByTagName("*");
        for (int i = 0; i < all.getLength(); i++) {
            var element = (org.w3c.dom.Element) all.item(i);
            if (Set.of("room", "corridor").contains(element.getAttribute("data-type"))) return true;
        }
        return false;
    }

    public Prepared build(List<FloorDto> floors, double speed, double fallbackScaleUnitsPerMeter, double stairSeconds) {
        List<String> warnings = new ArrayList<>();
        Map<Integer, Parsed> levels = new LinkedHashMap<>();
        Map<String, Node> nodes = new LinkedHashMap<>();
        List<Passage> passages = new ArrayList<>();
        for (FloorDto floor : floors) {
            var document = SvgSafety.read(floor.svg());
            var root = document.getDocumentElement();
            if (root.hasAttribute("data-floor") && !root.getAttribute("data-floor").equals(Integer.toString(floor.level()))) {
                warnings.add("Этаж «" + floor.name() + "»: используется уровень " + floor.level() + " из карточки этажа.");
            }
            root.setAttribute("data-floor", Integer.toString(floor.level()));
            if (!root.hasAttribute("data-scale")) root.setAttribute("data-scale", Double.toString(1 / fallbackScaleUnitsPerMeter));
            // CSS transforms would render differently from the semantic geometry parser.
            if (SvgSafety.serialize(document).matches("(?is).*\\btransform\\s*:.*")) {
                throw new IllegalArgumentException("Для размеченного плана используйте SVG transform вместо CSS transform");
            }
            FloorPlan plan;
            try {
                plan = new SvgFloorPlanParser().parse(new ByteArrayInputStream(SvgSafety.serialize(document).getBytes(StandardCharsets.UTF_8)));
            } catch (Exception e) { throw new IllegalArgumentException("Этаж «" + floor.name() + "»: " + e.getMessage(), e); }
            if (plan.getRooms().isEmpty() && plan.getCorridors().isEmpty()) throw new IllegalArgumentException("В размеченном плане нет помещений");
            if (levels.putIfAbsent(floor.level(), new Parsed(floor, plan)) != null) throw new IllegalArgumentException("Повторяющийся уровень этажа");
            plan.getRooms().values().forEach(room -> addNode(nodes, floor, room, "room", room.getName(), room.getCapacity()));
            plan.getCorridors().values().forEach(room -> addNode(nodes, floor, room, "corridor", room.getId(), room.getCapacity()));
        }
        for (Parsed parsed : levels.values()) {
            List<Connector> connections = new ArrayList<>(parsed.plan.getDoors());
            connections.addAll(parsed.plan.getExits());
            for (Connector connector : connections) {
                boolean exit = connector instanceof EmergencyExit;
                List<String> ends = connector.getConnectsIds();
                String from = scoped(parsed.floor.id(), ends.get(0)), to = scoped(parsed.floor.id(), ends.get(1));
                if (from.equals("OUTSIDE")) { String swap = from; from = to; to = swap; }
                Position door = point(parsed.floor.id(), connector);
                Node start = nodes.get(from), end = nodes.get(to);
                List<Position> before = List.of(position(start), door);
                List<Position> after = exit ? List.of(door) : List.of(door, position(end));
                passages.add(new Passage(scoped(parsed.floor.id(), connector.getId()), exit ? "exit" : "door", from, to,
                        connector.getThroughput(), exit, distance(before) * parsed.plan.getScaleMetersPerUnit() / speed,
                        0, exit ? 0 : distance(after) * parsed.plan.getScaleMetersPerUnit() / speed, before, after));
            }
        }
        Map<String, Passage> stairs = new LinkedHashMap<>();
        for (Parsed owner : levels.values()) {
            for (Stairs stair : owner.plan.getStairs()) {
                var refs = stair.getConnectsIds();
                Parsed a = requireFloor(levels, stair.getFloorFrom()), b = requireFloor(levels, stair.getFloorTo());
                String aId = scoped(a.floor.id(), base(refs.get(0))), bId = scoped(b.floor.id(), base(refs.get(1)));
                if (!nodes.containsKey(aId) || !nodes.containsKey(bId)) throw new IllegalArgumentException("Лестница " + stair.getId() + ": неизвестное помещение на другом этаже");
                Parsed lower = a.floor.level() < b.floor.level() ? a : b;
                Parsed upper = lower == a ? b : a;
                String lowId = lower == a ? aId : bId, highId = lower == a ? bId : aId;
                String id = "stairs:" + lowId + ":" + highId + ":" + stair.getId();
                boolean both = stair.getDirection().equals("both");
                String from = stair.getDirection().equals("up") ? lowId : highId;
                String to = from.equals(lowId) ? highId : lowId;
                Parsed fromFloor = from.equals(lowId) ? lower : upper, toFloor = to.equals(lowId) ? lower : upper;
                Position entry = stairPoint(fromFloor, stair, nodes.get(from));
                Position leave = stairPoint(toFloor, stair, nodes.get(to));
                List<Position> before = List.of(position(nodes.get(from)), entry), after = List.of(leave, position(nodes.get(to)));
                double travel = stairSeconds;
                String declared = stair.getRawAttributes().get("data-travel-time-seconds");
                if (declared != null) {
                    try { travel = Double.parseDouble(declared); }
                    catch (NumberFormatException e) { throw new IllegalArgumentException("Некорректное время лестницы " + stair.getId()); }
                    if (!Double.isFinite(travel) || travel <= 0) throw new IllegalArgumentException("Время лестницы должно быть положительным");
                }
                Passage passage = new Passage(id, "stairs", from, to, stair.getThroughput(), !both,
                        distance(before) * fromFloor.plan.getScaleMetersPerUnit() / speed, travel,
                        distance(after) * toFloor.plan.getScaleMetersPerUnit() / speed, before, after);
                Passage previous = stairs.putIfAbsent(id, passage);
                if (previous != null && (!previous.from().equals(from) || !previous.to().equals(to)
                        || previous.directed() != passage.directed() || !previous.throughput().equals(passage.throughput())
                        || previous.traversalSeconds() != travel)) {
                    throw new IllegalArgumentException("Декларации лестницы " + stair.getId() + " на этажах расходятся");
                }
            }
        }
        passages.addAll(stairs.values());
        warnings.add("Размеченные SVG: data-scale задаёт метры на единицу SVG; заполненность вводится отдельно по помещениям.");
        warnings.add("Траектории в помещениях приближены центрами и проходами; внутренние препятствия не рассчитываются.");
        return new Prepared(new EvacuationNetwork(List.copyOf(nodes.values()), List.copyOf(passages)), List.copyOf(warnings));
    }

    private static void addNode(Map<String, Node> nodes, FloorDto floor, BuildingElement element, String kind, String name, int capacity) {
        Position point = point(floor.id(), element);
        String id = scoped(floor.id(), element.getId());
        if (nodes.putIfAbsent(id, new Node(id, Long.toString(floor.id()), kind, name, capacity, point.x(), point.y())) != null) {
            throw new IllegalArgumentException("Повторяющийся узел " + id);
        }
    }
    private static Parsed requireFloor(Map<Integer, Parsed> levels, int level) {
        Parsed result = levels.get(level);
        if (result == null) throw new IllegalArgumentException("Лестница ссылается на отсутствующий этаж " + level);
        return result;
    }
    private static Position stairPoint(Parsed floor, Stairs stair, Node fallback) {
        for (Stairs candidate : floor.plan.getStairs()) {
            if (candidate.getId().equals(stair.getId())) return point(floor.floor.id(), candidate);
        }
        return position(fallback);
    }
    private static String base(String ref) { return ref.substring(0, ref.lastIndexOf('@')); }
    private static String scoped(long floorId, String id) { return id.equals("OUTSIDE") ? id : floorId + "/" + id; }
    private static Position point(long floorId, BuildingElement element) {
        var box = element.getBoundingBox();
        return new Position(Long.toString(floorId), box.minX() / 2 + box.maxX() / 2, box.minY() / 2 + box.maxY() / 2);
    }
    private static Position position(Node node) { return new Position(node.floorId(), node.x(), node.y()); }
    private static double distance(List<Position> path) {
        double sum = 0;
        for (int i = 1; i < path.size(); i++) sum += Math.hypot(path.get(i).x() - path.get(i - 1).x(), path.get(i).y() - path.get(i - 1).y());
        return sum;
    }
}

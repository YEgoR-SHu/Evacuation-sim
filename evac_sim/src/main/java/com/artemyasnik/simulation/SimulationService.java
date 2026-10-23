package com.artemyasnik.simulation;

import com.artemyasnik.model.*;
import org.jgrapht.GraphPath;
import org.jgrapht.alg.shortestpath.DijkstraShortestPath;
import org.jgrapht.graph.DirectedWeightedMultigraph;
import org.jgrapht.graph.MaskSubgraph;

import java.util.*;

import static com.artemyasnik.model.EmergencyExit.OUTSIDE_NODE_ID;
import static com.artemyasnik.simulation.AnalysisResult.*;

/**
 * Событийная модель одного этажа. Дверь — общий ресурс для обоих направлений;
 * поиск JGraphT учитывает текущую очередь и людей, идущих к этому проёму.
 * Каждый человек проходит простой путь: уже посещённые узлы исключаются из поиска.
 */
public final class SimulationService {
    public static final int MAX_PEOPLE = 10_000;
    public static final int MAX_NODES = 2_000;
    private static final int MAX_CONNECTIONS = 5_000;
    private static final long MAX_PERSON_NODES = 2_000_000;
    private static final long MAX_ROUTING_WORK = 50_000_000;

    public AnalysisResult analyze(FloorPlan plan, Map<String, Integer> occupancy,
                                  Set<String> blockedConnections, double walkingSpeedMps) {
        if (plan == null) throw new IllegalArgumentException("Не передан план этажа.");
        positiveFinite(walkingSpeedMps, "Скорость движения");
        positiveFinite(plan.getScaleMetersPerUnit(), "Масштаб плана");
        Map<String, Integer> counts = occupancy == null ? Map.of() : occupancy;
        Set<String> blocked = blockedConnections == null ? Set.of() : blockedConnections;
        LinkedHashMap<String, NodeView> nodes = buildNodes(plan);
        if (nodes.size() > MAX_NODES) throw new IllegalArgumentException("Допускается не более " + MAX_NODES + " узлов.");

        List<Connector> connectors = new ArrayList<>();
        connectors.addAll(plan.getDoors());
        connectors.addAll(plan.getExits());
        if (connectors.size() > MAX_CONNECTIONS) throw new IllegalArgumentException("Слишком много связей на плане.");
        LinkedHashMap<String, Passage> passages = new LinkedHashMap<>();
        List<ConnectionView> connectionViews = new ArrayList<>();
        DirectedWeightedMultigraph<String, Leg> graph = new DirectedWeightedMultigraph<>(null, null);
        nodes.keySet().forEach(graph::addVertex);
        graph.addVertex(OUTSIDE_NODE_ID);
        for (Connector connector : connectors) {
            String id = connector.getId();
            if (id == null || id.isBlank() || passages.containsKey(id) || nodes.containsKey(id)) {
                throw new IllegalArgumentException("Неуникальный или пустой id связи: " + id);
            }
            List<String> ends = connector.getConnectsIds();
            if (ends.size() != 2 || ends.get(0).equals(ends.get(1))) {
                throw new IllegalArgumentException("Связь " + id + " должна соединять два разных узла.");
            }
            boolean exit = connector instanceof EmergencyExit;
            if (exit != (ends.contains(OUTSIDE_NODE_ID))) {
                throw new IllegalArgumentException("Только аварийный выход должен соединяться с OUTSIDE: " + id);
            }
            for (String end : ends) {
                if (!graph.containsVertex(end)) throw new IllegalArgumentException("Связь " + id + ": неизвестный узел " + end);
            }
            positiveFinite(connector.getThroughput(), "Пропускная способность " + id);
            double serviceSeconds = 1.0 / connector.getThroughput();
            positiveFinite(serviceSeconds, "Время прохода " + id);
            Point2D point = center(connector);
            Passage passage = new Passage(id, serviceSeconds);
            passages.put(id, passage);
            connectionViews.add(new ConnectionView(id, exit ? "exit" : "door", ends.get(0), ends.get(1),
                    connector.getThroughput(), connector.getWidthM(), point.x(), point.y(), blocked.contains(id)));
            if (blocked.contains(id)) continue;
            addLeg(graph, nodes, passage, ends.get(0), ends.get(1), point, plan.getScaleMetersPerUnit(), walkingSpeedMps);
            if (!exit) addLeg(graph, nodes, passage, ends.get(1), ends.get(0), point, plan.getScaleMetersPerUnit(), walkingSpeedMps);
            // Выход ориентирован наружу даже при записи data-connects="OUTSIDE,C1".
            else if (ends.get(0).equals(OUTSIDE_NODE_ID)) {
                graph.removeAllEdges(ends.get(0), ends.get(1));
                addLeg(graph, nodes, passage, ends.get(1), ends.get(0), point, plan.getScaleMetersPerUnit(), walkingSpeedMps);
            }
        }
        for (String id : blocked) {
            if (!passages.containsKey(id)) throw new IllegalArgumentException("Неизвестная блокируемая связь: " + id);
        }
        int totalPeople = validateOccupancy(nodes, counts);
        if ((long) totalPeople * nodes.size() > MAX_PERSON_NODES) {
            throw new IllegalArgumentException("Сценарий слишком велик для начальной модели: уменьшите число людей или узлов.");
        }
        long routingWork = (long) totalPeople * nodes.size() * (nodes.size() + 2L * connectors.size());
        if (routingWork > MAX_ROUTING_WORK) {
            throw new IllegalArgumentException("Сценарий превышает лимит вычислений начальной модели: уменьшите число людей, узлов или связей.");
        }

        List<String> warnings = new ArrayList<>();
        warnings.add("Маршруты учитывают текущие очереди; глобальный минимум времени эвакуации не гарантируется.");
        warnings.add("Вместимость проверяется при начальном размещении. Плотность людей и заполнение промежуточных помещений пока не моделируются.");
        warnings.add("Путь внутри помещения приближён отрезками через центр его габаритов и проём; препятствия внутри помещений не учитываются.");
        if (!plan.getStairs().isEmpty()) warnings.add("Межэтажные лестницы исключены: расчёт выполняется только для одного этажа и его выходов наружу.");

        PriorityQueue<Event> events = new PriorityQueue<>(Comparator.comparingDouble(Event::time).thenComparingLong(Event::order));
        long order = 0;
        // Порядок не зависит от реализации входной Map.
        for (String source : nodes.keySet()) {
            int count = counts.getOrDefault(source, 0);
            for (int i = 0; i < count; i++) events.add(new Event(0, order++, new Person(source), null));
        }
        LinkedHashMap<RouteKey, RouteAccumulator> completed = new LinkedHashMap<>();
        LinkedHashMap<String, Integer> unreachableSources = new LinkedHashMap<>();
        int evacuated = 0, unreachable = 0;
        double endTime = 0;
        while (!events.isEmpty()) {
            Event event = events.remove();
            Person person = event.person;
            if (event.leg != null) {
                Leg leg = event.leg;
                Passage passage = leg.passage;
                passage.inFlight--;
                double start = Math.max(event.time, passage.readyAt);
                double finish = start + passage.serviceSeconds;
                if (!Double.isFinite(finish) || finish <= start) {
                    throw new IllegalArgumentException("Время прохода " + passage.id + " вышло за допустимую точность расчёта.");
                }
                passage.readyAt = finish;
                passage.people++;
                passage.maxWait = Math.max(passage.maxWait, start - event.time);
                passage.busySeconds += passage.serviceSeconds;
                double arrival = finish + leg.after;
                if (!Double.isFinite(arrival) || (leg.after > 0 && arrival <= finish)) {
                    throw new IllegalArgumentException("Время расчёта вышло за числовой диапазон.");
                }
                person.current = leg.to;
                events.add(new Event(arrival, order++, person, null));
                continue;
            }
            if (person.current.equals(OUTSIDE_NODE_ID)) {
                evacuated++;
                endTime = Math.max(endTime, event.time);
                RouteKey key = new RouteKey(person.source, List.copyOf(person.nodeIds), List.copyOf(person.connectionIds));
                completed.computeIfAbsent(key, unused -> new RouteAccumulator()).accept(event.time);
                continue;
            }
            person.visited.add(person.current);
            for (Leg leg : graph.edgeSet()) {
                double wait = Math.max(0, leg.passage.readyAt - event.time - leg.before)
                        + leg.passage.inFlight * leg.passage.serviceSeconds;
                double weight = leg.before + leg.after + leg.passage.serviceSeconds + wait;
                if (!Double.isFinite(weight)) throw new IllegalArgumentException("Вес маршрута вышел за числовой диапазон.");
                graph.setEdgeWeight(leg, weight);
            }
            String current = person.current;
            var available = new MaskSubgraph<>(graph,
                    node -> person.visited.contains(node) && !node.equals(current), leg -> false);
            GraphPath<String, Leg> path = new DijkstraShortestPath<>(available).getPath(current, OUTSIDE_NODE_ID);
            if (path == null) {
                unreachable++;
                unreachableSources.merge(person.source, 1, Integer::sum);
                continue;
            }
            Leg next = path.getEdgeList().get(0);
            next.passage.inFlight++;
            person.connectionIds.add(next.passage.id);
            person.nodeIds.add(next.to);
            double arrival = event.time + next.before;
            if (!Double.isFinite(arrival) || (next.before > 0 && arrival <= event.time)) {
                throw new IllegalArgumentException("Время движения вышло за допустимую точность расчёта.");
            }
            events.add(new Event(arrival, order++, person, next));
        }
        unreachableSources.forEach((source, people) -> warnings.add("Из узла " + source + " нет доступного пути наружу: " + people + " чел."));
        List<RouteView> routes = completed.entrySet().stream().map(entry -> {
            RouteKey key = entry.getKey();
            RouteAccumulator value = entry.getValue();
            return new RouteView(key.source, key.nodes, key.connections, value.people, value.first, value.last);
        }).toList();
        List<ConnectionUsage> usage = passages.values().stream()
                .filter(p -> p.people > 0)
                .sorted(Comparator.comparingDouble((Passage p) -> p.maxWait).reversed().thenComparing(p -> p.id))
                .map(p -> new ConnectionUsage(p.id, p.people, p.maxWait, p.busySeconds)).toList();
        return new AnalysisResult(plan.getFloorNumber(), plan.getScaleMetersPerUnit(), totalPeople, evacuated,
                unreachable, endTime, List.copyOf(nodes.values()), List.copyOf(connectionViews), routes, usage, List.copyOf(warnings));
    }

    private LinkedHashMap<String, NodeView> buildNodes(FloorPlan plan) {
        LinkedHashMap<String, NodeView> nodes = new LinkedHashMap<>();
        for (Room room : plan.getRooms().values()) {
            addNode(nodes, room, "room", room.getName(), room.getCapacity());
        }
        for (Corridor corridor : plan.getCorridors().values()) {
            addNode(nodes, corridor, "corridor", corridor.getId(), corridor.getCapacity());
        }
        return nodes;
    }

    private void addNode(Map<String, NodeView> nodes, BuildingElement element, String kind, String name, int capacity) {
        String id = element.getId();
        if (id == null || id.isBlank() || id.equals(OUTSIDE_NODE_ID) || nodes.containsKey(id)) {
            throw new IllegalArgumentException("Неуникальный или зарезервированный id узла: " + id);
        }
        if (capacity < 0) throw new IllegalArgumentException("Отрицательная вместимость узла " + id);
        Point2D center = center(element);
        nodes.put(id, new NodeView(id, kind, name, capacity, center.x(), center.y()));
    }

    private int validateOccupancy(Map<String, NodeView> nodes, Map<String, Integer> occupancy) {
        long total = 0;
        for (var entry : occupancy.entrySet()) {
            NodeView node = nodes.get(entry.getKey());
            if (node == null) throw new IllegalArgumentException("Неизвестный узел размещения людей: " + entry.getKey());
            Integer count = entry.getValue();
            if (count == null || count < 0 || count > node.capacity()) {
                throw new IllegalArgumentException("Число людей в " + entry.getKey() + " должно быть от 0 до " + node.capacity());
            }
            total += count;
            if (total > MAX_PEOPLE) throw new IllegalArgumentException("Допускается не более " + MAX_PEOPLE + " человек.");
        }
        return (int) total;
    }

    private void addLeg(DirectedWeightedMultigraph<String, Leg> graph, Map<String, NodeView> nodes,
                        Passage passage, String from, String to, Point2D door, double scale, double speed) {
        NodeView start = nodes.get(from), end = nodes.get(to);
        double before = start == null ? 0 : Math.hypot(start.x() - door.x(), start.y() - door.y()) * scale / speed;
        double after = end == null ? 0 : Math.hypot(end.x() - door.x(), end.y() - door.y()) * scale / speed;
        if (!Double.isFinite(before) || !Double.isFinite(after)) throw new IllegalArgumentException("Слишком большая геометрия связи " + passage.id);
        Leg leg = new Leg(passage, from, to, before, after);
        graph.addEdge(from, to, leg);
        graph.setEdgeWeight(leg, before + after + passage.serviceSeconds);
    }

    private Point2D center(BuildingElement element) {
        BoundingBox bounds = element.getBoundingBox();
        double x = bounds.minX() / 2 + bounds.maxX() / 2;
        double y = bounds.minY() / 2 + bounds.maxY() / 2;
        if (!Double.isFinite(x) || !Double.isFinite(y)) throw new IllegalArgumentException("Некорректная геометрия " + element.getId());
        return new Point2D(x, y);
    }

    private void positiveFinite(double value, String label) {
        if (!Double.isFinite(value) || value <= 0) throw new IllegalArgumentException(label + " должна быть конечным положительным числом.");
    }

    private record Leg(Passage passage, String from, String to, double before, double after) { }
    private record Event(double time, long order, Person person, Leg leg) { }
    private record RouteKey(String source, List<String> nodes, List<String> connections) { }

    private static final class Passage {
        final String id;
        final double serviceSeconds;
        double readyAt, maxWait, busySeconds;
        int inFlight, people;
        Passage(String id, double serviceSeconds) { this.id = id; this.serviceSeconds = serviceSeconds; }
    }

    private static final class Person {
        final String source;
        final Set<String> visited = new HashSet<>();
        final List<String> nodeIds = new ArrayList<>();
        final List<String> connectionIds = new ArrayList<>();
        String current;
        Person(String source) { this.source = source; this.current = source; nodeIds.add(source); }
    }

    private static final class RouteAccumulator {
        int people;
        double first = Double.POSITIVE_INFINITY, last;
        void accept(double time) { people++; first = Math.min(first, time); last = Math.max(last, time); }
    }
}

package com.artemyasnik.simulation;

import org.jgrapht.GraphPath;
import org.jgrapht.alg.shortestpath.DijkstraShortestPath;
import org.jgrapht.graph.DirectedWeightedMultigraph;
import org.jgrapht.graph.MaskSubgraph;

import java.util.*;

import static com.artemyasnik.simulation.EvacuationNetwork.*;
import static com.artemyasnik.simulation.NetworkResult.*;

/**
 * Deterministic, event-based evacuation on a building network. All importers use
 * this engine; SVG/raster geometry and storage belong to their respective adapters.
 */
public final class NetworkSimulationService {
    public static final int MAX_PEOPLE = 10_000;
    public static final int MAX_NODES = 2_000;
    public static final int MAX_PASSAGES = 5_000;
    public static final long MAX_PERSON_NODES = 2_000_000;
    public static final long MAX_ROUTING_WORK = 50_000_000;
    private static final int MAX_GEOMETRY_POINTS = 200_000;
    private static final int MAX_TIMELINE_POINTS = 500_000;
    private static final int MAX_ID_LENGTH = 256;

    public enum RoutingStrategy { QUEUE_AWARE, SHORTEST_PATH }

    /** Compare two reproducible strategies and keep the better complete result. */
    public NetworkResult analyze(EvacuationNetwork network, Map<String, Integer> occupancy,
                                 Set<String> blockedConnections) {
        Input input = validate(network, occupancy, blockedConnections);
        NetworkResult queue = run(input, RoutingStrategy.QUEUE_AWARE);
        NetworkResult shortest = run(input, RoutingStrategy.SHORTEST_PATH);
        NetworkResult selected = better(shortest, queue) ? shortest : queue;
        return new NetworkResult(selected.totalPeople(), selected.evacuatedPeople(), selected.unreachablePeople(),
                selected.evacuationTimeSeconds(), selected.strategy(),
                List.of(comparison(queue), comparison(shortest)), selected.nodes(), selected.passages(),
                selected.routes(), selected.bottlenecks(), selected.people(), selected.warnings());
    }

    /** Run one strategy, useful for inspectable comparisons and the v0.2 API adapter. */
    public NetworkResult analyze(EvacuationNetwork network, Map<String, Integer> occupancy,
                                 Set<String> blockedConnections, RoutingStrategy strategy) {
        if (strategy == null) throw new IllegalArgumentException("Не задана стратегия маршрутизации.");
        return run(validate(network, occupancy, blockedConnections), strategy);
    }

    private boolean better(NetworkResult candidate, NetworkResult other) {
        if (candidate.evacuatedPeople() != other.evacuatedPeople()) {
            return candidate.evacuatedPeople() > other.evacuatedPeople();
        }
        return candidate.evacuationTimeSeconds() < other.evacuationTimeSeconds();
    }

    private StrategyComparison comparison(NetworkResult result) {
        return new StrategyComparison(result.strategy(), result.evacuatedPeople(),
                result.unreachablePeople(), result.evacuationTimeSeconds());
    }

    private Input validate(EvacuationNetwork network, Map<String, Integer> occupancy, Set<String> blocked) {
        if (network == null || network.nodes() == null || network.passages() == null) {
            throw new IllegalArgumentException("Не передана сеть здания.");
        }
        if (network.nodes().size() > MAX_NODES) throw new IllegalArgumentException("Допускается не более " + MAX_NODES + " узлов.");
        if (network.passages().size() > MAX_PASSAGES) throw new IllegalArgumentException("Слишком много связей на плане.");
        LinkedHashMap<String, Node> nodes = new LinkedHashMap<>();
        for (Node node : network.nodes()) {
            if (node == null) throw new IllegalArgumentException("Пустой узел сети.");
            id(node.id(), "узла");
            id(node.floorId(), "этажа");
            if (node.id().equals(OUTSIDE) || nodes.putIfAbsent(node.id(), node) != null) {
                throw new IllegalArgumentException("Неуникальный или зарезервированный id узла: " + node.id());
            }
            if (node.capacity() < 0) throw new IllegalArgumentException("Отрицательная вместимость узла " + node.id());
            position(node.position());
        }
        LinkedHashMap<String, Passage> passages = new LinkedHashMap<>();
        int geometryPoints = 0;
        for (Passage passage : network.passages()) {
            if (passage == null) throw new IllegalArgumentException("Пустая связь сети.");
            id(passage.id(), "связи");
            if (nodes.containsKey(passage.id()) || passages.containsKey(passage.id())) {
                throw new IllegalArgumentException("Неуникальный id связи: " + passage.id());
            }
            if (!nodes.containsKey(passage.from()) || passage.from().equals(passage.to())
                    || (!OUTSIDE.equals(passage.to()) && !nodes.containsKey(passage.to()))) {
                throw new IllegalArgumentException("Связь " + passage.id() + " должна соединять два известных разных узла.");
            }
            if (OUTSIDE.equals(passage.to()) && !passage.directed()) {
                throw new IllegalArgumentException("Выход " + passage.id() + " должен быть направлен наружу.");
            }
            if (passage.throughput() != null) {
                positive(passage.throughput(), "Пропускная способность " + passage.id());
                positive(1 / passage.throughput(), "Время прохода " + passage.id());
            }
            nonnegative(passage.beforeSeconds(), "Время движения " + passage.id());
            nonnegative(passage.traversalSeconds(), "Время пересечения " + passage.id());
            nonnegative(passage.afterSeconds(), "Время движения " + passage.id());
            positiveOrZeroSum(passage.beforeSeconds(), passage.traversalSeconds(), passage.afterSeconds(),
                    passage.throughput() == null ? 0 : 1 / passage.throughput());
            List<Position> before = copyPath(passage.beforePath());
            List<Position> after = copyPath(passage.afterPath());
            Node fromNode = nodes.get(passage.from());
            Node toNode = nodes.get(passage.to());
            if (!before.isEmpty()) {
                endpoint(before.getFirst(), fromNode.position(), passage.id());
                sameFloor(before, fromNode.floorId(), passage.id());
            }
            if (!after.isEmpty()) {
                if (toNode != null) endpoint(after.getLast(), toNode.position(), passage.id());
                sameFloor(after, toNode == null ? fromNode.floorId() : toNode.floorId(), passage.id());
            }
            geometryPoints += before.size() + after.size();
            if (geometryPoints > MAX_GEOMETRY_POINTS) throw new IllegalArgumentException("Слишком подробная геометрия маршрутов.");
            passages.put(passage.id(), new Passage(passage.id(), passage.kind(), passage.from(), passage.to(),
                    passage.throughput(), passage.directed(), passage.beforeSeconds(), passage.traversalSeconds(),
                    passage.afterSeconds(), before, after));
        }
        Set<String> closed = blocked == null ? Set.of() : new LinkedHashSet<>(blocked);
        for (String blockedId : closed) if (!passages.containsKey(blockedId)) {
            throw new IllegalArgumentException("Неизвестная блокируемая связь: " + blockedId);
        }
        Map<String, Integer> counts = occupancy == null ? Map.of() : new LinkedHashMap<>(occupancy);
        long total = 0;
        for (var entry : counts.entrySet()) {
            Node node = nodes.get(entry.getKey());
            if (node == null) throw new IllegalArgumentException("Неизвестный узел размещения людей: " + entry.getKey());
            Integer count = entry.getValue();
            if (count == null || count < 0 || count > node.capacity()) {
                throw new IllegalArgumentException("Число людей в " + entry.getKey() + " должно быть от 0 до " + node.capacity());
            }
            total += count;
            if (total > MAX_PEOPLE) throw new IllegalArgumentException("Допускается не более " + MAX_PEOPLE + " человек.");
        }
        if (total * nodes.size() > MAX_PERSON_NODES) {
            throw new IllegalArgumentException("Сценарий слишком велик: уменьшите число людей или узлов.");
        }
        long work = total * nodes.size() * (nodes.size() + 2L * passages.size());
        if (work > MAX_ROUTING_WORK) throw new IllegalArgumentException("Сценарий превышает лимит вычислений: уменьшите число людей, узлов или связей.");
        return new Input(nodes, passages, counts, closed, (int) total);
    }

    private NetworkResult run(Input input, RoutingStrategy strategy) {
        var graph = new DirectedWeightedMultigraph<String, Leg>(null, null);
        input.nodes.keySet().forEach(graph::addVertex);
        graph.addVertex(OUTSIDE);
        LinkedHashMap<String, Resource> resources = new LinkedHashMap<>();
        for (Passage passage : input.passages.values()) {
            Resource resource = new Resource(passage.id(), passage.throughput() == null ? 0 : 1 / passage.throughput());
            resources.put(passage.id(), resource);
            if (input.blocked.contains(passage.id())) continue;
            addLeg(graph, input.nodes, passage, resource, false);
            if (!passage.directed()) addLeg(graph, input.nodes, passage, resource, true);
        }
        PriorityQueue<Event> events = new PriorityQueue<>(Comparator.comparingDouble(Event::time).thenComparingLong(Event::order));
        List<Person> people = new ArrayList<>(input.total);
        long order = 0;
        for (String source : input.nodes.keySet()) {
            for (int n = 0; n < input.occupancy.getOrDefault(source, 0); n++) {
                Person person = new Person(people.size(), source, input.nodes.get(source).position());
                people.add(person);
                events.add(new Event(0, order++, person, null));
            }
        }
        int evacuated = 0;
        double endTime = 0;
        int timelinePoints = 0;
        LinkedHashMap<RouteKey, Accumulator> routes = new LinkedHashMap<>();
        LinkedHashMap<String, Integer> unreachable = new LinkedHashMap<>();
        while (!events.isEmpty()) {
            if (Thread.currentThread().isInterrupted()) throw new IllegalArgumentException("Расчёт отменён.");
            Event event = events.remove();
            Person person = event.person;
            if (event.leg != null) {
                Leg leg = event.leg;
                Resource resource = leg.resource;
                resource.inFlight--;
                double start = Math.max(event.time, resource.readyAt);
                double finish = advance(start, resource.serviceSeconds);
                resource.readyAt = finish;
                resource.people++;
                resource.maxWait = Math.max(resource.maxWait, start - event.time);
                resource.busySeconds += resource.serviceSeconds;
                Position entrance = leg.beforePath.getLast();
                Position exit = leg.afterPath.getFirst();
                if (start > event.time) person.add("wait", event.time, start, List.of(entrance), resource.id);
                if (resource.serviceSeconds > 0) person.add("passage", start, finish, List.of(entrance), resource.id);
                double crossed = advance(finish, leg.traversalSeconds);
                if (leg.traversalSeconds > 0 || !entrance.equals(exit)) {
                    person.add(isStair(leg.kind) ? "stairs" : "move", finish, crossed, List.of(entrance, exit), resource.id);
                }
                double arrival = advance(crossed, leg.afterSeconds);
                if (leg.afterSeconds > 0) person.add("move", crossed, arrival, leg.afterPath, resource.id);
                timelinePoints += person.newPoints;
                person.newPoints = 0;
                if (timelinePoints > MAX_TIMELINE_POINTS) throw new IllegalArgumentException("Слишком большой журнал движения: уменьшите сценарий или подробность геометрии.");
                person.current = leg.to;
                events.add(new Event(arrival, order++, person, null));
                continue;
            }
            if (OUTSIDE.equals(person.current)) {
                evacuated++;
                endTime = Math.max(endTime, event.time);
                person.status = "evacuated";
                person.finish = event.time;
                person.add("evacuated", event.time, event.time, person.lastPosition(), null);
                timelinePoints += person.newPoints;
                person.newPoints = 0;
                checkTimelineSize(timelinePoints);
                RouteKey key = new RouteKey(person.source, List.copyOf(person.nodeIds), List.copyOf(person.connectionIds));
                routes.computeIfAbsent(key, ignored -> new Accumulator()).accept(event.time);
                continue;
            }
            person.visited.add(person.current);
            for (Leg leg : graph.edgeSet()) {
                double wait = strategy == RoutingStrategy.QUEUE_AWARE
                        ? Math.max(0, leg.resource.readyAt - event.time - leg.beforeSeconds)
                          + leg.resource.inFlight * leg.resource.serviceSeconds : 0;
                double weight = leg.beforeSeconds + leg.traversalSeconds + leg.afterSeconds + leg.resource.serviceSeconds + wait;
                if (!Double.isFinite(weight)) throw new IllegalArgumentException("Вес маршрута вышел за числовой диапазон.");
                graph.setEdgeWeight(leg, weight);
            }
            String current = person.current;
            var available = new MaskSubgraph<>(graph, node -> person.visited.contains(node) && !node.equals(current), edge -> false);
            GraphPath<String, Leg> path = new DijkstraShortestPath<>(available).getPath(current, OUTSIDE);
            if (path == null) {
                person.status = "unreachable";
                person.finish = event.time;
                person.add("unreachable", event.time, event.time, List.of(input.nodes.get(current).position()), null);
                timelinePoints += person.newPoints;
                person.newPoints = 0;
                checkTimelineSize(timelinePoints);
                unreachable.merge(person.source, 1, Integer::sum);
                continue;
            }
            Leg next = path.getEdgeList().getFirst();
            next.resource.inFlight++;
            person.connectionIds.add(next.resource.id);
            person.nodeIds.add(next.to);
            double arrival = advance(event.time, next.beforeSeconds);
            if (next.beforeSeconds > 0) person.add("move", event.time, arrival, next.beforePath, next.resource.id);
            timelinePoints += person.newPoints;
            person.newPoints = 0;
            if (timelinePoints > MAX_TIMELINE_POINTS) throw new IllegalArgumentException("Слишком большой журнал движения: уменьшите сценарий или подробность геометрии.");
            events.add(new Event(arrival, order++, person, next));
        }
        int unreachablePeople = unreachable.values().stream().mapToInt(Integer::intValue).sum();
        if (evacuated + unreachablePeople != input.total) throw new IllegalStateException("Нарушен учёт людей в расчёте.");
        List<Route> routeViews = routes.entrySet().stream().map(e -> new Route(e.getKey().source,
                e.getKey().nodes, e.getKey().connections, e.getValue().people, e.getValue().first, e.getValue().last)).toList();
        List<Bottleneck> bottlenecks = resources.values().stream().filter(r -> r.people > 0 && r.serviceSeconds > 0)
                .sorted(Comparator.comparingDouble((Resource r) -> r.maxWait).reversed().thenComparing(r -> r.id))
                .map(r -> new Bottleneck(r.id, r.people, r.maxWait, r.busySeconds)).toList();
        List<PersonResult> personViews = people.stream().map(p -> new PersonResult(p.id, p.source, p.status, p.finish,
                List.copyOf(p.nodeIds), List.copyOf(p.connectionIds), List.copyOf(p.timeline))).toList();
        List<String> warnings = new ArrayList<>();
        warnings.add("Сравниваются кратчайшие пути и маршруты с учётом очередей; глобальный минимум времени эвакуации не гарантируется.");
        warnings.add("Вместимость проверяется при начальном размещении. Плотность людей и заполнение промежуточных помещений пока не моделируются.");
        unreachable.forEach((source, count) -> warnings.add("Из узла " + source + " нет доступного пути наружу: " + count + " чел."));
        return new NetworkResult(input.total, evacuated, unreachablePeople, endTime, strategy.name(), List.of(),
                List.copyOf(input.nodes.values()), List.copyOf(input.passages.values()), routeViews,
                bottlenecks, personViews, List.copyOf(warnings));
    }

    private void addLeg(DirectedWeightedMultigraph<String, Leg> graph, Map<String, Node> nodes,
                        Passage passage, Resource resource, boolean reversed) {
        String from = reversed ? passage.to() : passage.from();
        String to = reversed ? passage.from() : passage.to();
        List<Position> before = reversed ? reverse(passage.afterPath()) : passage.beforePath();
        List<Position> after = reversed ? reverse(passage.beforePath()) : passage.afterPath();
        if (before.isEmpty()) before = List.of(nodes.get(from).position());
        if (after.isEmpty()) after = List.of(OUTSIDE.equals(to) ? before.getLast() : nodes.get(to).position());
        Leg leg = new Leg(resource, passage.kind(), from, to,
                reversed ? passage.afterSeconds() : passage.beforeSeconds(), passage.traversalSeconds(),
                reversed ? passage.beforeSeconds() : passage.afterSeconds(), before, after);
        graph.addEdge(from, to, leg);
        graph.setEdgeWeight(leg, leg.beforeSeconds + leg.traversalSeconds + leg.afterSeconds + resource.serviceSeconds);
    }

    private static boolean isStair(String kind) { return "stairs".equals(kind) || "stair".equals(kind); }
    private static List<Position> reverse(List<Position> path) { return path.reversed(); }

    private List<Position> copyPath(List<Position> path) {
        if (path == null) return List.of();
        if (path.size() > MAX_GEOMETRY_POINTS) throw new IllegalArgumentException("Слишком подробная геометрия маршрута.");
        path.forEach(this::position);
        return List.copyOf(path);
    }

    private void position(Position p) {
        if (p == null || p.floorId() == null || p.floorId().isBlank() || p.floorId().length() > MAX_ID_LENGTH
                || !Double.isFinite(p.x()) || !Double.isFinite(p.y())) {
            throw new IllegalArgumentException("Некорректные координаты маршрута.");
        }
    }
    private void endpoint(Position actual, Position expected, String passage) {
        if (!actual.floorId().equals(expected.floorId()) || Math.abs(actual.x() - expected.x()) > 1e-7
                || Math.abs(actual.y() - expected.y()) > 1e-7) {
            throw new IllegalArgumentException("Геометрия связи " + passage + " не совпадает с координатами её узла.");
        }
    }
    private void sameFloor(List<Position> path, String floorId, String passage) {
        if (path.stream().anyMatch(p -> !p.floorId().equals(floorId))) {
            throw new IllegalArgumentException("Путь до/после прохода " + passage + " должен лежать на одном этаже.");
        }
    }
    private void id(String id, String kind) {
        if (id == null || id.isBlank() || id.length() > MAX_ID_LENGTH) throw new IllegalArgumentException("Некорректный id " + kind + ".");
    }
    private void positive(double n, String label) {
        if (!Double.isFinite(n) || n <= 0) throw new IllegalArgumentException(label + " должна быть конечным положительным числом.");
    }
    private void nonnegative(double n, String label) {
        if (!Double.isFinite(n) || n < 0) throw new IllegalArgumentException(label + " должно быть конечным неотрицательным числом.");
    }
    private void positiveOrZeroSum(double... values) {
        double sum = 0;
        for (double value : values) sum += value;
        if (!Double.isFinite(sum)) throw new IllegalArgumentException("Время движения вышло за числовой диапазон.");
    }
    private double advance(double time, double duration) {
        double next = time + duration;
        if (!Double.isFinite(next) || (duration > 0 && next <= time)) {
            throw new IllegalArgumentException("Время расчёта вышло за допустимую точность.");
        }
        return next;
    }

    private void checkTimelineSize(int points) {
        if (points > MAX_TIMELINE_POINTS) throw new IllegalArgumentException("Слишком большой журнал движения: уменьшите сценарий или подробность геометрии.");
    }

    private record Input(LinkedHashMap<String, Node> nodes, LinkedHashMap<String, Passage> passages,
                         Map<String, Integer> occupancy, Set<String> blocked, int total) { }
    private record Leg(Resource resource, String kind, String from, String to, double beforeSeconds,
                       double traversalSeconds, double afterSeconds, List<Position> beforePath, List<Position> afterPath) { }
    private record Event(double time, long order, Person person, Leg leg) { }
    private record RouteKey(String source, List<String> nodes, List<String> connections) { }
    private static final class Resource {
        final String id;
        final double serviceSeconds;
        double readyAt, maxWait, busySeconds;
        int inFlight, people;
        Resource(String id, double serviceSeconds) { this.id = id; this.serviceSeconds = serviceSeconds; }
    }
    private static final class Person {
        final int id;
        final String source;
        final Position initialPosition;
        final Set<String> visited = new HashSet<>();
        final List<String> nodeIds = new ArrayList<>(), connectionIds = new ArrayList<>();
        final List<Segment> timeline = new ArrayList<>();
        String current, status;
        double finish;
        int newPoints;
        Person(int id, String source, Position initialPosition) {
            this.id = id; this.source = source; this.initialPosition = initialPosition;
            current = source; nodeIds.add(source);
        }
        void add(String kind, double start, double end, List<Position> path, String connection) {
            timeline.add(new Segment(kind, start, end, path, connection));
            newPoints += path.size();
        }
        List<Position> lastPosition() {
            return timeline.isEmpty() ? List.of(initialPosition) : List.of(timeline.getLast().path().getLast());
        }
    }
    private static final class Accumulator {
        int people;
        double first = Double.POSITIVE_INFINITY, last;
        void accept(double time) { people++; first = Math.min(first, time); last = Math.max(last, time); }
    }
}

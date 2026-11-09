package com.artemyasnik.simulation;

import com.artemyasnik.model.*;

import java.util.*;

import static com.artemyasnik.model.EmergencyExit.OUTSIDE_NODE_ID;
import static com.artemyasnik.simulation.AnalysisResult.*;

/** Compatibility adapter for the original one-floor API; all calculation uses the shared engine. */
public final class SimulationService {
    public static final int MAX_PEOPLE = NetworkSimulationService.MAX_PEOPLE;
    public static final int MAX_NODES = NetworkSimulationService.MAX_NODES;

    public AnalysisResult analyze(FloorPlan plan, Map<String, Integer> occupancy,
                                  Set<String> blockedConnections, double walkingSpeedMps) {
        if (plan == null) throw new IllegalArgumentException("Не передан план этажа.");
        positiveFinite(walkingSpeedMps, "Скорость движения");
        positiveFinite(plan.getScaleMetersPerUnit(), "Масштаб плана");
        Set<String> blocked = blockedConnections == null ? Set.of() : blockedConnections;
        LinkedHashMap<String, NodeView> views = new LinkedHashMap<>();
        for (Room room : plan.getRooms().values()) addNode(views, room, "room", room.getName(), room.getCapacity());
        for (Corridor corridor : plan.getCorridors().values()) addNode(views, corridor, "corridor", corridor.getId(), corridor.getCapacity());
        String floorId = String.valueOf(plan.getFloorNumber());
        List<EvacuationNetwork.Node> nodes = views.values().stream().map(n -> new EvacuationNetwork.Node(
                n.id(), floorId, n.kind(), n.name(), n.capacity(), n.x(), n.y())).toList();
        Map<String, EvacuationNetwork.Node> byId = new LinkedHashMap<>();
        nodes.forEach(n -> byId.put(n.id(), n));
        List<Connector> connectors = new ArrayList<>();
        connectors.addAll(plan.getDoors());
        connectors.addAll(plan.getExits());
        List<EvacuationNetwork.Passage> passages = new ArrayList<>();
        List<ConnectionView> connectionViews = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (Connector connector : connectors) {
            String id = connector.getId();
            if (id == null || id.isBlank() || !ids.add(id) || byId.containsKey(id)) {
                throw new IllegalArgumentException("Неуникальный или пустой id связи: " + id);
            }
            List<String> ends = connector.getConnectsIds();
            if (ends.size() != 2 || ends.get(0).equals(ends.get(1))) {
                throw new IllegalArgumentException("Связь " + id + " должна соединять два разных узла.");
            }
            boolean exit = connector instanceof EmergencyExit;
            if (exit != ends.contains(OUTSIDE_NODE_ID)) {
                throw new IllegalArgumentException("Только аварийный выход должен соединяться с OUTSIDE: " + id);
            }
            for (String end : ends) if (!OUTSIDE_NODE_ID.equals(end) && !byId.containsKey(end)) {
                throw new IllegalArgumentException("Связь " + id + ": неизвестный узел " + end);
            }
            positiveFinite(connector.getThroughput(), "Пропускная способность " + id);
            Point2D point = center(connector);
            EvacuationNetwork.Position at = new EvacuationNetwork.Position(floorId, point.x(), point.y());
            String from = OUTSIDE_NODE_ID.equals(ends.get(0)) ? ends.get(1) : ends.get(0);
            String to = OUTSIDE_NODE_ID.equals(ends.get(0)) ? ends.get(0) : ends.get(1);
            EvacuationNetwork.Node source = byId.get(from), destination = byId.get(to);
            double before = Math.hypot(source.x() - point.x(), source.y() - point.y()) * plan.getScaleMetersPerUnit() / walkingSpeedMps;
            double after = destination == null ? 0 : Math.hypot(destination.x() - point.x(), destination.y() - point.y()) * plan.getScaleMetersPerUnit() / walkingSpeedMps;
            passages.add(new EvacuationNetwork.Passage(id, exit ? "exit" : "door", from, to,
                    connector.getThroughput(), exit, before, 0, after,
                    List.of(source.position(), at), destination == null ? List.of(at) : List.of(at, destination.position())));
            connectionViews.add(new ConnectionView(id, exit ? "exit" : "door", ends.get(0), ends.get(1),
                    connector.getThroughput(), connector.getWidthM(), point.x(), point.y(), blocked.contains(id)));
        }
        NetworkResult result = new NetworkSimulationService().analyze(new EvacuationNetwork(nodes, passages), occupancy,
                blocked, NetworkSimulationService.RoutingStrategy.QUEUE_AWARE);
        List<String> warnings = new ArrayList<>();
        warnings.add("Маршруты учитывают текущие очереди; глобальный минимум времени эвакуации не гарантируется.");
        warnings.add("Вместимость проверяется при начальном размещении. Плотность людей и заполнение промежуточных помещений пока не моделируются.");
        warnings.add("Путь внутри помещения приближён отрезками через центр его габаритов и проём; препятствия внутри помещений не учитываются.");
        if (!plan.getStairs().isEmpty()) warnings.add("Межэтажные лестницы исключены: расчёт выполняется только для одного этажа и его выходов наружу.");
        result.warnings().stream().filter(w -> w.startsWith("Из узла ")).forEach(warnings::add);
        return new AnalysisResult(plan.getFloorNumber(), plan.getScaleMetersPerUnit(), result.totalPeople(),
                result.evacuatedPeople(), result.unreachablePeople(), result.evacuationTimeSeconds(),
                List.copyOf(views.values()), List.copyOf(connectionViews),
                result.routes().stream().map(r -> new RouteView(r.sourceNodeId(), r.nodeIds(), r.connectionIds(),
                        r.people(), r.firstArrivalSeconds(), r.lastArrivalSeconds())).toList(),
                result.bottlenecks().stream().map(b -> new ConnectionUsage(b.connectionId(), b.people(), b.maxWaitSeconds(), b.busySeconds())).toList(),
                List.copyOf(warnings));
    }

    private void addNode(Map<String, NodeView> nodes, BuildingElement element, String kind, String name, int capacity) {
        String id = element.getId();
        if (id == null || id.isBlank() || id.equals(OUTSIDE_NODE_ID) || nodes.containsKey(id)) {
            throw new IllegalArgumentException("Неуникальный или зарезервированный id узла: " + id);
        }
        if (capacity < 0) throw new IllegalArgumentException("Отрицательная вместимость узла " + id);
        Point2D point = center(element);
        nodes.put(id, new NodeView(id, kind, name, capacity, point.x(), point.y()));
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
}

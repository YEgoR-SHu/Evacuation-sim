package com.artemyasnik.graph;

import com.artemyasnik.model.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Строит граф для моделирования эвакуации по одному этажу: узлы — комнаты,
 * коридоры и псевдо-узел OUTSIDE; рёбра — двери/выходы/лестницы с их
 * пропускной способностью. Граф неориентированный (эвакуация может временно
 * идти в любую сторону), но для расчёта потока эвакуации обычно обходят от
 * комнат к OUTSIDE.
 */
public class EvacGraphBuilder {

    public EvacGraph build(FloorPlan plan) {
        Map<String, EvacGraphNode> nodes = new LinkedHashMap<>();
        List<EvacGraphEdge> edges = new ArrayList<>();

        for (Room r : plan.getRooms().values()) {
            nodes.put(r.getId(), new EvacGraphNode(r.getId(), "room", r.getCapacity()));
        }
        for (Corridor c : plan.getCorridors().values()) {
            nodes.put(c.getId(), new EvacGraphNode(c.getId(), "corridor", c.getCapacity()));
        }

        boolean needsOutside = !plan.getExits().isEmpty();
        if (needsOutside) {
            nodes.putIfAbsent(EmergencyExit.OUTSIDE_NODE_ID,
                    new EvacGraphNode(EmergencyExit.OUTSIDE_NODE_ID, "outside", Integer.MAX_VALUE));
        }

        for (Door d : plan.getDoors()) {
            addEdge(nodes, edges, d.getId(), "door", d.getConnectsIds(), d.getThroughput());
        }
        for (EmergencyExit e : plan.getExits()) {
            addEdge(nodes, edges, e.getId(), "exit", e.getConnectsIds(), e.getThroughput());
        }
        for (Stairs s : plan.getStairs()) {
            // межэтажные узлы (id@floor) для однoэтажного графа сворачиваются к baseId;
            // полноценная многоэтажная сборка — в EvacGraphBuilder.buildMultiFloor (TODO)
            addEdge(nodes, edges, s.getId(), "stairs", stripFloorSuffix(s.getConnectsIds()), s.getThroughput());
        }

        return new EvacGraph(nodes, edges);
    }

    private List<String> stripFloorSuffix(List<String> ids) {
        return ids.stream().map(s -> s.contains("@") ? s.substring(0, s.indexOf('@')) : s).toList();
    }

    private void addEdge(Map<String, EvacGraphNode> nodes, List<EvacGraphEdge> edges,
                         String id, String kind, List<String> connects, double throughput) {
        if (connects.size() < 2) {
            throw new IllegalArgumentException("Связь " + id + " должна иметь минимум 2 узла в data-connects");
        }
        for (String nodeId : connects) {
            if (!nodes.containsKey(nodeId)) {
                throw new IllegalArgumentException(
                        "Связь " + id + " ссылается на неизвестный узел '" + nodeId
                                + "' — проверь, что id комнаты/коридора/OUTSIDE существует");
            }
        }
        edges.add(new EvacGraphEdge(id, kind, connects.get(0), connects.get(1), throughput));
    }
}
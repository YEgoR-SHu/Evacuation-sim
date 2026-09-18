package com.artemyasnik.graph;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class EvacGraph {

    private final Map<String, EvacGraphNode> nodes;
    private final List<EvacGraphEdge> edges;

    public EvacGraph(Map<String, EvacGraphNode> nodes, List<EvacGraphEdge> edges) {
        this.nodes = nodes;
        this.edges = edges;
    }

    public Map<String, EvacGraphNode> getNodes() {
        return nodes;
    }

    public List<EvacGraphEdge> getEdges() {
        return edges;
    }

    public List<EvacGraphEdge> edgesOf(String nodeId) {
        List<EvacGraphEdge> result = new ArrayList<>();
        for (EvacGraphEdge e : edges) {
            if (e.getFromNodeId().equals(nodeId) || e.getToNodeId().equals(nodeId)) {
                result.add(e);
            }
        }
        return result;
    }
}
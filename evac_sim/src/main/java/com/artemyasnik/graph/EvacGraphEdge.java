package com.artemyasnik.graph;

public class EvacGraphEdge {
    private final String id;
    private final String kind; // "door" | "exit" | "stairs"
    private final String fromNodeId;
    private final String toNodeId;
    private final double throughputPersonsPerSec;

    public EvacGraphEdge(String id, String kind, String fromNodeId, String toNodeId,
                         double throughputPersonsPerSec) {
        this.id = id;
        this.kind = kind;
        this.fromNodeId = fromNodeId;
        this.toNodeId = toNodeId;
        this.throughputPersonsPerSec = throughputPersonsPerSec;
    }

    public String getId() {
        return id;
    }

    public String getKind() {
        return kind;
    }

    public String getFromNodeId() {
        return fromNodeId;
    }

    public String getToNodeId() {
        return toNodeId;
    }

    public double getThroughputPersonsPerSec() {
        return throughputPersonsPerSec;
    }

    @Override
    public String toString() {
        return id + "[" + kind + "]: " + fromNodeId + " <-> " + toNodeId
                + " (" + throughputPersonsPerSec + " чел/с)";
    }
}
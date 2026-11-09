export interface User {
    id: number;
    username: string;
}

export interface BuildingSummary {
    id: number;
    name: string;
    description: string;
    floorCount: number;
    scenarioCount: number;
    createdAt: string;
    updatedAt: string;
}

export interface Floor {
    id: number;
    name: string;
    level: number;
    svg: string;
    vb: [number, number, number, number];
}

export interface ScenarioSummary {
    id: number;
    name: string;
    isDefault: boolean;
    createdAt: string;
    updatedAt: string;
}

export interface Params {
    people: number;
    speed: number;
    fireRate: number;
    scale: number;
    stairTime: number;
    exitFlow: number;
    stairFlow: number;
}

export interface Fire {
    floorId: number;
    x: number;
    y: number;
}

export interface Mark {
    id: number;
    floorId: number;
    kind: 'stair' | 'exit' | 'spawn';
    x: number;
    y: number;
    width: number | null;
}

export interface Building {
    id: number;
    name: string;
    description: string;
    floors: Floor[];
    scenarios: ScenarioSummary[];
    createdAt: string;
    updatedAt: string;
}

export interface ScenarioFull {
    id: number;
    name: string;
    isDefault: boolean;
    params: Params;
    fire: Fire | null;
    marks: Mark[];
    createdAt: string;
    updatedAt: string;
}

export interface ErrorResponse {
    error: string;
    message: string;
}
export interface Position { floorId: string; x: number; y: number; }
export interface NetworkNode extends Position {
    id: string; kind: string; name: string; capacity: number;
}
export interface Passage {
    id: string; kind: string; from: string; to: string; throughput: number | null;
    directed: boolean; beforeSeconds: number; traversalSeconds: number; afterSeconds: number;
    beforePath: Position[]; afterPath: Position[];
}
export interface SimulationSettings {
    occupancy: Record<string, number>;
    blockedConnections: string[];
    stairLinks: { fromMarkId: number; toMarkId: number }[];
}
export interface Topology {
    mode: 'semantic' | 'raster'; nodes: NetworkNode[]; passages: Passage[];
    occupancy: Record<string, number>; warnings: string[];
}
export interface Segment {
    kind: string; startSeconds: number; endSeconds: number;
    path: Position[]; connectionId: string | null;
}
export interface PersonResult {
    id: number; sourceNodeId: string; status: 'evacuated' | 'unreachable'; finishSeconds: number;
    nodeIds: string[]; connectionIds: string[]; timeline: Segment[];
}
export interface NetworkResult {
    totalPeople: number; evacuatedPeople: number; unreachablePeople: number;
    evacuationTimeSeconds: number; strategy: string;
    strategyComparisons: { strategy: string; evacuatedPeople: number; unreachablePeople: number; evacuationTimeSeconds: number }[];
    nodes: NetworkNode[]; passages: Passage[];
    routes: { sourceNodeId: string; nodeIds: string[]; connectionIds: string[]; people: number; firstArrivalSeconds: number; lastArrivalSeconds: number }[];
    bottlenecks: { connectionId: string; people: number; maxWaitSeconds: number; busySeconds: number }[];
    people: PersonResult[]; warnings: string[];
}
export interface SimulationRun {
    id: string; createdAt: string; mode: 'semantic' | 'raster'; result: NetworkResult;
    floors: Floor[]; warnings: string[]; engineVersion: string; inputSha256: string;
}
export interface RunSummary {
    id: string; createdAt: string; totalPeople: number; evacuatedPeople: number;
    unreachablePeople: number; evacuationTimeSeconds: number;
}

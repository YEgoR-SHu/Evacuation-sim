import type {
    User, BuildingSummary, Building, ScenarioFull, ScenarioSummary,
    Floor, Mark, ErrorResponse, SimulationSettings, Topology, SimulationRun, RunSummary
} from './types';

async function request<T>(path: string, options: RequestInit = {}): Promise<T> {
    const res = await fetch(`/api${path}`, {
        ...options,
        headers: {
            'Content-Type': 'application/json',
            ...(options.headers || {}),
        },
        credentials: 'include', // Важно! Отправляем куки SESSION
    });

    if (!res.ok) {
        let message = 'Ошибка сервера';
        try {
            const err = (await res.json()) as ErrorResponse;
            message = err.message || err.error;
        } catch { /* ignore */ }
        throw new Error(message);
    }

    if (res.status === 204) {
        return undefined as T;
    }

    return res.json();
}

export const api = {
    auth: {
        register: (username: string, password: string) =>
            request<User>('/auth/register', { method: 'POST', body: JSON.stringify({ username, password }) }),
        login: (username: string, password: string) =>
            request<User>('/auth/login', { method: 'POST', body: JSON.stringify({ username, password }) }),
        logout: () =>
            request<void>('/auth/logout', { method: 'POST' }),
        me: () =>
            request<User>('/auth/me'),
    },

    buildings: {
        list: () =>
            request<BuildingSummary[]>('/buildings'),
        create: (name: string, description: string) =>
            request<BuildingSummary>('/buildings', { method: 'POST', body: JSON.stringify({ name, description }) }),
        get: (id: number) =>
            request<Building>(`/buildings/${id}`),
        update: (id: number, name: string, description: string) =>
            request<BuildingSummary>(`/buildings/${id}`, { method: 'PUT', body: JSON.stringify({ name, description }) }),
        delete: (id: number) =>
            request<void>(`/buildings/${id}`, { method: 'DELETE' }),
    },

    floors: {
        list: (bid: number) =>
            request<Floor[]>(`/buildings/${bid}/floors`),
        create: (bid: number, data: Omit<Floor, 'id'>) =>
            request<Floor>(`/buildings/${bid}/floors`, { method: 'POST', body: JSON.stringify(data) }),
        update: (bid: number, fid: number, data: Omit<Floor, 'id'>) =>
            request<Floor>(`/buildings/${bid}/floors/${fid}`, { method: 'PUT', body: JSON.stringify(data) }),
        delete: (bid: number, fid: number) =>
            request<void>(`/buildings/${bid}/floors/${fid}`, { method: 'DELETE' }),
    },

    scenarios: {
        list: (bid: number) =>
            request<ScenarioSummary[]>(`/buildings/${bid}/scenarios`),
        get: (bid: number, sid: number) =>
            request<ScenarioFull>(`/buildings/${bid}/scenarios/${sid}`),
        create: (bid: number, name: string) =>
            request<ScenarioSummary>(`/buildings/${bid}/scenarios`, { method: 'POST', body: JSON.stringify({ name, params: null, fire: null }) }),
        update: (bid: number, sid: number, data: { name: string, params: any, fire: any }) =>
            request<ScenarioFull>(`/buildings/${bid}/scenarios/${sid}`, { method: 'PUT', body: JSON.stringify(data) }),
        delete: (bid: number, sid: number) =>
            request<void>(`/buildings/${bid}/scenarios/${sid}`, { method: 'DELETE' }),
        setDefault: (bid: number, sid: number) =>
            request<void>(`/buildings/${bid}/scenarios/${sid}/default`, { method: 'POST' }),
    },

    simulation: {
        settings: (bid: number, sid: number) => request<SimulationSettings>(`/buildings/${bid}/scenarios/${sid}/simulation-settings`),
        saveSettings: (bid: number, sid: number, data: SimulationSettings) =>
            request<SimulationSettings>(`/buildings/${bid}/scenarios/${sid}/simulation-settings`, { method: 'PUT', body: JSON.stringify(data) }),
        topology: (bid: number, sid: number) => request<Topology>(`/buildings/${bid}/scenarios/${sid}/topology`),
        calculate: (bid: number, sid: number) => request<SimulationRun>(`/buildings/${bid}/scenarios/${sid}/simulate`, { method: 'POST' }),
        runs: (bid: number, sid: number) => request<RunSummary[]>(`/buildings/${bid}/scenarios/${sid}/runs`),
        run: (bid: number, sid: number, rid: string) => request<SimulationRun>(`/buildings/${bid}/scenarios/${sid}/runs/${encodeURIComponent(rid)}`),
    },

    marks: {
        list: (bid: number, sid: number) =>
            request<Mark[]>(`/buildings/${bid}/scenarios/${sid}/marks`),
        create: (bid: number, sid: number, data: Omit<Mark, 'id'>) =>
            request<Mark>(`/buildings/${bid}/scenarios/${sid}/marks`, { method: 'POST', body: JSON.stringify(data) }),
        update: (bid: number, sid: number, mid: number, data: Omit<Mark, 'id'>) =>
            request<Mark>(`/buildings/${bid}/scenarios/${sid}/marks/${mid}`, { method: 'PUT', body: JSON.stringify(data) }),
        delete: (bid: number, sid: number, mid: number) =>
            request<void>(`/buildings/${bid}/scenarios/${sid}/marks/${mid}`, { method: 'DELETE' }),
        deleteAll: (bid: number, sid: number) =>
            request<void>(`/buildings/${bid}/scenarios/${sid}/marks`, { method: 'DELETE' }),
    }
};
CREATE TABLE simulation_settings (
    scenario_id INTEGER PRIMARY KEY REFERENCES scenarios(id) ON DELETE CASCADE,
    settings_json TEXT NOT NULL
);
CREATE TABLE simulation_runs (
    id TEXT PRIMARY KEY,
    scenario_id INTEGER NOT NULL REFERENCES scenarios(id) ON DELETE CASCADE,
    created_at TEXT NOT NULL,
    input_json TEXT NOT NULL,
    result_json TEXT NOT NULL,
    total_people INTEGER NOT NULL,
    evacuated_people INTEGER NOT NULL,
    unreachable_people INTEGER NOT NULL,
    time_seconds REAL NOT NULL
);
CREATE INDEX idx_runs_scenario ON simulation_runs(scenario_id,created_at);

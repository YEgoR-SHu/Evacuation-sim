-- V1__init.sql
-- Схема БД: пользователи, сессии, здания, этажи, сценарии, метки.
-- Модель: User → Building → (Floor, Scenario); Scenario → Mark.

CREATE TABLE users (
                       id            INTEGER PRIMARY KEY AUTOINCREMENT,
                       username      TEXT NOT NULL UNIQUE,
                       password_hash TEXT NOT NULL,
                       created_at    TEXT NOT NULL DEFAULT (datetime('now'))
);

CREATE TABLE sessions (
                          token       TEXT PRIMARY KEY,
                          user_id     INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
                          created_at  TEXT NOT NULL DEFAULT (datetime('now')),
                          expires_at  TEXT NOT NULL
);

CREATE INDEX idx_sessions_user    ON sessions(user_id);
CREATE INDEX idx_sessions_expires ON sessions(expires_at);

-- Здание = план (SVG этажей). Общий для всех сценариев.
CREATE TABLE buildings (
                           id          INTEGER PRIMARY KEY AUTOINCREMENT,
                           user_id     INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
                           name        TEXT NOT NULL,
                           description TEXT NOT NULL DEFAULT '',
                           created_at  TEXT NOT NULL DEFAULT (datetime('now')),
                           updated_at  TEXT NOT NULL DEFAULT (datetime('now'))
);

CREATE INDEX idx_buildings_user ON buildings(user_id);

-- Этаж = один SVG. Общий для всех сценариев здания.
CREATE TABLE floors (
                        id          INTEGER PRIMARY KEY AUTOINCREMENT,
                        building_id INTEGER NOT NULL REFERENCES buildings(id) ON DELETE CASCADE,
                        name        TEXT NOT NULL,
                        level       INTEGER NOT NULL,
                        svg         TEXT NOT NULL,
                        vb_x        REAL NOT NULL,
                        vb_y        REAL NOT NULL,
                        vb_w        REAL NOT NULL,
                        vb_h        REAL NOT NULL,
                        created_at  TEXT NOT NULL DEFAULT (datetime('now')),
                        UNIQUE (building_id, level)
);

CREATE INDEX idx_floors_building ON floors(building_id);

-- Сценарий = вариант эвакуации. Привязан к зданию.
CREATE TABLE scenarios (
                           id          INTEGER PRIMARY KEY AUTOINCREMENT,
                           building_id INTEGER NOT NULL REFERENCES buildings(id) ON DELETE CASCADE,
                           name        TEXT NOT NULL,
                           is_default  INTEGER NOT NULL DEFAULT 0,
                           params_json TEXT NOT NULL DEFAULT '{}',
                           fire_json   TEXT NOT NULL DEFAULT 'null',
                           created_at  TEXT NOT NULL DEFAULT (datetime('now')),
                           updated_at  TEXT NOT NULL DEFAULT (datetime('now'))
);

CREATE INDEX idx_scenarios_building ON scenarios(building_id);
CREATE UNIQUE INDEX idx_scenarios_default ON scenarios(building_id) WHERE is_default = 1;

-- Метки привязаны к сценарию и этажу.
CREATE TABLE scenario_marks (
                                id          INTEGER PRIMARY KEY AUTOINCREMENT,
                                scenario_id INTEGER NOT NULL REFERENCES scenarios(id) ON DELETE CASCADE,
                                floor_id    INTEGER NOT NULL REFERENCES floors(id) ON DELETE CASCADE,
                                kind        TEXT NOT NULL CHECK (kind IN ('stair', 'exit', 'spawn')),
                                x           REAL NOT NULL,
                                y           REAL NOT NULL,
                                width       REAL,
                                created_at  TEXT NOT NULL DEFAULT (datetime('now'))
);

CREATE INDEX idx_marks_scenario        ON scenario_marks(scenario_id);
CREATE INDEX idx_marks_scenario_floor  ON scenario_marks(scenario_id, floor_id);
CREATE INDEX idx_marks_kind            ON scenario_marks(kind);
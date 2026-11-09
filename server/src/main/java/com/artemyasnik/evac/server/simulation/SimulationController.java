package com.artemyasnik.evac.server.simulation;

import com.artemyasnik.evac.server.auth.AuthController;
import com.artemyasnik.evac.server.building.*;
import com.artemyasnik.evac.server.config.Database;
import com.artemyasnik.evac.server.dto.*;
import com.artemyasnik.evac.server.simulation.RasterNetworkBuilder.StairLink;
import com.artemyasnik.simulation.*;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.javalin.config.RoutesConfig;
import io.javalin.http.Context;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.Semaphore;

/** Saved scenarios, both SVG formats and playback use one calculation contract. */
public final class SimulationController {
    public static final String VERSION = "0.3.0";
    private static final String BASE = "/api/buildings/{bid}/scenarios/{sid}";
    private final BuildingService buildings;
    private final ScenarioService scenarios;
    private final Database db;
    private final ObjectMapper json = new ObjectMapper().disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private final Semaphore slots = new Semaphore(2);

    public record Settings(Map<String, Integer> occupancy, Set<String> blockedConnections, List<StairLink> stairLinks) {
        public Settings {
            occupancy = occupancy == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(occupancy));
            blockedConnections = blockedConnections == null ? Set.of() : Collections.unmodifiableSet(new LinkedHashSet<>(blockedConnections));
            stairLinks = stairLinks == null ? List.of() : List.copyOf(stairLinks);
        }
        public static Settings empty() { return new Settings(null, null, null); }
    }
    public record Topology(String mode, List<EvacuationNetwork.Node> nodes, List<EvacuationNetwork.Passage> passages,
                           Map<String, Integer> occupancy, List<String> warnings) { }
    public record Run(String id, String createdAt, String mode, NetworkResult result, List<FloorDto> floors,
                      List<String> warnings, String engineVersion, String inputSha256) { }
    public record RunSummary(String id, String createdAt, int totalPeople, int evacuatedPeople, int unreachablePeople,
                             double evacuationTimeSeconds) { }
    private record Prepared(String mode, EvacuationNetwork network, Map<String, Integer> occupancy,
                            Set<String> blocked, List<String> warnings, List<FloorDto> floors) { }
    private record Snapshot(EvacuationNetwork network, Map<String, Integer> occupancy, Set<String> blocked,
                            List<FloorDto> floors, ParamsDto params, Settings settings) { }

    public SimulationController(BuildingService buildings, ScenarioService scenarios, Database db) {
        this.buildings = buildings;
        this.scenarios = scenarios;
        this.db = db;
    }

    public void register(RoutesConfig app) {
        app.get(BASE + "/simulation-settings", ctx -> {
            requireScenario(ctx);
            ctx.json(settings(sid(ctx)));
        });
        app.put(BASE + "/simulation-settings", ctx -> {
            ScenarioFullDto scenario = requireScenario(ctx);
            Settings settings = parseSettings(ctx);
            prepare(building(ctx), scenario, settings);
            writeSettings(sid(ctx), settings);
            ctx.json(settings);
        });
        app.get(BASE + "/topology", ctx -> {
            ScenarioFullDto scenario = requireScenario(ctx);
            Prepared prepared = prepare(building(ctx), scenario, settings(sid(ctx)));
            ctx.json(new Topology(prepared.mode, prepared.network.nodes(), prepared.network.passages(), prepared.occupancy, prepared.warnings));
        });
        app.post(BASE + "/simulate", ctx -> {
            ScenarioFullDto scenario = requireScenario(ctx);
            Settings settings = ctx.body().isBlank() ? settings(sid(ctx)) : parseSettings(ctx);
            if (!slots.tryAcquire()) { ctx.status(503).json(Map.of("error", "Расчёт занят. Повторите запрос позже.")); return; }
            try {
                Prepared prepared = prepare(building(ctx), scenario, settings);
                if (scenario.fire() != null) throw new IllegalArgumentException("Для базовой эвакуации удалите очаг пожара из сценария. Модель пожара ещё не подтверждена.");
                NetworkResult result = new NetworkSimulationService().analyze(prepared.network, prepared.occupancy, prepared.blocked);
                Snapshot snapshot = new Snapshot(prepared.network, prepared.occupancy, prepared.blocked, prepared.floors, scenario.params(), settings);
                byte[] input = json.writeValueAsBytes(snapshot);
                String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input));
                var warnings = new ArrayList<>(prepared.warnings);
                warnings.addAll(result.warnings());
                Run run = new Run(UUID.randomUUID().toString(), Instant.now().toString(), prepared.mode, result,
                        prepared.floors, List.copyOf(warnings), VERSION, hash);
                saveRun(sid(ctx), input, run);
                writeSettings(sid(ctx), settings);
                ctx.json(run);
            } finally { slots.release(); }
        });
        app.get(BASE + "/runs", ctx -> {
            requireScenario(ctx);
            List<RunSummary> runs = new ArrayList<>();
            try (var c = db.getConnection(); var statement = c.prepareStatement("SELECT id,created_at,total_people,evacuated_people,unreachable_people,time_seconds FROM simulation_runs WHERE scenario_id=? ORDER BY created_at DESC,id DESC")) {
                statement.setLong(1, sid(ctx));
                try (var rows = statement.executeQuery()) {
                    while (rows.next()) runs.add(new RunSummary(rows.getString(1), rows.getString(2), rows.getInt(3), rows.getInt(4), rows.getInt(5), rows.getDouble(6)));
                }
            }
            ctx.json(runs);
        });
        app.get(BASE + "/runs/{rid}", ctx -> {
            requireScenario(ctx);
            try (var c = db.getConnection(); var statement = c.prepareStatement("SELECT result_json FROM simulation_runs WHERE scenario_id=? AND id=?")) {
                statement.setLong(1, sid(ctx)); statement.setString(2, ctx.pathParam("rid"));
                try (var rows = statement.executeQuery()) {
                    if (!rows.next()) throw new NotFoundException("Расчёт не найден");
                    ctx.contentType("application/json").result(rows.getString(1));
                }
            }
        });
    }

    private Prepared prepare(BuildingDto building, ScenarioFullDto scenario, Settings settings) {
        List<FloorDto> floors = building.floors();
        if (floors.isEmpty()) throw new IllegalArgumentException("Добавьте хотя бы один этаж здания");
        if (floors.size() > 20) throw new IllegalArgumentException("Допускается не более 20 этажей");
        long bytes = floors.stream().mapToLong(floor -> floor.svg().getBytes(StandardCharsets.UTF_8).length).sum();
        if (bytes > 4 * 1024 * 1024) throw new IllegalArgumentException("Суммарный размер планов для расчёта — не более 4 МиБ");
        ScenarioService.validateParams(scenario.params());
        var semantic = new SemanticNetworkBuilder();
        long annotated = floors.stream().filter(semantic::hasModel).count();
        EvacuationNetwork network;
        Map<String, Integer> occupancy;
        List<String> warnings;
        String mode;
        if (annotated > 0) {
            if (annotated != floors.size()) throw new IllegalArgumentException("В одном здании используйте единый формат этажей: все с data-разметкой либо все с метками сценария");
            if (!settings.stairLinks().isEmpty()) throw new IllegalArgumentException("В размеченном SVG лестницы связываются через data-connects, дополнительные связи меток не нужны");
            var prepared = semantic.build(floors, scenario.params().speed(), scenario.params().scale(), scenario.params().stairTime());
            network = prepared.network(); warnings = prepared.warnings(); mode = "semantic";
            occupancy = new LinkedHashMap<>(settings.occupancy());
        } else {
            var prepared = new RasterNetworkBuilder().build(floors, scenario, settings.stairLinks());
            network = prepared.network(); warnings = prepared.warnings(); mode = "raster";
            occupancy = settings.occupancy().isEmpty() ? prepared.occupancy() : new LinkedHashMap<>(settings.occupancy());
        }
        Map<String, EvacuationNetwork.Node> nodes = new LinkedHashMap<>();
        network.nodes().forEach(node -> nodes.put(node.id(), node));
        long total = 0;
        for (var entry : occupancy.entrySet()) {
            var node = nodes.get(entry.getKey());
            if (node == null) throw new IllegalArgumentException("Помещение заполненности отсутствует в плане: " + entry.getKey());
            if (entry.getValue() == null || entry.getValue() < 0 || entry.getValue() > node.capacity()) throw new IllegalArgumentException("Неверное количество людей в " + entry.getKey());
            if (mode.equals("raster") && !node.kind().equals("spawn") && entry.getValue() > 0) throw new IllegalArgumentException("На обычном плане люди размещаются только в метках «Люди»");
            total += entry.getValue();
        }
        if (total > 10_000) throw new IllegalArgumentException("Допускается не более 10 000 человек");
        Set<String> passageIds = new HashSet<>(); network.passages().forEach(p -> passageIds.add(p.id()));
        for (String id : settings.blockedConnections()) if (!passageIds.contains(id)) throw new IllegalArgumentException("Блокируемого прохода больше нет в плане: " + id);
        return new Prepared(mode, network, Collections.unmodifiableMap(occupancy), settings.blockedConnections(), warnings, floors);
    }

    private Settings parseSettings(Context ctx) throws Exception {
        if (ctx.body().length() > 100_000) throw new IllegalArgumentException("Слишком много настроек сценария");
        Settings settings = json.readValue(ctx.body(), Settings.class);
        if (settings == null) throw new IllegalArgumentException("Передайте объект настроек сценария");
        if (settings.occupancy().size() > 2000 || settings.blockedConnections().size() > 5000 || settings.stairLinks().size() > 200) throw new IllegalArgumentException("Слишком много настроек сценария");
        return settings;
    }
    private Settings settings(long sid) throws Exception {
        try (var c = db.getConnection(); var statement = c.prepareStatement("SELECT settings_json FROM simulation_settings WHERE scenario_id=?")) {
            statement.setLong(1, sid);
            try (var rows = statement.executeQuery()) { return rows.next() ? json.readValue(rows.getString(1), Settings.class) : Settings.empty(); }
        }
    }
    private void writeSettings(long sid, Settings settings) throws Exception {
        try (var c = db.getConnection(); var statement = c.prepareStatement("INSERT INTO simulation_settings(scenario_id,settings_json) VALUES(?,?) ON CONFLICT(scenario_id) DO UPDATE SET settings_json=excluded.settings_json")) {
            statement.setLong(1, sid); statement.setString(2, json.writeValueAsString(settings)); statement.executeUpdate();
        }
    }
    private void saveRun(long sid, byte[] input, Run run) throws Exception {
        String output = json.writeValueAsString(run);
        if (output.getBytes(StandardCharsets.UTF_8).length > 32 * 1024 * 1024) throw new IllegalArgumentException("Результат слишком большой для сохранения: уменьшите сценарий");
        try (var c = db.getConnection()) {
            c.setAutoCommit(false);
            try (var statement = c.prepareStatement("INSERT INTO simulation_runs(id,scenario_id,created_at,input_json,result_json,total_people,evacuated_people,unreachable_people,time_seconds) VALUES(?,?,?,?,?,?,?,?,?)")) {
                statement.setString(1, run.id()); statement.setLong(2, sid); statement.setString(3, run.createdAt());
                statement.setString(4, new String(input, StandardCharsets.UTF_8)); statement.setString(5, output);
                statement.setInt(6, run.result().totalPeople()); statement.setInt(7, run.result().evacuatedPeople());
                statement.setInt(8, run.result().unreachablePeople()); statement.setDouble(9, run.result().evacuationTimeSeconds()); statement.executeUpdate();
            }
            try (var trim = c.prepareStatement("DELETE FROM simulation_runs WHERE scenario_id=? AND id NOT IN (SELECT id FROM simulation_runs WHERE scenario_id=? ORDER BY created_at DESC,id DESC LIMIT 20)")) {
                trim.setLong(1, sid); trim.setLong(2, sid); trim.executeUpdate();
            }
            c.commit();
        }
    }
    private ScenarioFullDto requireScenario(Context ctx) { return scenarios.get(bid(ctx), sid(ctx), userId(ctx)); }
    private BuildingDto building(Context ctx) { return buildings.get(bid(ctx), userId(ctx)); }
    private static long bid(Context ctx) { return id(ctx.pathParam("bid")); }
    private static long sid(Context ctx) { return id(ctx.pathParam("sid")); }
    private static long id(String value) {
        try { long id = Long.parseLong(value); if (id > 0) return id; }
        catch (NumberFormatException ignored) { }
        throw new NotFoundException("Объект не найден");
    }
    private static long userId(Context ctx) { return AuthController.currentUser(ctx).id(); }
}

package com.artemyasnik.evac.server.simulation;

import com.artemyasnik.evac.server.TestServer;
import com.artemyasnik.evac.server.TestServer.Client;
import com.artemyasnik.evac.server.TestServer.Response;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Exercises the saved platform data through the actual shared Java engine. */
class UnifiedSimulationIntegrationTest {
    @TempDir Path temporary;

    @Test void semanticOccupancyIsExplicitCapacityIsEnforcedAndBlockedExitPreservesPeople() throws Exception {
        try (var server = new TestServer(temporary.resolve("semantic.db"))) {
            Fixture fixture = fixture(server, "semantic-owner");
            long floor = floor(fixture, 1, oneRoomSvg(1, ""));
            String room = floor + "/R", exit = floor + "/E";
            JsonNode topology = success(fixture.client.send("GET", fixture.base() + "/topology", null), 200);
            assertEquals("semantic", topology.path("mode").asText());
            assertTrue(topology.path("occupancy").isEmpty());
            assertEquals(room, topology.path("nodes").get(0).path("id").asText());
            JsonNode defaults = success(fixture.client.send("GET", fixture.base() + "/simulation-settings", null), 200);
            assertTrue(defaults.path("occupancy").isEmpty());
            assertEquals(0, simulate(fixture).path("result").path("totalPeople").asInt());

            assertEquals(400, fixture.client.send("PUT", fixture.base() + "/simulation-settings", settings(Map.of(room, 5), List.of(), List.of())).status());
            success(fixture.client.send("PUT", fixture.base() + "/simulation-settings", settings(Map.of(room, 3), List.of(), List.of())), 200);
            JsonNode run = simulate(fixture);
            assertCounts(run, 3, 3, 0);
            assertEquals(8.0, run.path("result").path("evacuationTimeSeconds").asDouble(), 1e-8);
            assertEquals(3, run.path("result").path("people").size());
            assertEquals(2, run.path("result").path("strategyComparisons").size());

            success(fixture.client.send("PUT", fixture.base() + "/simulation-settings", settings(Map.of(room, 3), List.of(exit), List.of())), 200);
            JsonNode blocked = simulate(fixture);
            assertCounts(blocked, 3, 0, 3);
            for (JsonNode person : blocked.path("result").path("people")) assertEquals("unreachable", person.path("status").asText());
            JsonNode runs = success(fixture.client.send("GET", fixture.base() + "/runs", null), 200);
            assertEquals(3, runs.size());
            JsonNode stored = success(fixture.client.send("GET", fixture.base() + "/runs/" + run.path("id").asText(), null), 200);
            assertEquals(run, stored);
        }
    }

    @Test void semanticFloorsReuseRoomIdsAndDirectedStairsHaveCorrectTravelSeconds() throws Exception {
        try (var server = new TestServer(temporary.resolve("stairs.db"))) {
            Fixture fixture = fixture(server, "stairs-owner");
            long lower = floor(fixture, 1, oneRoomSvg(1, stairSvg("down")));
            long upper = floor(fixture, 2, upperRoomSvg(stairSvg("down")));
            JsonNode topology = success(fixture.client.send("GET", fixture.base() + "/topology", null), 200);
            assertEquals(2, topology.path("nodes").size());
            JsonNode stairs = findByKind(topology.path("passages"), "stairs");
            assertEquals(upper + "/R", stairs.path("from").asText());
            assertEquals(lower + "/R", stairs.path("to").asText());
            assertTrue(stairs.path("directed").asBoolean());
            assertEquals(4.0, stairs.path("traversalSeconds").asDouble());
            success(fixture.client.send("PUT", fixture.base() + "/simulation-settings", settings(Map.of(upper + "/R", 2), List.of(), List.of())), 200);
            JsonNode run = simulate(fixture);
            assertCounts(run, 2, 2, 0);
            assertEquals(12.0, run.path("result").path("evacuationTimeSeconds").asDouble(), 1e-8);
            for (JsonNode person : run.path("result").path("people")) {
                assertTrue(containsKind(person.path("timeline"), "stairs"));
                assertTrue(person.path("nodeIds").toString().contains(lower + "/R"));
                assertTrue(person.path("nodeIds").toString().contains(upper + "/R"));
            }
            success(fixture.client.send("PUT", fixture.base() + "/simulation-settings",
                    settings(Map.of(upper + "/R", 2), List.of(stairs.path("id").asText()), List.of())), 200);
            assertCounts(simulate(fixture), 2, 0, 2);
        }
    }

    @Test void rasterSavedMarksAndExplicitStairLinksUseTheSameEngineAndConserveEightPeople() throws Exception {
        try (var server = new TestServer(temporary.resolve("raster.db"))) {
            Fixture fixture = fixture(server, "raster-owner");
            long lower = floor(fixture, 1, whiteSvg()), upper = floor(fixture, 2, whiteSvg());
            success(fixture.client.send("PUT", fixture.base(), scenarioBody(8, 2.0, null)), 200);
            long spawn = mark(fixture, upper, "spawn", 20, 50);
            long upperStair = mark(fixture, upper, "stair", 180, 50);
            long lowerStair = mark(fixture, lower, "stair", 20, 50);
            mark(fixture, lower, "exit", 180, 50);
            var links = List.of(Map.of("fromMarkId", upperStair, "toMarkId", lowerStair));
            JsonNode savedSettings = success(fixture.client.send("PUT", fixture.base() + "/simulation-settings", settings(Map.of(), List.of(), links)), 200);
            assertEquals(upperStair, savedSettings.path("stairLinks").get(0).path("fromMarkId").asLong());
            JsonNode topology = success(fixture.client.send("GET", fixture.base() + "/topology", null), 200);
            assertEquals("raster", topology.path("mode").asText());
            assertEquals(8, topology.path("occupancy").path("mark:" + spawn).asInt());
            JsonNode run = simulate(fixture);
            assertEquals("raster", run.path("mode").asText());
            assertCounts(run, 8, 8, 0);
            assertEquals(29.0, run.path("result").path("evacuationTimeSeconds").asDouble(), 1e-8);
            assertEquals(8, run.path("result").path("people").size());
            for (JsonNode person : run.path("result").path("people")) assertTrue(containsKind(person.path("timeline"), "stairs"));
            JsonNode again = simulate(fixture);
            assertEquals(run.path("inputSha256"), again.path("inputSha256"));
            assertEquals(run.path("result"), again.path("result"));
        }
    }

    @Test void settingsTopologySimulationAndSavedRunsArePrivateToTheirBuildingOwner() throws Exception {
        try (var server = new TestServer(temporary.resolve("owners.db"))) {
            Fixture fixture = fixture(server, "first-owner");
            long floor = floor(fixture, 1, oneRoomSvg(1, ""));
            success(fixture.client.send("PUT", fixture.base() + "/simulation-settings", settings(Map.of(floor + "/R", 1), List.of(), List.of())), 200);
            JsonNode run = simulate(fixture);
            Client other = server.anotherClient(); other.registerAndLogin("second-owner");
            for (String suffix : List.of("/simulation-settings", "/topology", "/runs", "/runs/" + run.path("id").asText())) {
                assertEquals(404, other.send("GET", fixture.base() + suffix, null).status(), suffix);
            }
            assertEquals(404, other.send("PUT", fixture.base() + "/simulation-settings", settings(Map.of(), List.of(), List.of())).status());
            assertEquals(404, other.send("POST", fixture.base() + "/simulate", null).status());
            assertEquals(401, server.anotherClient().send("GET", fixture.base() + "/runs", null).status());
        }
    }

    @Test void malformedFractionalAndUnknownSettingsAreBadRequestsAndDoNotOverwriteSavedSettings() throws Exception {
        try (var server = new TestServer(temporary.resolve("validation.db"))) {
            Fixture fixture = fixture(server, "validation-owner");
            long floor = floor(fixture, 1, oneRoomSvg(1, ""));
            Object valid = settings(Map.of(floor + "/R", 2), List.of(), List.of());
            JsonNode saved = success(fixture.client.send("PUT", fixture.base() + "/simulation-settings", valid), 200);
            String url = fixture.base() + "/simulation-settings";
            assertEquals(400, fixture.client.raw("PUT", url, "{").status());
            assertEquals(400, fixture.client.raw("PUT", url, "{\"occupancy\":{\"" + floor + "/R\":1.5}}").status());
            assertEquals(400, fixture.client.send("PUT", url, settings(Map.of("missing-room", 2), List.of(), List.of())).status());
            assertEquals(400, fixture.client.send("PUT", url, settings(Map.of(floor + "/R", 2), List.of("missing-door"), List.of())).status());
            assertEquals(400, fixture.client.raw("POST", fixture.base() + "/simulate", "{\"occupancy\":{\"" + floor + "/R\":2.5}}").status());
            assertEquals(400, fixture.client.raw("POST", fixture.base() + "/simulate", "null").status());
            assertEquals(saved, success(fixture.client.send("GET", url, null), 200));
            assertCounts(simulate(fixture), 2, 2, 0);
        }
    }

    @Test void savedRunKeepsItsInputSnapshotAndSurvivesAFullServerRestart() throws Exception {
        Path database = temporary.resolve("restart.db");
        JsonNode savedRun;
        String base;
        try (var server = new TestServer(database)) {
            Fixture fixture = fixture(server, "restart-owner");
            long floor = floor(fixture, 1, oneRoomSvg(1, ""));
            base = fixture.base();
            success(fixture.client.send("PUT", base + "/simulation-settings", settings(Map.of(floor + "/R", 2), List.of(), List.of())), 200);
            savedRun = simulate(fixture);
            assertEquals(64, savedRun.path("inputSha256").asText().length());
            assertEquals("0.3.0", savedRun.path("engineVersion").asText());
            success(fixture.client.send("PUT", base + "/simulation-settings", settings(Map.of(floor + "/R", 1), List.of(), List.of())), 200);
            // Replacing the source plan must not rewrite the SVG or result in a previous run.
            success(fixture.client.send("PUT", "/api/buildings/" + fixture.building + "/floors/" + floor,
                    floorBody(1, oneRoomSvg(1, "<title>Изменённый план</title>"))), 200);
            assertEquals(savedRun, success(fixture.client.send("GET", base + "/runs/" + savedRun.path("id").asText(), null), 200));
        }
        try (var restarted = new TestServer(database)) {
            success(restarted.client.send("POST", "/api/auth/login", Map.of("username", "restart-owner", "password", "test-password")), 200);
            assertEquals(savedRun, success(restarted.client.send("GET", base + "/runs/" + savedRun.path("id").asText(), null), 200));
            JsonNode runs = success(restarted.client.send("GET", base + "/runs", null), 200);
            assertEquals(1, runs.size());
            JsonNode settings = success(restarted.client.send("GET", base + "/simulation-settings", null), 200);
            assertEquals(1, settings.path("occupancy").elements().next().asInt());
        }
    }

    @Test void fireScenarioCannotSilentlyProduceAnOrdinaryEvacuationResult() throws Exception {
        try (var server = new TestServer(temporary.resolve("fire.db"))) {
            Fixture fixture = fixture(server, "fire-owner");
            long floor = floor(fixture, 1, oneRoomSvg(1, ""));
            success(fixture.client.send("PUT", fixture.base() + "/simulation-settings", settings(Map.of(floor + "/R", 1), List.of(), List.of())), 200);
            success(fixture.client.send("PUT", fixture.base(), scenarioBody(1, 1.0, Map.of("floorId", floor, "x", 50, "y", 50))), 200);
            Response response = fixture.client.send("POST", fixture.base() + "/simulate", null);
            assertEquals(400, response.status());
            assertTrue(response.body().contains("пожар"));
            assertTrue(success(fixture.client.send("GET", fixture.base() + "/runs", null), 200).isEmpty());
        }
    }

    private static Fixture fixture(TestServer server, String username) throws Exception {
        server.client.registerAndLogin(username);
        JsonNode building = success(server.client.send("POST", "/api/buildings", Map.of("name", "Контрольное здание", "description", "Интеграционный сценарий")), 201);
        long bid = building.path("id").asLong();
        JsonNode scenario = success(server.client.send("POST", "/api/buildings/" + bid + "/scenarios", scenarioBody(40, 1.0, null)), 201);
        return new Fixture(server.client, bid, scenario.path("id").asLong());
    }
    private static long floor(Fixture fixture, int level, String svg) throws Exception {
        return success(fixture.client.send("POST", "/api/buildings/" + fixture.building + "/floors", floorBody(level, svg)), 201).path("id").asLong();
    }
    private static Map<String, Object> floorBody(int level, String svg) {
        return Map.of("name", "Этаж " + level, "level", level, "svg", svg, "vb", List.of(0, 0, 200, 100));
    }
    private static Map<String, Object> scenarioBody(int people, double speed, Object fire) {
        var body = new LinkedHashMap<String, Object>(); body.put("name", "Контрольный сценарий");
        body.put("params", Map.of("people", people, "speed", speed, "scale", 20.0, "stairTime", 12.0, "exitFlow", 1.0, "stairFlow", 1.0));
        body.put("fire", fire); return body;
    }
    private static Map<String, Object> settings(Map<String, Integer> occupancy, List<String> blocked, List<?> links) {
        return Map.of("occupancy", occupancy, "blockedConnections", blocked, "stairLinks", links);
    }
    private static long mark(Fixture fixture, long floor, String kind, double x, double y) throws Exception {
        var body = new LinkedHashMap<String, Object>(); body.put("floorId", floor); body.put("kind", kind); body.put("x", x); body.put("y", y);
        if (!kind.equals("spawn")) body.put("width", 1.0);
        return success(fixture.client.send("POST", fixture.base() + "/marks", body), 201).path("id").asLong();
    }
    private static JsonNode simulate(Fixture fixture) throws Exception { return success(fixture.client.send("POST", fixture.base() + "/simulate", null), 200); }
    private static JsonNode success(Response response, int status) {
        assertEquals(status, response.status(), response.body()); assertNotNull(response.json(), response.body()); return response.json();
    }
    private static void assertCounts(JsonNode run, int total, int evacuated, int unreachable) {
        JsonNode result = run.path("result");
        assertEquals(total, result.path("totalPeople").asInt());
        assertEquals(evacuated, result.path("evacuatedPeople").asInt());
        assertEquals(unreachable, result.path("unreachablePeople").asInt());
        assertEquals(total, result.path("people").size());
    }
    private static JsonNode findByKind(JsonNode list, String kind) {
        for (JsonNode node : list) if (node.path("kind").asText().equals(kind)) return node;
        throw new AssertionError("No " + kind + " in " + list);
    }
    private static boolean containsKind(JsonNode list, String kind) {
        for (JsonNode node : list) if (node.path("kind").asText().equals(kind)) return true;
        return false;
    }
    private static String oneRoomSvg(int level, String extra) {
        return "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 200 100\" data-floor=\"" + level + "\" data-scale=\"0.1\">" +
                "<rect data-type=\"room\" data-id=\"R\" data-capacity=\"4\" x=\"0\" y=\"0\" width=\"100\" height=\"100\"/>" +
                "<line data-type=\"exit\" data-id=\"E\" data-connects=\"R,OUTSIDE\" data-throughput=\"1\" data-width=\"1\" x1=\"100\" y1=\"40\" x2=\"100\" y2=\"60\"/>" + extra + "</svg>";
    }
    private static String upperRoomSvg(String extra) {
        return "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 200 100\" data-floor=\"2\" data-scale=\"0.1\">" +
                "<rect data-type=\"room\" data-id=\"R\" data-capacity=\"4\" x=\"0\" y=\"0\" width=\"100\" height=\"100\"/>" + extra + "</svg>";
    }
    private static String stairSvg(String direction) {
        return "<rect data-type=\"stairs\" data-id=\"S\" data-connects=\"R@1,R@2\" data-direction=\"" + direction + "\" data-throughput=\"1\" data-width=\"1\" data-travel-time-seconds=\"4\" x=\"45\" y=\"45\" width=\"10\" height=\"10\"/>";
    }
    private static String whiteSvg() { return "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 200 100\"/>"; }
    private record Fixture(Client client, long building, long scenario) {
        String base() { return "/api/buildings/" + building + "/scenarios/" + scenario; }
    }
}

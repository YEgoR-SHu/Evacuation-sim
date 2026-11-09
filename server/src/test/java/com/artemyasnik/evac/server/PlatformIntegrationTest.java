package com.artemyasnik.evac.server;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class PlatformIntegrationTest {
    @TempDir Path temp;
    private static final String SVG = "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 100 100\"><rect x=\"0\" y=\"0\" width=\"100\" height=\"100\" fill=\"white\"/></svg>";

    @Test void authenticationOwnershipAndLogoutUseServerSessions() throws Exception {
        try (var test = new TestServer(temp.resolve("auth.db"))) {
            var owner = test.client;
            assertEquals(401, owner.send("GET", "/api/buildings", null).status());
            assertEquals(400, owner.send("POST", "/api/auth/login", Map.of("username", "admin", "password", "admin")).status());
            owner.registerAndLogin("owner");
            var building = owner.send("POST", "/api/buildings", Map.of("name", "Учебный корпус"));
            assertEquals(201, building.status(), building.body());
            long id = building.json().path("id").asLong();
            var other = test.anotherClient();
            other.registerAndLogin("other");
            assertEquals(404, other.send("GET", "/api/buildings/" + id, null).status());
            assertEquals(404, other.send("DELETE", "/api/buildings/" + id, null).status());
            assertEquals(200, owner.send("GET", "/api/buildings/" + id, null).status());
            assertEquals(204, owner.send("POST", "/api/auth/logout", null).status());
            assertEquals(401, owner.send("GET", "/api/auth/me", null).status());
            assertEquals(401, owner.send("GET", "/api/buildings", null).status());
        }
    }

    @Test void floorValidationRejectsBrokenPlansAndRemovesActiveContent() throws Exception {
        try (var test = new TestServer(temp.resolve("floors.db"))) {
            var c = test.client;
            c.registerAndLogin("planner");
            long bid = createBuilding(c);
            String path = "/api/buildings/" + bid + "/floors";
            var floor = c.send("POST", path, floor(SVG, 1));
            assertEquals(201, floor.status(), floor.body());
            assertEquals(409, c.send("POST", path, floor(SVG, 1)).status());
            var sanitized = c.send("POST", path, floor("<svg xmlns=\"http://www.w3.org/2000/svg\" onload=\"alert(1)\"><script>alert(1)</script></svg>", 2));
            assertEquals(201, sanitized.status(), sanitized.body());
            assertFalse(sanitized.json().path("svg").asText().contains("onload"));
            assertFalse(sanitized.json().path("svg").asText().contains("script"));
            // Старые базы могли сохранить SVG до введения очистки.
            long originalFloorId = floor.json().path("id").asLong();
            try (var connection = test.server.db().getConnection();
                 var update = connection.prepareStatement("UPDATE floors SET svg = ? WHERE id = ?")) {
                update.setString(1, "<svg xmlns=\"http://www.w3.org/2000/svg\" onload=\"alert(1)\"><script>alert(1)</script></svg>");
                update.setLong(2, originalFloorId);
                update.executeUpdate();
            }
            var oldFloor = c.send("GET", path + "/" + originalFloorId, null);
            assertEquals(200, oldFloor.status());
            assertFalse(oldFloor.json().path("svg").asText().contains("onload"));
            var oldBuilding = c.send("GET", "/api/buildings/" + bid, null);
            assertFalse(oldBuilding.json().path("floors").get(0).path("svg").asText().contains("script"));
            var svgExport = c.send("GET", path + "/" + originalFloorId + "/svg", null);
            assertEquals(200, svgExport.status());
            assertFalse(svgExport.body().contains("onload"));
            assertEquals(400, c.send("POST", path, floor("<svg><broken>", 2)).status());
            assertEquals(400, c.send("POST", path, Map.of("name", "Bad", "level", 2, "svg", SVG, "vb", List.of(0, 0, -1, 100))).status());
            assertEquals(400, c.raw("POST", path, "{invalid json").status());
            assertEquals(400, c.raw("POST", path, "null").status());
        }
    }

    @Test void scenarioAndMarkValidationDoesNotPersistInvalidNumbers() throws Exception {
        try (var test = new TestServer(temp.resolve("validation.db"))) {
            var c = test.client;
            c.registerAndLogin("planner");
            long bid = createBuilding(c);
            long fid = c.send("POST", "/api/buildings/" + bid + "/floors", floor(SVG, 1)).json().path("id").asLong();
            String scenarios = "/api/buildings/" + bid + "/scenarios";
            assertEquals(400, c.send("POST", scenarios, Map.of("name", "Invalid", "params", Map.of("people", -1))).status());
            assertEquals(400, c.send("POST", scenarios, Map.of("name", "Fractional", "params", Map.of("people", 1.5))).status());
            assertEquals(400, c.send("POST", scenarios, Map.of("name", "Invalid", "params", Map.of("speed", 0))).status());
            assertEquals(400, c.send("POST", scenarios, Map.of("name", "Invalid", "params", Map.of("scale", -20))).status());
            var valid = c.send("POST", scenarios, Map.of("name", "Empty", "params", Map.of("people", 0)));
            assertEquals(201, valid.status(), valid.body());
            long sid = valid.json().path("id").asLong();
            String marks = scenarios + "/" + sid + "/marks";
            assertEquals(400, c.send("POST", marks, Map.of("floorId", fid, "kind", "exit", "x", 10, "y", 10, "width", 0)).status());
            assertEquals(400, c.send("POST", marks, Map.of("floorId", fid, "kind", "spawn", "x", 101, "y", 10)).status());
            assertEquals(201, c.send("POST", marks, Map.of("floorId", fid, "kind", "spawn", "x", 10, "y", 10)).status());
            assertEquals(1, c.send("GET", marks, null).json().size());
            assertEquals(1, c.send("GET", scenarios, null).json().size());
        }
    }

    @Test void everyPooledConnectionEnforcesForeignKeysAndDeletesCascade() throws Exception {
        try (var test = new TestServer(temp.resolve("cascade.db"))) {
            var c = test.client;
            c.registerAndLogin("planner");
            long bid = createBuilding(c);
            long fid = c.send("POST", "/api/buildings/" + bid + "/floors", floor(SVG, 1)).json().path("id").asLong();
            long sid = c.send("POST", "/api/buildings/" + bid + "/scenarios", Map.of("name", "Base")).json().path("id").asLong();
            assertEquals(201, c.send("POST", "/api/buildings/" + bid + "/scenarios/" + sid + "/marks", Map.of("floorId", fid, "kind", "exit", "x", 90, "y", 10, "width", 1)).status());
            try (var first = test.server.db().getConnection(); var second = test.server.db().getConnection()) {
                for (var connection : List.of(first, second)) {
                    try (var statement = connection.createStatement(); var rows = statement.executeQuery("PRAGMA foreign_keys")) {
                        assertTrue(rows.next());
                        assertEquals(1, rows.getInt(1));
                    }
                }
            }
            assertEquals(204, c.send("DELETE", "/api/buildings/" + bid, null).status());
            try (var connection = test.server.db().getConnection(); var statement = connection.createStatement()) {
                for (String table : List.of("floors", "scenarios", "scenario_marks")) {
                    try (var rows = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
                        assertTrue(rows.next()); assertEquals(0, rows.getInt(1), table);
                    }
                }
            }
        }
    }

    @Test void dataSurvivesRestartAndNoDefaultAccountIsCreated() throws Exception {
        Path db = temp.resolve("restart.db");
        long bid;
        try (var test = new TestServer(db)) {
            try (var connection = test.server.db().getConnection(); var statement = connection.createStatement(); var rows = statement.executeQuery("SELECT COUNT(*) FROM users")) {
                assertTrue(rows.next()); assertEquals(0, rows.getInt(1));
            }
            test.client.registerAndLogin("planner");
            bid = createBuilding(test.client);
        }
        try (var test = new TestServer(db)) {
            assertEquals(200, test.client.send("POST", "/api/auth/login", Map.of("username", "planner", "password", "test-password")).status());
            assertEquals(200, test.client.send("GET", "/api/buildings/" + bid, null).status());
        }
    }

    @Test void packagedPagesAndAssetsAreServedWithTheApi() throws Exception {
        try (var test = new TestServer(temp.resolve("static.db"))) {
            assertEquals("0.3.0", test.client.send("GET", "/api/health", null).json().path("version").asText());
            assertEquals(200, test.client.send("GET", "/api/health/db", null).status());
            int assetCount = 0;
            var browser = java.net.http.HttpClient.newHttpClient();
            String origin = "http://127.0.0.1:" + test.server.app().port();
            for (String path : List.of("/login.html", "/buildings.html", "/building.html", "/scenario.html", "/viewer.html")) {
                var response = test.client.send("GET", path, null);
                assertEquals(200, response.status(), path + ": " + response.body());
                assertFalse(response.body().isBlank(), path);
                var assets = java.util.regex.Pattern.compile("(?:src|href)=\"(/[^\"]+\\.(?:js|css))\"").matcher(response.body());
                while (assets.find()) {
                    String assetPath = assets.group(1);
                    var asset = test.client.send("GET", assetPath, null);
                    assertEquals(200, asset.status(), assetPath);
                    assertFalse(asset.body().isBlank(), assetPath);
                    // ES modules send Origin even for same-origin script/style requests.
                    var moduleRequest = java.net.http.HttpRequest.newBuilder(java.net.URI.create(origin + assetPath))
                            .timeout(java.time.Duration.ofSeconds(10)).header("Origin", origin).GET().build();
                    var moduleResponse = browser.send(moduleRequest, java.net.http.HttpResponse.BodyHandlers.ofString());
                    assertEquals(200, moduleResponse.statusCode(), "Same-origin module: " + assetPath);
                    assertFalse(moduleResponse.body().isBlank(), assetPath);
                    assetCount++;
                }
            }
            assertTrue(assetCount >= 5, "Bundled pages must reference real JavaScript and CSS resources");
        }
    }

    private static long createBuilding(TestServer.Client client) throws Exception {
        var result = client.send("POST", "/api/buildings", Map.of("name", "Корпус"));
        assertEquals(201, result.status(), result.body());
        return result.json().path("id").asLong();
    }
    private static Map<String, Object> floor(String svg, int level) {
        return Map.of("name", "Этаж " + level, "level", level, "svg", svg, "vb", List.of(0, 0, 100, 100));
    }
}

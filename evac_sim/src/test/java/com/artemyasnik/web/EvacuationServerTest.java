package com.artemyasnik.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** Проверяет общий конвейер HTTP → Jackson → SVG/Batik → JGraphT → JSON. */
@Timeout(20)
class EvacuationServerTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static EvacuationServer server;
    private static HttpClient client;
    private static String officeSvg;

    @BeforeAll
    static void startServer() throws Exception {
        server = EvacuationServer.start(0);
        client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
                .proxy(new ProxySelector() {
                    @Override public List<Proxy> select(URI uri) { return List.of(Proxy.NO_PROXY); }
                    @Override public void connectFailed(URI uri, SocketAddress address, IOException failure) { }
                }).build();
        try (var input = EvacuationServerTest.class.getResourceAsStream("/examples/example.svg")) {
            assertNotNull(input);
            officeSvg = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @AfterAll
    static void closeServer() {
        if (client != null) client.close();
        if (server != null) server.close();
    }

    private HttpResponse<String> request(String method, String path, String body, String contentType) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + path))
                .timeout(Duration.ofSeconds(10));
        if (contentType != null) builder.header("Content-Type", contentType);
        builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private String payload(Map<String, Integer> occupancy, Set<String> blocked) throws Exception {
        return JSON.writeValueAsString(Map.of("svg", officeSvg, "occupancy", occupancy,
                "blockedConnections", blocked, "walkingSpeedMps", 1.3));
    }

    private JsonNode assertJson(HttpResponse<String> response, int status) throws Exception {
        assertEquals(status, response.statusCode(), response.body());
        assertTrue(response.headers().firstValue("Content-Type").orElse("").startsWith("application/json"));
        assertEquals("nosniff", response.headers().firstValue("X-Content-Type-Options").orElse(""));
        return JSON.readTree(response.body());
    }

    @Test
    void officePopulationTravelsThroughRealParsedConnectionsToOutside() throws Exception {
        Map<String, Integer> counts = Map.of("R101", 8, "R103", 12, "R106", 10);
        JsonNode result = assertJson(request("POST", "/api/analyze", payload(counts, Set.of()), "application/json; charset=utf-8"), 200);
        assertEquals(30, result.path("totalPeople").asInt());
        assertEquals(30, result.path("evacuatedPeople").asInt());
        assertEquals(0, result.path("unreachablePeople").asInt());
        assertTrue(result.path("evacuationTimeSeconds").asDouble() > 0);
        assertEquals(11, result.path("nodes").size());
        assertEquals(12, result.path("connections").size());
        int routedPeople = 0;
        for (JsonNode route : result.path("routes")) {
            JsonNode nodes = route.path("nodeIds");
            assertEquals(route.path("sourceNodeId").asText(), nodes.get(0).asText());
            assertEquals("OUTSIDE", nodes.get(nodes.size() - 1).asText());
            assertEquals(nodes.size() - 1, route.path("connectionIds").size());
            assertTrue(route.path("lastArrivalSeconds").asDouble() >= route.path("firstArrivalSeconds").asDouble());
            routedPeople += route.path("people").asInt();
        }
        assertEquals(30, routedPeople);
        assertFalse(result.path("bottlenecks").isEmpty());
    }

    @Test
    void closingAllExitsPreservesPopulationAndReportsUnreachablePeople() throws Exception {
        JsonNode result = assertJson(request("POST", "/api/analyze", payload(Map.of("R101", 8), Set.of("E_left", "E_right")), "application/json"), 200);
        assertEquals(8, result.path("totalPeople").asInt());
        assertEquals(0, result.path("evacuatedPeople").asInt());
        assertEquals(8, result.path("unreachablePeople").asInt());
        assertTrue(result.path("routes").isEmpty());
        assertTrue(result.path("warnings").size() > 0);
        for (JsonNode connection : result.path("connections")) {
            if (connection.path("kind").asText().equals("exit")) assertTrue(connection.path("blocked").asBoolean());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"null", "{}", "{", "{\"svg\":\"<svg/>\",\"unknown\":true}",
            "{\"svg\":\"<svg/>\",\"occupancy\":[]}", "{\"svg\":\"<svg/>\",\"walkingSpeedMps\":NaN}",
            "{\"svg\":\"<svg/>\"} null"})
    void invalidJsonAndWrongRequestShapesAreClientErrors(String body) throws Exception {
        assertTrue(assertJson(request("POST", "/api/analyze", body, "application/json"), 400).hasNonNull("error"));
    }

    @Test
    void fractionalOrNullOccupancyNeverGetsSilentlyRoundedOrDropped() throws Exception {
        String svg = JSON.writeValueAsString(officeSvg);
        for (String count : List.of("1.9", "null")) {
            String body = "{\"svg\":" + svg + ",\"occupancy\":{\"R101\":" + count + "}}";
            assertJson(request("POST", "/api/analyze", body, "application/json"), 400);
        }
    }

    @Test
    void invalidDomainInputIsReportedWithoutBreakingLaterCalculations() throws Exception {
        for (String body : List.of(payload(Map.of("R101", 9), Set.of()),
                payload(Map.of("missing", 1), Set.of()), payload(Map.of("R101", 1), Set.of("missing")),
                "{\"svg\":" + JSON.writeValueAsString(officeSvg) + ",\"walkingSpeedMps\":\"NaN\"}")) {
            assertJson(request("POST", "/api/analyze", body, "application/json"), 400);
        }
        JsonNode valid = assertJson(request("POST", "/api/analyze", payload(Map.of("R101", 1), Set.of()), "application/json"), 200);
        assertEquals(1, valid.path("evacuatedPeople").asInt());
    }

    @Test
    void malformedXmlAndExternalEntitiesAreRejectedAtTheHttpBoundary() throws Exception {
        for (String svg : List.of("<svg>", "<!DOCTYPE svg [<!ENTITY x SYSTEM 'file:///does-not-exist'>]><svg><text>&x;</text></svg>")) {
            String body = JSON.writeValueAsString(Map.of("svg", svg));
            assertJson(request("POST", "/api/analyze", body, "application/json"), 400);
        }
    }

    @Test
    void requestSizeAndMediaTypeHaveExplicitErrors() throws Exception {
        assertJson(request("POST", "/api/analyze", " ".repeat(EvacuationServer.MAX_REQUEST_BYTES + 1), "application/json"), 413);
        assertJson(request("POST", "/api/analyze", payload(Map.of(), Set.of()), "text/plain"), 415);
        assertJson(request("POST", "/api/analyze", "{}", null), 415);
    }

    @Test
    void onlySupportedApiMethodsAreAccepted() throws Exception {
        var analyze = request("GET", "/api/analyze", null, null);
        assertJson(analyze, 405);
        assertEquals("POST", analyze.headers().firstValue("Allow").orElse(""));
        var health = request("POST", "/api/health", "{}", "application/json");
        assertJson(health, 405);
        assertEquals("GET", health.headers().firstValue("Allow").orElse(""));
        assertJson(request("POST", "/", "{}", "application/json"), 405);
    }

    @Test
    void browserAssetsExamplesHealthAndHeadAreServedFromTheSameApplication() throws Exception {
        JsonNode health = assertJson(request("GET", "/api/health", null, null), 200);
        assertEquals("ok", health.path("status").asText());
        assertEquals("0.2.0", health.path("version").asText());
        var root = request("GET", "/", null, null);
        assertEquals(200, root.statusCode());
        assertTrue(root.body().contains("planner.js"));
        for (String asset : List.of("/planner.js", "/planner.css", "/examples/example.svg")) {
            var response = request("GET", asset, null, null);
            assertEquals(200, response.statusCode(), asset);
            assertFalse(response.body().isEmpty());
            var head = request("HEAD", asset, null, null);
            assertEquals(200, head.statusCode(), asset);
            assertTrue(head.body().isEmpty());
            assertEquals(Integer.toString(response.body().getBytes(StandardCharsets.UTF_8).length),
                    head.headers().firstValue("Content-Length").orElse(""));
        }
    }

    @Test
    void traversalAndSimilarApiPrefixesDoNotExposeResourcesOrRunCalculations() throws Exception {
        for (String path : List.of("/examples/%2e%2e/pom.xml", "/../pom.xml", "/missing.html", "/api/health/details")) {
            assertJson(request("GET", path, null, null), 404);
        }
        assertJson(request("POST", "/api/analyze/details", "{}", "application/json"), 404);
    }
}

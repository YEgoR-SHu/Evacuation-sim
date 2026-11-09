package com.artemyasnik.evac.server;

import com.artemyasnik.evac.server.config.Config;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;

public final class TestServer implements AutoCloseable {
    public static final ObjectMapper JSON = new ObjectMapper();
    public final ServerApp.RunningServer server;
    public final Client client;
    public TestServer(Path path) {
        server = ServerApp.create(new Config(0, path, "", "", 24));
        client = new Client(server.app().port());
    }
    public Client anotherClient() { return new Client(server.app().port()); }
    @Override public void close() { server.close(); }
    public record Response(int status, JsonNode json, String body) { }
    public static final class Client {
        private final HttpClient http;
        private final String base;
        public Client(int port) {
            CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
            http = HttpClient.newBuilder().cookieHandler(cookies).connectTimeout(Duration.ofSeconds(5)).build();
            base = "http://127.0.0.1:" + port;
        }
        public Response send(String method, String path, Object body) throws Exception {
            String json = body == null ? "" : JSON.writeValueAsString(body);
            return raw(method, path, json);
        }
        public Response raw(String method, String path, String body) throws Exception {
            HttpRequest request = HttpRequest.newBuilder(URI.create(base + path))
                    .timeout(Duration.ofSeconds(15)).header("Content-Type", "application/json")
                    .method(method, body.isEmpty() ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            JsonNode data = null;
            try { if (!response.body().isBlank()) data = JSON.readTree(response.body()); }
            catch (Exception ignored) { }
            return new Response(response.statusCode(), data, response.body());
        }
        public void registerAndLogin(String username) throws Exception {
            var input = java.util.Map.of("username", username, "password", "test-password");
            var register = send("POST", "/api/auth/register", input);
            if (register.status() != 201) throw new AssertionError(register);
            var login = send("POST", "/api/auth/login", input);
            if (login.status() != 200) throw new AssertionError(login);
        }
    }
}

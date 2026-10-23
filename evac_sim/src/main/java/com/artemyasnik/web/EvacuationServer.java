package com.artemyasnik.web;

import com.artemyasnik.parser.SvgFloorPlanParser;
import com.artemyasnik.simulation.SimulationService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.xml.sax.SAXException;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;

/** Локальный HTTP-адаптер: один расчётный конвейер для SVG, графа и интерфейса. */
public final class EvacuationServer implements AutoCloseable {
    static final int MAX_REQUEST_BYTES = 2 * 1024 * 1024;
    private final HttpServer server;
    private final ExecutorService executor;
    private final Semaphore calculations = new Semaphore(2);
    private final ObjectMapper json = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
            .build();

    private EvacuationServer(int port) throws IOException {
        if (port < 0 || port > 65535) throw new IllegalArgumentException("Порт должен быть от 0 до 65535");
        json.getFactory().setStreamReadConstraints(StreamReadConstraints.builder()
                .maxNestingDepth(32).maxStringLength(MAX_REQUEST_BYTES).build());
        server = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), 32);
        executor = Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(executor);
        server.createContext("/api/analyze", this::analyze);
        server.createContext("/api/health", this::health);
        server.createContext("/", this::staticFile);
    }

    public static EvacuationServer start(int port) throws IOException {
        EvacuationServer instance = new EvacuationServer(port);
        instance.server.start();
        return instance;
    }

    public int port() { return server.getAddress().getPort(); }

    public record AnalysisRequest(String svg, Map<String, Integer> occupancy,
                                  Set<String> blockedConnections, Double walkingSpeedMps) {}

    private void analyze(HttpExchange exchange) throws IOException {
        try (exchange) {
            if (!exchange.getRequestURI().getPath().equals("/api/analyze")) {
                respond(exchange, 404, Map.of("error", "Адрес не найден"));
                return;
            }
            if (!exchange.getRequestMethod().equals("POST")) {
                exchange.getResponseHeaders().set("Allow", "POST");
                respond(exchange, 405, Map.of("error", "Используйте POST"));
                return;
            }
            String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
            if (contentType == null || !contentType.split(";", 2)[0].trim().equalsIgnoreCase("application/json")) {
                respond(exchange, 415, Map.of("error", "Ожидается Content-Type: application/json"));
                return;
            }
            if (!calculations.tryAcquire()) {
                respond(exchange, 429, Map.of("error", "Расчёт занят. Повторите запрос через несколько секунд"));
                return;
            }
            try {
                byte[] bytes = exchange.getRequestBody().readNBytes(MAX_REQUEST_BYTES + 1);
                if (bytes.length > MAX_REQUEST_BYTES) {
                    respond(exchange, 413, Map.of("error", "Размер запроса превышает 2 МиБ"));
                    return;
                }
                AnalysisRequest request = json.readValue(bytes, AnalysisRequest.class);
                if (request == null || request.svg() == null || request.svg().isBlank()) {
                    throw new IllegalArgumentException("Загрузите SVG-план с разметкой data-type и data-connects");
                }
                var plan = new SvgFloorPlanParser().parse(new ByteArrayInputStream(request.svg().getBytes(StandardCharsets.UTF_8)));
                var result = new SimulationService().analyze(plan, request.occupancy(),
                        request.blockedConnections() == null ? Set.of() : request.blockedConnections(),
                        request.walkingSpeedMps() == null ? 1.3 : request.walkingSpeedMps());
                respond(exchange, 200, result);
            } catch (JsonProcessingException e) {
                respond(exchange, 400, Map.of("error", "Некорректный JSON или тип поля запроса"));
            } catch (SAXException e) {
                respond(exchange, 400, Map.of("error", "Некорректный SVG: требуется XML без DTD и внешних сущностей"));
            } catch (IllegalArgumentException e) {
                respond(exchange, 400, Map.of("error", e.getMessage() == null ? "Некорректные параметры расчёта" : e.getMessage()));
            } catch (Exception e) {
                System.err.println("Ошибка расчёта: " + e.getClass().getSimpleName());
                respond(exchange, 500, Map.of("error", "Не удалось выполнить расчёт"));
            } finally {
                calculations.release();
            }
        }
    }

    private void health(HttpExchange exchange) throws IOException {
        try (exchange) {
            if (!exchange.getRequestURI().getPath().equals("/api/health")) {
                respond(exchange, 404, Map.of("error", "Адрес не найден"));
            } else if (!exchange.getRequestMethod().equals("GET")) {
                exchange.getResponseHeaders().set("Allow", "GET");
                respond(exchange, 405, Map.of("error", "Используйте GET"));
            } else respond(exchange, 200, Map.of("status", "ok", "version", "0.2.0"));
        }
    }

    private void staticFile(HttpExchange exchange) throws IOException {
        try (exchange) {
            String method = exchange.getRequestMethod();
            if (!method.equals("GET") && !method.equals("HEAD")) {
                exchange.getResponseHeaders().set("Allow", "GET, HEAD");
                respond(exchange, 405, Map.of("error", "Используйте GET"));
                return;
            }
            String path = exchange.getRequestURI().getPath();
            if (path.equals("/")) path = "/index.html";
            if (!path.matches("/[a-zA-Z0-9_./-]+") || path.contains("..")) {
                respond(exchange, 404, Map.of("error", "Файл не найден"));
                return;
            }
            String resource = path.startsWith("/examples/") ? path : "/static" + path;
            try (var input = EvacuationServer.class.getResourceAsStream(resource)) {
                if (input == null) {
                    respond(exchange, 404, Map.of("error", "Файл не найден"));
                    return;
                }
                byte[] bytes = input.readAllBytes();
                String type = path.endsWith(".html") ? "text/html; charset=utf-8"
                        : path.endsWith(".js") ? "text/javascript; charset=utf-8"
                        : path.endsWith(".css") ? "text/css; charset=utf-8"
                        : path.endsWith(".svg") ? "image/svg+xml" : "application/octet-stream";
                headers(exchange, type);
                if (method.equals("HEAD")) {
                    exchange.getResponseHeaders().set("Content-Length", Integer.toString(bytes.length));
                    exchange.sendResponseHeaders(200, -1);
                } else {
                    exchange.sendResponseHeaders(200, bytes.length);
                    exchange.getResponseBody().write(bytes);
                }
            }
        }
    }

    private void respond(HttpExchange exchange, int status, Object body) throws IOException {
        byte[] bytes = json.writeValueAsBytes(body);
        headers(exchange, "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    private void headers(HttpExchange exchange, String contentType) {
        var headers = exchange.getResponseHeaders();
        headers.set("Content-Type", contentType);
        headers.set("Cache-Control", "no-store");
        headers.set("X-Content-Type-Options", "nosniff");
        headers.set("Referrer-Policy", "no-referrer");
        headers.set("Content-Security-Policy", "default-src 'self'; script-src 'self' 'unsafe-inline'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; object-src 'none'; base-uri 'self'; frame-ancestors 'none'");
    }

    @Override public void close() {
        server.stop(0);
        executor.close();
    }
}

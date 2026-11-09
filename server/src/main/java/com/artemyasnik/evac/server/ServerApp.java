package com.artemyasnik.evac.server;

import com.artemyasnik.evac.server.auth.*;
import com.artemyasnik.evac.server.building.*;
import com.artemyasnik.evac.server.config.*;
import com.artemyasnik.evac.server.simulation.SimulationController;
import com.artemyasnik.evac.server.web.GlobalExceptionHandler;
import com.artemyasnik.evac.server.web.StaticFiles;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.DeserializationFeature;
import io.javalin.Javalin;
import io.javalin.config.RoutesConfig;
import io.javalin.json.JavalinJackson;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Единственный сервер объединённого приложения: интерфейс, данные и Java-расчёт. */
public final class ServerApp {
    private static final Logger log = LoggerFactory.getLogger(ServerApp.class);
    private ServerApp() { }

    public static void main(String[] args) {
        RunningServer server = create(Config.fromEnv());
        Runtime.getRuntime().addShutdownHook(new Thread(server::close, "shutdown-hook"));
    }

    /** Запускает отдельный экземпляр. Порт 0 выбирает свободный порт для интеграционных тестов. */
    public static RunningServer create(Config config) {
        Database db = new Database(config.dbPath());
        ScheduledExecutorService cleaner = null;
        Javalin app = null;
        try {
            Migrations.run(db.getDataSource());
            ObjectMapper mapper = new ObjectMapper()
                    .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
                    .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
            UserRepository users = new UserRepository(db);
            SessionRepository sessions = new SessionRepository(db);
            BuildingRepository buildings = new BuildingRepository(db);
            FloorRepository floors = new FloorRepository(db);
            ScenarioRepository scenarios = new ScenarioRepository(db, mapper);
            MarkRepository marks = new MarkRepository(db);
            AuthService auth = new AuthService(users, sessions, config.sessionTtlHours());
            BuildingService buildingService = new BuildingService(buildings, floors, scenarios);
            FloorService floorService = new FloorService(floors, buildings);
            ScenarioService scenarioService = new ScenarioService(scenarios, buildings, floors, marks);
            MarkService markService = new MarkService(marks, scenarios, floors, buildings);
            AuthController authCtrl = new AuthController(auth, config.sessionTtlHours());
            BuildingController buildingCtrl = new BuildingController(buildingService);
            FloorController floorCtrl = new FloorController(floorService);
            ScenarioController scenarioCtrl = new ScenarioController(scenarioService);
            MarkController markCtrl = new MarkController(markService);

            // Установка первого пользователя возможна только с явно заданными учётными данными.
            if (users.countAll() == 0 && !config.adminUsername().isBlank()) {
                auth.register(config.adminUsername(), config.adminPassword());
            }

            app = Javalin.create(cfg -> {
                cfg.startup.showJavalinBanner = false;
                cfg.jsonMapper(new JavalinJackson(mapper, false));
                cfg.http.maxRequestSize = 4 * 1024 * 1024;
                StaticFiles.register(cfg);
                GlobalExceptionHandler.register(cfg.routes);
                cfg.routes.after(ctx -> {
                    ctx.header("X-Content-Type-Options", "nosniff");
                    ctx.header("Referrer-Policy", "same-origin");
                });
                cfg.routes.before("/api/*", authCtrl::attachUser);
                cfg.routes.before("/api/buildings", authCtrl::requireAuth);
                cfg.routes.before("/api/buildings/*", authCtrl::requireAuth);
                registerApiRoutes(cfg.routes, db, authCtrl, buildingCtrl, floorCtrl, scenarioCtrl, markCtrl);
                new SimulationController(buildingService, scenarioService, db).register(cfg.routes);
            });

            cleaner = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "session-cleaner");
                t.setDaemon(true);
                return t;
            });
            cleaner.scheduleAtFixedRate(() -> {
                try { auth.cleanupExpiredSessions(); }
                catch (Exception e) { log.warn("Ошибка очистки сессий", e); }
            }, 1, 1, TimeUnit.HOURS);
            app.start(config.host(), config.port());
            return new RunningServer(app, db, cleaner);
        } catch (RuntimeException e) {
            if (cleaner != null) cleaner.shutdownNow();
            if (app != null) app.stop();
            db.close();
            throw e;
        }
    }

    private static void registerApiRoutes(RoutesConfig app, Database db, AuthController auth,
            BuildingController buildings, FloorController floors, ScenarioController scenarios,
            MarkController marks) {
        app.post("/api/auth/register", auth::register);
        app.post("/api/auth/login", auth::login);
        app.post("/api/auth/logout", auth::logout);
        app.get("/api/auth/me", auth::me);
        app.get("/api/buildings", buildings::list);
        app.post("/api/buildings", buildings::create);
        app.get("/api/buildings/{id}", buildings::get);
        app.put("/api/buildings/{id}", buildings::update);
        app.delete("/api/buildings/{id}", buildings::delete);
        app.get("/api/buildings/{bid}/floors", floors::list);
        app.post("/api/buildings/{bid}/floors", floors::create);
        app.get("/api/buildings/{bid}/floors/{fid}", floors::get);
        app.get("/api/buildings/{bid}/floors/{fid}/svg", floors::getSvg);
        app.put("/api/buildings/{bid}/floors/{fid}", floors::update);
        app.delete("/api/buildings/{bid}/floors/{fid}", floors::delete);
        app.get("/api/buildings/{bid}/scenarios", scenarios::list);
        app.post("/api/buildings/{bid}/scenarios", scenarios::create);
        app.get("/api/buildings/{bid}/scenarios/{sid}", scenarios::get);
        app.put("/api/buildings/{bid}/scenarios/{sid}", scenarios::update);
        app.delete("/api/buildings/{bid}/scenarios/{sid}", scenarios::delete);
        app.post("/api/buildings/{bid}/scenarios/{sid}/default", scenarios::setDefault);
        app.get("/api/buildings/{bid}/scenarios/{sid}/marks", marks::list);
        app.post("/api/buildings/{bid}/scenarios/{sid}/marks", marks::create);
        app.put("/api/buildings/{bid}/scenarios/{sid}/marks/{mid}", marks::update);
        app.delete("/api/buildings/{bid}/scenarios/{sid}/marks/{mid}", marks::delete);
        app.delete("/api/buildings/{bid}/scenarios/{sid}/marks", marks::deleteAll);
        app.get("/api/health", ctx -> ctx.json(Map.of("status", "ok", "service", "evac-sim", "version", "0.3.0")));
        app.get("/api/health/db", ctx -> {
            boolean alive = db.ping();
            ctx.status(alive ? 200 : 503).json(Map.of("database", alive ? "ok" : "down", "tables", alive ? db.countUserTables() : -1));
        });
        app.get("/", ctx -> ctx.redirect("/buildings.html"));
    }

    public static final class RunningServer implements AutoCloseable {
        private final Javalin app;
        private final Database db;
        private final ScheduledExecutorService cleaner;
        private final AtomicBoolean stopped = new AtomicBoolean();
        private RunningServer(Javalin app, Database db, ScheduledExecutorService cleaner) {
            this.app = app; this.db = db; this.cleaner = cleaner;
        }
        public Javalin app() { return app; }
        public Database db() { return db; }
        public void stop() { close(); }
        @Override public void close() {
            if (stopped.compareAndSet(false, true)) {
                cleaner.shutdownNow();
                try { app.stop(); } finally { db.close(); }
            }
        }
    }
}

package com.artemyasnik.evac.server.building;

import com.artemyasnik.evac.server.auth.AuthController;
import com.artemyasnik.evac.server.dto.ScenarioRequest;
import com.artemyasnik.evac.server.dto.UserDto;
import io.javalin.http.Context;
import com.artemyasnik.evac.server.web.RequestBodies;
import io.javalin.http.HttpStatus;

public final class ScenarioController {

    private final ScenarioService service;

    public ScenarioController(ScenarioService service) {
        this.service = service;
    }

    public void list(Context ctx) {
        long buildingId = parseId(ctx, "bid");
        long userId = currentUserId(ctx);
        ctx.json(service.list(buildingId, userId));
    }

    public void get(Context ctx) {
        long buildingId = parseId(ctx, "bid");
        long scenarioId = parseId(ctx, "sid");
        long userId = currentUserId(ctx);
        ctx.json(service.get(buildingId, scenarioId, userId));
    }

    public void create(Context ctx) {
        long buildingId = parseId(ctx, "bid");
        long userId = currentUserId(ctx);
        ScenarioRequest req = RequestBodies.read(ctx, ScenarioRequest.class);
        ctx.status(HttpStatus.CREATED);
        ctx.json(service.create(buildingId, userId, req));
    }

    public void update(Context ctx) {
        long buildingId = parseId(ctx, "bid");
        long scenarioId = parseId(ctx, "sid");
        long userId = currentUserId(ctx);
        ScenarioRequest req = RequestBodies.read(ctx, ScenarioRequest.class);
        ctx.json(service.update(buildingId, scenarioId, userId, req));
    }

    public void delete(Context ctx) {
        long buildingId = parseId(ctx, "bid");
        long scenarioId = parseId(ctx, "sid");
        long userId = currentUserId(ctx);
        service.delete(buildingId, scenarioId, userId);
        ctx.status(HttpStatus.NO_CONTENT);
    }

    public void setDefault(Context ctx) {
        long buildingId = parseId(ctx, "bid");
        long scenarioId = parseId(ctx, "sid");
        long userId = currentUserId(ctx);
        service.setDefault(buildingId, scenarioId, userId);
        ctx.status(HttpStatus.NO_CONTENT);
    }

    private static long parseId(Context ctx, String param) {
        try {
            return Long.parseLong(ctx.pathParam(param));
        } catch (NumberFormatException e) {
            throw new NotFoundException("Объект не найден");
        }
    }

    private static long currentUserId(Context ctx) {
        UserDto user = AuthController.currentUser(ctx);
        if (user == null) {
            throw new IllegalStateException("Пользователь не аутентифицирован");
        }
        return user.id();
    }
}
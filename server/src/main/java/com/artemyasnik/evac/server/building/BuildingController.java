package com.artemyasnik.evac.server.building;

import com.artemyasnik.evac.server.auth.AuthController;
import com.artemyasnik.evac.server.dto.BuildingRequest;
import com.artemyasnik.evac.server.dto.UserDto;
import io.javalin.http.Context;
import com.artemyasnik.evac.server.web.RequestBodies;
import io.javalin.http.HttpStatus;

public final class BuildingController {

    private final BuildingService service;

    public BuildingController(BuildingService service) {
        this.service = service;
    }

    public void list(Context ctx) {
        long userId = currentUserId(ctx);
        ctx.json(service.list(userId));
    }

    public void create(Context ctx) {
        long userId = currentUserId(ctx);
        BuildingRequest req = RequestBodies.read(ctx, BuildingRequest.class);
        ctx.status(HttpStatus.CREATED);
        ctx.json(service.create(userId, req));
    }

    public void get(Context ctx) {
        long userId = currentUserId(ctx);
        long id = parseId(ctx);
        ctx.json(service.get(id, userId));
    }

    public void update(Context ctx) {
        long userId = currentUserId(ctx);
        long id = parseId(ctx);
        BuildingRequest req = RequestBodies.read(ctx, BuildingRequest.class);
        ctx.json(service.update(id, userId, req));
    }

    public void delete(Context ctx) {
        long userId = currentUserId(ctx);
        long id = parseId(ctx);
        service.delete(id, userId);
        ctx.status(HttpStatus.NO_CONTENT);
    }

    private static long parseId(Context ctx) {
        try {
            return Long.parseLong(ctx.pathParam("id"));
        } catch (NumberFormatException e) {
            throw new NotFoundException("Здание не найдено");
        }
    }

    private static long currentUserId(Context ctx) {
        UserDto user = AuthController.currentUser(ctx);
        if (user == null) {
            // Сюда не должны попасть — middleware requireAuth уже отработал.
            throw new IllegalStateException("Пользователь не аутентифицирован");
        }
        return user.id();
    }
}
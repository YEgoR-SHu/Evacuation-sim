package com.artemyasnik.evac.server.building;

import com.artemyasnik.evac.server.auth.AuthController;
import com.artemyasnik.evac.server.dto.FloorDto;
import com.artemyasnik.evac.server.dto.FloorRequest;
import com.artemyasnik.evac.server.dto.UserDto;
import io.javalin.http.Context;
import com.artemyasnik.evac.server.web.RequestBodies;
import io.javalin.http.HttpStatus;

public final class FloorController {

    private final FloorService service;

    public FloorController(FloorService service) {
        this.service = service;
    }

    public void list(Context ctx) {
        long buildingId = parseId(ctx, "bid");
        long userId = currentUserId(ctx);
        ctx.json(service.list(buildingId, userId));
    }

    public void get(Context ctx) {
        long buildingId = parseId(ctx, "bid");
        long floorId = parseId(ctx, "fid");
        long userId = currentUserId(ctx);
        ctx.json(service.get(buildingId, floorId, userId));
    }

    public void getSvg(Context ctx) {
        long buildingId = parseId(ctx, "bid");
        long floorId = parseId(ctx, "fid");
        long userId = currentUserId(ctx);
        FloorDto floor = service.get(buildingId, floorId, userId);
        ctx.contentType("image/svg+xml; charset=utf-8");
        ctx.result(floor.svg());
    }

    public void create(Context ctx) {
        long buildingId = parseId(ctx, "bid");
        long userId = currentUserId(ctx);
        FloorRequest req = RequestBodies.read(ctx, FloorRequest.class);
        ctx.status(HttpStatus.CREATED);
        ctx.json(service.create(buildingId, userId, req));
    }

    public void update(Context ctx) {
        long buildingId = parseId(ctx, "bid");
        long floorId = parseId(ctx, "fid");
        long userId = currentUserId(ctx);
        FloorRequest req = RequestBodies.read(ctx, FloorRequest.class);
        ctx.json(service.update(buildingId, floorId, userId, req));
    }

    public void delete(Context ctx) {
        long buildingId = parseId(ctx, "bid");
        long floorId = parseId(ctx, "fid");
        long userId = currentUserId(ctx);
        service.delete(buildingId, floorId, userId);
        ctx.status(HttpStatus.NO_CONTENT);
    }

    private static long parseId(Context ctx, String name) {
        try {
            return Long.parseLong(ctx.pathParam(name));
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
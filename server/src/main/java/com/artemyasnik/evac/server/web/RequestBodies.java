package com.artemyasnik.evac.server.web;

import io.javalin.http.Context;

/** JSON-объект обязателен: Javalin иначе преобразует JSON null в серверную ошибку. */
public final class RequestBodies {
    private RequestBodies() { }
    public static <T> T read(Context context, Class<T> type) {
        String body = context.body().trim();
        if (body.isEmpty() || body.equals("null"))
            throw new IllegalArgumentException("Тело запроса должно содержать JSON-объект");
        return context.bodyAsClass(type);
    }
}

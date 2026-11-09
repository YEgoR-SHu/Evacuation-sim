package com.artemyasnik.evac.server.auth;

import com.artemyasnik.evac.server.dto.ErrorDto;
import com.artemyasnik.evac.server.dto.LoginRequest;
import com.artemyasnik.evac.server.dto.RegisterRequest;
import com.artemyasnik.evac.server.dto.UserDto;
import io.javalin.http.Context;
import com.artemyasnik.evac.server.web.RequestBodies;
import io.javalin.http.Cookie;
import io.javalin.http.HttpStatus;
import io.javalin.http.SameSite;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;

public final class AuthController {

    public static final String COOKIE_NAME = "SESSION";
    private static final String ATTR_USER = "currentUser";

    private final AuthService auth;
    private final long sessionTtlHours;

    public AuthController(AuthService auth, long sessionTtlHours) {
        this.auth = auth;
        this.sessionTtlHours = sessionTtlHours;
    }

    public void register(Context ctx) {
        RegisterRequest req = RequestBodies.read(ctx, RegisterRequest.class);
        if (req == null) throw new IllegalArgumentException("Тело запроса обязательно");
        UserDto user = auth.register(req.username(), req.password());
        ctx.status(HttpStatus.CREATED);
        ctx.json(user);
    }

    public void login(Context ctx) {
        LoginRequest req = RequestBodies.read(ctx, LoginRequest.class);
        if (req == null) throw new IllegalArgumentException("Тело запроса обязательно");
        AuthService.LoginResult result = auth.login(req.username(), req.password());

        ctx.cookie(buildSessionCookie(result.token(),
                Duration.ofHours(sessionTtlHours)));

        ctx.json(result.user());
    }

    public void logout(Context ctx) {
        String token = ctx.cookie(COOKIE_NAME);
        auth.logout(token);
        ctx.cookie(buildSessionCookie("", Duration.ZERO));
        ctx.status(HttpStatus.NO_CONTENT);
    }

    public void me(Context ctx) {
        UserDto user = ctx.attribute(ATTR_USER);
        if (user == null) {
            ctx.status(HttpStatus.UNAUTHORIZED);
            ctx.json(new ErrorDto("unauthorized", "Требуется вход"));
            return;
        }
        ctx.json(user);
    }

    /**
     * Middleware: проверяет cookie SESSION, кладёт currentUser в attributes.
     * Не блокирует запрос — блокировка делается в {@link #requireAuth}.
     */
    public void attachUser(Context ctx) {
        String token = ctx.cookie(COOKIE_NAME);
        Optional<UserDto> user = auth.findByToken(token);
        user.ifPresent(u -> ctx.attribute(ATTR_USER, u));
    }

    public static UserDto currentUser(Context ctx) {
        return ctx.attribute(ATTR_USER);
    }

    /**
     * Middleware: пропускает только аутентифицированных.
     */
    public void requireAuth(Context ctx) {
        if (ctx.attribute(ATTR_USER) == null) {
            ctx.status(HttpStatus.UNAUTHORIZED);
            ctx.json(new ErrorDto("unauthorized", "Требуется вход"));
            // HttpResponseException принимает числовой HTTP-статус
            throw new io.javalin.http.HttpResponseException(401);
        }
    }

    private static Cookie buildSessionCookie(String value, Duration maxAge) {
        // В Javalin 6 конструктор Cookie(name, value), остальное — через сеттеры
        Cookie cookie = new Cookie(COOKIE_NAME, value);
        cookie.setPath("/");
        cookie.setMaxAge((int) maxAge.toSeconds());
        cookie.setHttpOnly(true);
        cookie.setSecure(false);           // в проде с HTTPS — true
        cookie.setSameSite(SameSite.STRICT);
        return cookie;
    }
}
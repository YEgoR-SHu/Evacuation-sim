package com.artemyasnik.evac.server.auth;

import com.artemyasnik.evac.server.dto.UserDto;
import org.mindrot.jbcrypt.BCrypt;

import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.Optional;

public final class AuthService {

    private static final int BCRYPT_ROUNDS = 10;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final UserRepository users;
    private final SessionRepository sessions;
    private final long sessionTtlHours;

    public AuthService(UserRepository users, SessionRepository sessions, long sessionTtlHours) {
        this.users = users;
        this.sessions = sessions;
        this.sessionTtlHours = sessionTtlHours;
    }

    /**
     * Создать пользователя. Возвращает DTO, но не создаёт сессию
     * (после регистрации клиент должен вызвать /login).
     */
    public UserDto register(String username, String password) {
        String u = normalizeUsername(username);
        String p = validatePassword(password);
        if (users.existsByUsername(u)) {
            throw new IllegalStateException("Пользователь '" + u + "' уже существует");
        }
        String hash = BCrypt.hashpw(p, BCrypt.gensalt(BCRYPT_ROUNDS));
        long id = users.create(u, hash);
        return new UserDto(id, u);
    }

    /**
     * Проверить пароль и создать сессию. Возвращает токен + пользователя.
     */
    public LoginResult login(String username, String password) {
        String u = normalizeUsername(username);
        if (password == null || password.isEmpty()
                || password.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 72)
            throw new IllegalArgumentException("Неверный логин или пароль");
        UserRepository.UserRow row = users.findByUsername(u)
                .orElseThrow(() -> new IllegalArgumentException("Неверный логин или пароль"));
        if (!BCrypt.checkpw(password, row.passwordHash())) {
            throw new IllegalArgumentException("Неверный логин или пароль");
        }

        String token = generateToken();
        Instant expiresAt = Instant.now().plus(sessionTtlHours, ChronoUnit.HOURS);
        sessions.create(token, row.id(), expiresAt);

        return new LoginResult(token, new UserDto(row.id(), row.username()), expiresAt);
    }

    public Optional<UserDto> findByToken(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        return sessions.findUserIdByValidToken(token).flatMap(users::findById);
    }

    public void logout(String token) {
        if (token != null && !token.isBlank()) {
            sessions.delete(token);
        }
    }

    public void cleanupExpiredSessions() {
        sessions.deleteExpired();
    }

    private static String normalizeUsername(String username) {
        if (username == null) {
            throw new IllegalArgumentException("Логин не указан");
        }
        String u = username.trim();
        if (u.length() < 3 || u.length() > 64) {
            throw new IllegalArgumentException("Логин должен быть от 3 до 64 символов");
        }
        if (!u.matches("[a-zA-Z0-9_.-]+")) {
            throw new IllegalArgumentException("Логин: только латиница, цифры, _, ., -");
        }
        return u;
    }

    private static String validatePassword(String password) {
        if (password == null || password.length() < 6) {
            throw new IllegalArgumentException("Пароль должен быть не короче 6 символов");
        }
        if (password.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 72) {
            throw new IllegalArgumentException("Пароль должен занимать не более 72 байт UTF-8");
        }
        return password;
    }

    private static String generateToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public record LoginResult(String token, UserDto user, Instant expiresAt) {
    }
}
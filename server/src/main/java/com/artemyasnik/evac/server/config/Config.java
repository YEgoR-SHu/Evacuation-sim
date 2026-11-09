package com.artemyasnik.evac.server.config;

import java.nio.file.Path;

/** Настройки локального сервера; учётные данные по умолчанию не создаются. */
public record Config(int port, Path dbPath, String adminUsername, String adminPassword,
                     long sessionTtlHours, String host) {
    public Config(int port, Path dbPath, String username, String password, long ttl) {
        this(port, dbPath, username, password, ttl, "127.0.0.1");
    }
    public Config {
        if (port < 0 || port > 65535) throw new IllegalArgumentException("PORT должен быть от 0 до 65535");
        if (dbPath == null) throw new IllegalArgumentException("DB_PATH обязателен");
        adminUsername = adminUsername == null ? "" : adminUsername.trim();
        adminPassword = adminPassword == null ? "" : adminPassword;
        if (adminUsername.isBlank() != adminPassword.isBlank())
            throw new IllegalArgumentException("ADMIN_USERNAME и ADMIN_PASSWORD задаются вместе");
        if (sessionTtlHours < 1 || sessionTtlHours > 720)
            throw new IllegalArgumentException("SESSION_TTL_HOURS должен быть от 1 до 720");
        if (host == null || host.isBlank()) throw new IllegalArgumentException("HOST обязателен");
    }
    public static Config fromEnv() {
        return new Config(integer("PORT", 8080), Path.of(env("DB_PATH", "./data/evac.db")),
                env("ADMIN_USERNAME", ""), env("ADMIN_PASSWORD", ""),
                integer("SESSION_TTL_HOURS", 168), env("HOST", "127.0.0.1"));
    }
    private static String env(String key, String fallback) {
        String value = System.getenv(key);
        return value == null || value.isBlank() ? fallback : value;
    }
    private static int integer(String key, int fallback) {
        String value = System.getenv(key);
        if (value == null || value.isBlank()) return fallback;
        try { return Integer.parseInt(value.trim()); }
        catch (NumberFormatException e) { throw new IllegalArgumentException(key + " должен быть целым числом"); }
    }
}

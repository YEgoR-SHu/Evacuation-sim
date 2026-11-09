package com.artemyasnik.evac.server.auth;

import com.artemyasnik.evac.server.config.Database;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Optional;

public final class SessionRepository {

    private final Database db;

    public SessionRepository(Database db) {
        this.db = db;
    }

    public void create(String token, long userId, Instant expiresAt) {
        String sql = "INSERT INTO sessions (token, user_id, expires_at) VALUES (?, ?, ?)";
        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, token);
            ps.setLong(2, userId);
            ps.setString(3, expiresAt.toString());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Не удалось создать сессию", e);
        }
    }

    /**
     * Возвращает пользователя, если сессия существует и не истекла.
     */
    public Optional<Long> findUserIdByValidToken(String token) {
        String sql = "SELECT user_id, expires_at FROM sessions WHERE token = ?";
        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, token);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                String expiresStr = rs.getString("expires_at");
                Instant expires = Instant.parse(expiresStr);
                if (expires.isBefore(Instant.now())) {
                    return Optional.empty();
                }
                return Optional.of(rs.getLong("user_id"));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Ошибка чтения сессии", e);
        }
    }

    public void delete(String token) {
        String sql = "DELETE FROM sessions WHERE token = ?";
        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, token);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Не удалось удалить сессию", e);
        }
    }

    public void deleteExpired() {
        String sql = "DELETE FROM sessions WHERE expires_at < ?";
        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, Instant.now().toString());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Не удалось удалить истёкшие сессии", e);
        }
    }
}
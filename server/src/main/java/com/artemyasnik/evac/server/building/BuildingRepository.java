package com.artemyasnik.evac.server.building;

import com.artemyasnik.evac.server.config.Database;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public final class BuildingRepository {

    private final Database db;

    public BuildingRepository(Database db) {
        this.db = db;
    }

    public long create(long userId, String name, String description) {
        String sql = "INSERT INTO buildings (user_id, name, description) VALUES (?, ?, ?)";
        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, userId);
            ps.setString(2, name);
            ps.setString(3, description);
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                if (rs.next()) {
                    return rs.getLong(1);
                }
                throw new SQLException("Не удалось получить id нового здания");
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Не удалось создать здание", e);
        }
    }

    /**
     * Список зданий пользователя (только краткая инфа).
     */
    public List<BuildingRow> findAllByUser(long userId) {
        String sql = """
                SELECT b.id, b.name, b.description, b.created_at, b.updated_at,
                       (SELECT COUNT(*) FROM floors f WHERE f.building_id = b.id)    AS floor_count,
                       (SELECT COUNT(*) FROM scenarios s WHERE s.building_id = b.id) AS scenario_count
                FROM buildings b
                WHERE b.user_id = ?
                ORDER BY b.updated_at DESC, b.id DESC
                """;
        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                List<BuildingRow> out = new ArrayList<>();
                while (rs.next()) {
                    out.add(readRow(rs));
                }
                return out;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Не удалось прочитать список зданий", e);
        }
    }

    /**
     * Одно здание по id, но только если оно принадлежит пользователю.
     * Иначе — empty (чтобы не палить существование чужих зданий).
     */
    public Optional<BuildingRow> findByIdAndUser(long id, long userId) {
        String sql = """
                SELECT b.id, b.name, b.description, b.created_at, b.updated_at,
                       (SELECT COUNT(*) FROM floors f WHERE f.building_id = b.id)    AS floor_count,
                       (SELECT COUNT(*) FROM scenarios s WHERE s.building_id = b.id) AS scenario_count
                FROM buildings b
                WHERE b.id = ? AND b.user_id = ?
                """;
        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, id);
            ps.setLong(2, userId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of(readRow(rs));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Не удалось прочитать здание", e);
        }
    }

    public boolean update(long id, long userId, String name, String description) {
        String sql = "UPDATE buildings SET name = ?, description = ?, updated_at = ? " +
                "WHERE id = ? AND user_id = ?";
        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, name);
            ps.setString(2, description);
            ps.setString(3, Instant.now().toString());
            ps.setLong(4, id);
            ps.setLong(5, userId);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            throw new IllegalStateException("Не удалось обновить здание", e);
        }
    }

    public boolean delete(long id, long userId) {
        String sql = "DELETE FROM buildings WHERE id = ? AND user_id = ?";
        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, id);
            ps.setLong(2, userId);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            throw new IllegalStateException("Не удалось удалить здание", e);
        }
    }

    private static BuildingRow readRow(ResultSet rs) throws SQLException {
        return new BuildingRow(
                rs.getLong("id"),
                rs.getString("name"),
                rs.getString("description"),
                rs.getInt("floor_count"),
                rs.getInt("scenario_count"),
                rs.getString("created_at"),
                rs.getString("updated_at")
        );
    }

    public record BuildingRow(
            long id,
            String name,
            String description,
            int floorCount,
            int scenarioCount,
            String createdAt,
            String updatedAt
    ) {
    }

    public void touchUpdatedAt(long buildingId) {
        String sql = "UPDATE buildings SET updated_at = ? WHERE id = ?";
        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, Instant.now().toString());
            ps.setLong(2, buildingId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Не удалось обновить время здания", e);
        }
    }
}
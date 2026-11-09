package com.artemyasnik.evac.server.building;

import com.artemyasnik.evac.server.config.Database;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public final class FloorRepository {

    private final Database db;

    public FloorRepository(Database db) {
        this.db = db;
    }

    public long create(long buildingId, String name, int level, String svg,
                       double vbX, double vbY, double vbW, double vbH) {
        String sql = """
                INSERT INTO floors (building_id, name, level, svg, vb_x, vb_y, vb_w, vb_h)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """;
        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, buildingId);
            ps.setString(2, name);
            ps.setInt(3, level);
            ps.setString(4, svg);
            ps.setDouble(5, vbX);
            ps.setDouble(6, vbY);
            ps.setDouble(7, vbW);
            ps.setDouble(8, vbH);
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                if (rs.next()) {
                    return rs.getLong(1);
                }
                throw new SQLException("Не удалось получить id нового этажа");
            }
        } catch (SQLException e) {
            if (e.getMessage() != null && e.getMessage().contains("UNIQUE")) {
                throw new IllegalStateException(
                        "На этом здании уже есть этаж с уровнем " + level);
            }
            throw new IllegalStateException("Не удалось создать этаж", e);
        }
    }

    public List<FloorRow> findAllByBuilding(long buildingId) {
        String sql = """
                SELECT id, building_id, name, level, svg, vb_x, vb_y, vb_w, vb_h
                FROM floors
                WHERE building_id = ?
                ORDER BY level ASC, id ASC
                """;
        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, buildingId);
            try (ResultSet rs = ps.executeQuery()) {
                List<FloorRow> out = new ArrayList<>();
                while (rs.next()) {
                    out.add(readRow(rs));
                }
                return out;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Не удалось прочитать этажи", e);
        }
    }

    public Optional<FloorRow> findByIdAndBuilding(long id, long buildingId) {
        String sql = """
                SELECT id, building_id, name, level, svg, vb_x, vb_y, vb_w, vb_h
                FROM floors
                WHERE id = ? AND building_id = ?
                """;
        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, id);
            ps.setLong(2, buildingId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of(readRow(rs));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Не удалось прочитать этаж", e);
        }
    }

    public boolean update(long id, long buildingId, String name, int level, String svg,
                          double vbX, double vbY, double vbW, double vbH) {
        String sql = """
                UPDATE floors
                SET name = ?, level = ?, svg = ?, vb_x = ?, vb_y = ?, vb_w = ?, vb_h = ?
                WHERE id = ? AND building_id = ?
                """;
        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, name);
            ps.setInt(2, level);
            ps.setString(3, svg);
            ps.setDouble(4, vbX);
            ps.setDouble(5, vbY);
            ps.setDouble(6, vbW);
            ps.setDouble(7, vbH);
            ps.setLong(8, id);
            ps.setLong(9, buildingId);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            if (e.getMessage() != null && e.getMessage().contains("UNIQUE")) {
                throw new IllegalStateException(
                        "На этом здании уже есть этаж с уровнем " + level);
            }
            throw new IllegalStateException("Не удалось обновить этаж", e);
        }
    }

    public boolean delete(long id, long buildingId) {
        String sql = "DELETE FROM floors WHERE id = ? AND building_id = ?";
        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, id);
            ps.setLong(2, buildingId);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            throw new IllegalStateException("Не удалось удалить этаж", e);
        }
    }

    private static FloorRow readRow(ResultSet rs) throws SQLException {
        return new FloorRow(
                rs.getLong("id"),
                rs.getLong("building_id"),
                rs.getString("name"),
                rs.getInt("level"),
                rs.getString("svg"),
                rs.getDouble("vb_x"),
                rs.getDouble("vb_y"),
                rs.getDouble("vb_w"),
                rs.getDouble("vb_h")
        );
    }

    public record FloorRow(
            long id,
            long buildingId,
            String name,
            int level,
            String svg,
            double vbX,
            double vbY,
            double vbW,
            double vbH
    ) {
    }
}
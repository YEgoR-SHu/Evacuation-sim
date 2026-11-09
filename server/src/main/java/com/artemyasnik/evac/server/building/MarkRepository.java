package com.artemyasnik.evac.server.building;

import com.artemyasnik.evac.server.config.Database;
import com.artemyasnik.evac.server.dto.MarkDto;
import com.artemyasnik.evac.server.dto.MarkRequest;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public final class MarkRepository {

    private final Database db;

    public MarkRepository(Database db) {
        this.db = db;
    }

    public long create(long scenarioId, MarkRequest req) {
        String sql = """
                INSERT INTO scenario_marks (scenario_id, floor_id, kind, x, y, width)
                VALUES (?, ?, ?, ?, ?, ?)
                """;
        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, scenarioId);
            ps.setLong(2, req.floorId());
            ps.setString(3, req.kind());
            ps.setDouble(4, req.x());
            ps.setDouble(5, req.y());
            if (req.width() != null) {
                ps.setDouble(6, req.width());
            } else {
                ps.setNull(6, Types.REAL);
            }
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                if (rs.next()) {
                    return rs.getLong(1);
                }
                throw new SQLException("Не удалось получить id новой метки");
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Не удалось создать метку", e);
        }
    }

    public List<MarkDto> findAllByScenario(long scenarioId) {
        String sql = """
                SELECT id, floor_id, kind, x, y, width
                FROM scenario_marks
                WHERE scenario_id = ?
                ORDER BY floor_id ASC, id ASC
                """;
        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, scenarioId);
            try (ResultSet rs = ps.executeQuery()) {
                List<MarkDto> out = new ArrayList<>();
                while (rs.next()) {
                    out.add(readRow(rs));
                }
                return out;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Не удалось прочитать метки", e);
        }
    }

    public Optional<MarkDto> findByIdAndScenario(long markId, long scenarioId) {
        String sql = """
                SELECT id, floor_id, kind, x, y, width
                FROM scenario_marks
                WHERE id = ? AND scenario_id = ?
                """;
        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, markId);
            ps.setLong(2, scenarioId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of(readRow(rs));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Не удалось прочитать метку", e);
        }
    }

    public boolean update(long markId, long scenarioId, MarkRequest req) {
        String sql = """
                UPDATE scenario_marks
                SET floor_id = ?, kind = ?, x = ?, y = ?, width = ?
                WHERE id = ? AND scenario_id = ?
                """;
        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, req.floorId());
            ps.setString(2, req.kind());
            ps.setDouble(3, req.x());
            ps.setDouble(4, req.y());
            if (req.width() != null) {
                ps.setDouble(5, req.width());
            } else {
                ps.setNull(5, Types.REAL);
            }
            ps.setLong(6, markId);
            ps.setLong(7, scenarioId);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            throw new IllegalStateException("Не удалось обновить метку", e);
        }
    }

    public boolean delete(long markId, long scenarioId) {
        String sql = "DELETE FROM scenario_marks WHERE id = ? AND scenario_id = ?";
        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, markId);
            ps.setLong(2, scenarioId);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            throw new IllegalStateException("Не удалось удалить метку", e);
        }
    }

    public void deleteAllByScenario(long scenarioId) {
        String sql = "DELETE FROM scenario_marks WHERE scenario_id = ?";
        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, scenarioId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Не удалось удалить метки", e);
        }
    }

    private static MarkDto readRow(ResultSet rs) throws SQLException {
        double widthRaw = rs.getDouble("width");
        Double width = rs.wasNull() ? null : widthRaw;
        return new MarkDto(
                rs.getLong("id"),
                rs.getLong("floor_id"),
                rs.getString("kind"),
                rs.getDouble("x"),
                rs.getDouble("y"),
                width
        );
    }
}
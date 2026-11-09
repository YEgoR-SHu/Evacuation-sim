package com.artemyasnik.evac.server.building;

import com.artemyasnik.evac.server.config.Database;
import com.artemyasnik.evac.server.dto.FireDto;
import com.artemyasnik.evac.server.dto.ParamsDto;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public final class ScenarioRepository {

    private final Database db;
    private final ObjectMapper mapper;

    public ScenarioRepository(Database db, ObjectMapper mapper) {
        this.db = db;
        this.mapper = mapper;
    }

    public long create(long buildingId, String name, boolean isDefault,
                       ParamsDto params, FireDto fire) {
        String sql = """
                INSERT INTO scenarios (building_id, name, is_default, params_json, fire_json)
                VALUES (?, ?, ?, ?, ?)
                """;
        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, buildingId);
            ps.setString(2, name);
            ps.setInt(3, isDefault ? 1 : 0);
            ps.setString(4, writeJson(params));
            ps.setString(5, writeJson(fire));
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                if (rs.next()) {
                    return rs.getLong(1);
                }
                throw new SQLException("Не удалось получить id нового сценария");
            }
        } catch (SQLException e) {
            if (e.getMessage() != null && e.getMessage().contains("UNIQUE")) {
                throw new IllegalStateException(
                        "На этом здании уже есть сценарий по умолчанию");
            }
            throw new IllegalStateException("Не удалось создать сценарий", e);
        }
    }

    public List<ScenarioRow> findAllByBuilding(long buildingId) {
        String sql = """
                SELECT id, building_id, name, is_default, params_json, fire_json,
                       created_at, updated_at
                FROM scenarios
                WHERE building_id = ?
                ORDER BY is_default DESC, id ASC
                """;
        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, buildingId);
            try (ResultSet rs = ps.executeQuery()) {
                List<ScenarioRow> out = new ArrayList<>();
                while (rs.next()) {
                    out.add(readRow(rs));
                }
                return out;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Не удалось прочитать сценарии", e);
        }
    }

    public Optional<ScenarioRow> findByIdAndBuilding(long id, long buildingId) {
        String sql = """
                SELECT id, building_id, name, is_default, params_json, fire_json,
                       created_at, updated_at
                FROM scenarios
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
            throw new IllegalStateException("Не удалось прочитать сценарий", e);
        }
    }

    public boolean update(long id, long buildingId, String name,
                          ParamsDto params, FireDto fire) {
        String sql = """
                UPDATE scenarios
                SET name = ?, params_json = ?, fire_json = ?, updated_at = ?
                WHERE id = ? AND building_id = ?
                """;
        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, name);
            ps.setString(2, writeJson(params));
            ps.setString(3, writeJson(fire));
            ps.setString(4, Instant.now().toString());
            ps.setLong(5, id);
            ps.setLong(6, buildingId);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            throw new IllegalStateException("Не удалось обновить сценарий", e);
        }
    }

    public boolean delete(long id, long buildingId) {
        String sql = "DELETE FROM scenarios WHERE id = ? AND building_id = ?";
        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, id);
            ps.setLong(2, buildingId);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            throw new IllegalStateException("Не удалось удалить сценарий", e);
        }
    }

    /**
     * Снимает is_default со всех сценариев здания и ставит на указанный.
     * Атомарно: либо оба UPDATE, либо ни одного.
     */
    public void setDefault(long scenarioId, long buildingId) {
        String clearSql = "UPDATE scenarios SET is_default = 0 WHERE building_id = ?";
        String setSql = "UPDATE scenarios SET is_default = 1 WHERE id = ? AND building_id = ?";
        try (Connection c = db.getConnection()) {
            boolean prevAuto = c.getAutoCommit();
            c.setAutoCommit(false);
            try (PreparedStatement ps1 = c.prepareStatement(clearSql);
                 PreparedStatement ps2 = c.prepareStatement(setSql)) {
                ps1.setLong(1, buildingId);
                ps1.executeUpdate();

                ps2.setLong(1, scenarioId);
                ps2.setLong(2, buildingId);
                int updated = ps2.executeUpdate();
                if (updated == 0) {
                    c.rollback();
                    throw new NotFoundException("Сценарий не найден");
                }
                c.commit();
            } catch (SQLException e) {
                c.rollback();
                throw e;
            } finally {
                c.setAutoCommit(prevAuto);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Не удалось установить сценарий по умолчанию", e);
        }
    }

    private String writeJson(Object o) {
        try {
            return mapper.writeValueAsString(o);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Не удалось сериализовать JSON", e);
        }
    }

    private ScenarioRow readRow(ResultSet rs) throws SQLException {
        ParamsDto params;
        FireDto fire;
        try {
            String paramsStr = rs.getString("params_json");
            params = (paramsStr == null || paramsStr.isBlank())
                    ? ParamsDto.defaultParams()
                    : mapper.readValue(paramsStr, ParamsDto.class);

            String fireStr = rs.getString("fire_json");
            fire = (fireStr == null || fireStr.isBlank() || fireStr.equals("null"))
                    ? null
                    : mapper.readValue(fireStr, FireDto.class);
        } catch (JsonProcessingException e) {
            throw new SQLException("Ошибка разбора JSON сценария", e);
        }
        return new ScenarioRow(
                rs.getLong("id"),
                rs.getLong("building_id"),
                rs.getString("name"),
                rs.getInt("is_default") == 1,
                params,
                fire,
                rs.getString("created_at"),
                rs.getString("updated_at")
        );
    }

    public record ScenarioRow(
            long id,
            long buildingId,
            String name,
            boolean isDefault,
            ParamsDto params,
            FireDto fire,
            String createdAt,
            String updatedAt
    ) {
    }
}
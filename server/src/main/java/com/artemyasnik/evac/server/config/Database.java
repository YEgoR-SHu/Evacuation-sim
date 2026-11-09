package com.artemyasnik.evac.server.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Пул соединений на SQLite (HikariCP).
 * <p>
 * SQLite — встраиваемая БД, один файл. HikariCP всё равно полезен:
 * даёт единый интерфейс {@link #getConnection()}, ограничивает число
 * одновременных соединений, умеет health-check.
 * <p>
 * Особенности SQLite, которые нужно включить явно:
 * <ul>
 *     <li>{@code journal_mode=WAL} — параллельное чтение при записи;</li>
 *     <li>{@code foreign_keys=ON} — SQLite по умолчанию FK не проверяет;</li>
 *     <li>{@code busy_timeout=5000} — не падать сразу при блокировке, подождать 5 с.</li>
 * </ul>
 */
public final class Database implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(Database.class);

    private final HikariDataSource dataSource;

    public Database(Path dbPath) {
        // Создаём родительскую папку, если её нет (./data/evac.db → ./data/).
        Path parent = dbPath.toAbsolutePath().getParent();
        if (parent != null) {
            try {
                Files.createDirectories(parent);
            } catch (IOException e) {
                throw new IllegalStateException("Не удалось создать папку для БД: " + parent, e);
            }
        }

        HikariConfig cfg = new HikariConfig();
        cfg.setJdbcUrl("jdbc:sqlite:" + dbPath.toAbsolutePath());
        cfg.setDriverClassName("org.sqlite.JDBC");
        cfg.setPoolName("evac-sqlite");
        cfg.setMaximumPoolSize(10);
        cfg.setMinimumIdle(1);
        cfg.setConnectionTimeout(10_000);
        cfg.setIdleTimeout(60_000);
        cfg.setMaxLifetime(30 * 60_000);

        // Эти PRAGMA-настройки применяются на каждое соединение из пула.
        // SQLite JDBC выполняет каждую настройку для каждого нового соединения.
        // Несколько PRAGMA в connectionInitSql могут исполниться только частично.
        cfg.addDataSourceProperty("foreign_keys", "true");
        cfg.addDataSourceProperty("journal_mode", "WAL");
        cfg.addDataSourceProperty("busy_timeout", "5000");

        this.dataSource = new HikariDataSource(cfg);
        log.info("SQLite pool создан: {}", dbPath.toAbsolutePath());
    }

    public Connection getConnection() throws SQLException {
        return dataSource.getConnection();
    }

    public HikariDataSource getDataSource() {
        return dataSource;
    }

    /**
     * Простая проверка «БД жива»: открыть соединение и выполнить {@code SELECT 1}.
     */
    public boolean ping() {
        try (Connection c = getConnection(); Statement s = c.createStatement()) {
            s.executeQuery("SELECT 1").close();
            return true;
        } catch (SQLException e) {
            log.error("SQLite ping failed", e);
            return false;
        }
    }

    /**
     * Количество таблиц, кроме служебной flyway_schema_history.
     */
    public int countUserTables() {
        try (Connection c = getConnection();
             Statement s = c.createStatement();
             var rs = s.executeQuery("""
                     SELECT COUNT(*) FROM sqlite_master
                     WHERE type='table'
                       AND name NOT LIKE 'sqlite_%'
                       AND name <> 'flyway_schema_history'
                     """)) {
            rs.next();
            return rs.getInt(1);
        } catch (SQLException e) {
            log.error("Не удалось посчитать таблицы", e);
            return -1;
        }
    }

    @Override
    public void close() {
        if (dataSource != null && !dataSource.isClosed()) {
            dataSource.close();
            log.info("SQLite pool закрыт");
        }
    }
}
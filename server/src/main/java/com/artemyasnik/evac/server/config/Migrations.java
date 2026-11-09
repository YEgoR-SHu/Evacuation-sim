package com.artemyasnik.evac.server.config;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;

/**
 * Запуск миграций Flyway.
 * <p>
 * SQL-файлы лежат в {@code src/main/resources/db/migration/}, имя —
 * {@code V<N>__<описание>.sql}. Flyway при старте читает их, применяет
 * недостающие, пишет историю в таблицу {@code flyway_schema_history}.
 */
public final class Migrations {

    private static final Logger log = LoggerFactory.getLogger(Migrations.class);

    private Migrations() {
    }

    public static void run(DataSource dataSource) {
        Flyway flyway = Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .baselineOnMigrate(false)
                .validateOnMigrate(true)
                .load();

        var result = flyway.migrate();

        // Реальную текущую версию берём из info(), а не из result.targetSchemaVersion:
        // targetSchemaVersion == null, когда мигрировать было нечего.
        MigrationInfo current = flyway.info().current();
        String currentVersion = (current == null || current.getVersion() == null)
                ? "нет"
                : current.getVersion().getVersion();

        if (result.migrationsExecuted > 0) {
            log.info("Flyway применил {} миграций (текущая версия: {})",
                    result.migrationsExecuted, currentVersion);
        } else {
            log.info("Flyway: схема актуальна (текущая версия: {})", currentVersion);
        }
    }
}
package com.artemyasnik.evac.server.web;

import io.javalin.config.JavalinConfig;
import io.javalin.http.staticfiles.Location;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Раздача статики фронта из classpath:static/.
 * <p>
 * Статика настраивается через {@link JavalinConfig} при создании
 * приложения ({@code Javalin.create(cfg -> ...)}), а не через {@code app.staticFiles}.
 * <p>
 * Если папки /static нет в classpath (dev-режим без сборки фронта) — не падаем,
 * а просто пропускаем регистрацию. В проде папка всегда есть (её копирует Dockerfile).
 */
public final class StaticFiles {

    private static final Logger log = LoggerFactory.getLogger(StaticFiles.class);

    private StaticFiles() {
    }

    /**
     * Регистрирует раздачу classpath:static/, если папка существует.
     * Вызывать из {@code Javalin.create(...)}.
     */
    public static void register(JavalinConfig cfg) {
        if (StaticFiles.class.getResource("/static") == null) {
            log.warn("Папка /static не найдена в classpath — статика не подключена. " +
                    "Это нормально для dev-режима без сборки фронта.");
            return;
        }

        cfg.staticFiles.add(staticFileConfig -> {
            staticFileConfig.hostedPath = "/";
            staticFileConfig.directory = "/static";
            staticFileConfig.location = Location.CLASSPATH;
        });

        log.info("Статика подключена: classpath:/static");
    }
}
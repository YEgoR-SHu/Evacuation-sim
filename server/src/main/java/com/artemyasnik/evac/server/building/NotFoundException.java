package com.artemyasnik.evac.server.building;

/**
 * «Не найдено или не принадлежит пользователю». Обработчик превращает в 404.
 */
public class NotFoundException extends RuntimeException {
    public NotFoundException(String message) {
        super(message);
    }
}
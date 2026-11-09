package com.artemyasnik.evac.server.building;

import com.artemyasnik.evac.server.dto.FloorDto;
import com.artemyasnik.evac.server.dto.FloorRequest;
import com.artemyasnik.evac.server.parser.SvgSafety;

import java.util.ArrayList;
import java.util.List;

public final class FloorService {

    private final FloorRepository floors;
    private final BuildingRepository buildings;

    public FloorService(FloorRepository floors, BuildingRepository buildings) {
        this.floors = floors;
        this.buildings = buildings;
    }

    public FloorDto create(long buildingId, long userId, FloorRequest req) {
        requireBuildingOwned(buildingId, userId);
        if (req == null) throw new IllegalArgumentException("Тело запроса обязательно");
        String name = validateName(req.name());
        int level = validateLevel(req.level());
        String svg = validateSvg(req.svg());
        double[] vb = validateVb(req.vb());

        long id = floors.create(buildingId, name, level, svg, vb[0], vb[1], vb[2], vb[3]);
        FloorRepository.FloorRow row = floors.findByIdAndBuilding(id, buildingId)
                .orElseThrow(() -> new IllegalStateException("Только что созданный этаж не найден"));
        touchBuilding(buildingId);
        return toDto(row);
    }

    public List<FloorDto> list(long buildingId, long userId) {
        requireBuildingOwned(buildingId, userId);
        List<FloorDto> out = new ArrayList<>();
        for (FloorRepository.FloorRow row : floors.findAllByBuilding(buildingId)) {
            out.add(toDto(row));
        }
        return out;
    }

    public FloorDto get(long buildingId, long floorId, long userId) {
        requireBuildingOwned(buildingId, userId);
        FloorRepository.FloorRow row = floors.findByIdAndBuilding(floorId, buildingId)
                .orElseThrow(() -> new NotFoundException("Этаж не найден"));
        return toDto(row);
    }

    public FloorDto update(long buildingId, long floorId, long userId, FloorRequest req) {
        requireBuildingOwned(buildingId, userId);
        if (req == null) throw new IllegalArgumentException("Тело запроса обязательно");
        String name = validateName(req.name());
        int level = validateLevel(req.level());
        String svg = validateSvg(req.svg());
        double[] vb = validateVb(req.vb());

        if (!floors.update(floorId, buildingId, name, level, svg, vb[0], vb[1], vb[2], vb[3])) {
            throw new NotFoundException("Этаж не найден");
        }
        FloorRepository.FloorRow row = floors.findByIdAndBuilding(floorId, buildingId)
                .orElseThrow(() -> new NotFoundException("Этаж не найден"));
        touchBuilding(buildingId);
        return toDto(row);
    }

    public void delete(long buildingId, long floorId, long userId) {
        requireBuildingOwned(buildingId, userId);
        if (!floors.delete(floorId, buildingId)) {
            throw new NotFoundException("Этаж не найден");
        }
        touchBuilding(buildingId);
    }

    /**
     * Проверка владения зданием — иначе 404, чтобы не палить чужие id.
     */
    private void requireBuildingOwned(long buildingId, long userId) {
        buildings.findByIdAndUser(buildingId, userId)
                .orElseThrow(() -> new NotFoundException("Здание не найдено"));
    }

    /**
     * Обновление updated_at здания — чтобы список сортировался по свежести.
     */
    private void touchBuilding(long buildingId) {
        // Найдём текущее имя/описание, чтобы не перезаписать их пустотой.
        // Проще: сделать отдельный UPDATE только updated_at.
        // Пока используем findByIdAndUser? Нет, он требует userId. Сделаем отдельный метод.
        buildings.touchUpdatedAt(buildingId);
    }

    private static String validateName(String name) {
        if (name == null) {
            throw new IllegalArgumentException("Название этажа обязательно");
        }
        String n = name.trim();
        if (n.isEmpty()) {
            throw new IllegalArgumentException("Название этажа не может быть пустым");
        }
        if (n.length() > 200) {
            throw new IllegalArgumentException("Название этажа длиннее 200 символов");
        }
        return n;
    }

    private static int validateLevel(Integer level) {
        if (level == null) {
            throw new IllegalArgumentException("Уровень этажа обязателен");
        }
        if (level < -100 || level > 200) {
            throw new IllegalArgumentException("Уровень этажа должен быть в диапазоне [-100, 200]");
        }
        return level;
    }

    private static String validateSvg(String svg) {
        return SvgSafety.sanitize(svg);
    }

    private static double[] validateVb(double[] vb) {
        if (vb == null || vb.length != 4) {
            throw new IllegalArgumentException("viewBox должен содержать 4 числа: [x, y, w, h]");
        }
        for (double value : vb) {
            if (!Double.isFinite(value) || Math.abs(value) > 1_000_000)
                throw new IllegalArgumentException("viewBox должен содержать конечные числа в пределах ±1000000");
        }
        if (vb[2] <= 0 || vb[3] <= 0 || !Double.isFinite(vb[0] + vb[2]) || !Double.isFinite(vb[1] + vb[3])) {
            throw new IllegalArgumentException("viewBox: ширина и высота должны быть > 0");
        }
        return vb;
    }

    private static FloorDto toDto(FloorRepository.FloorRow row) {
        return new FloorDto(
                row.id(),
                row.name(),
                row.level(),
                SvgSafety.sanitize(row.svg()),
                new double[]{row.vbX(), row.vbY(), row.vbW(), row.vbH()}
        );
    }
}
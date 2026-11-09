package com.artemyasnik.evac.server.building;

import com.artemyasnik.evac.server.dto.FireDto;
import com.artemyasnik.evac.server.dto.ParamsDto;
import com.artemyasnik.evac.server.dto.ScenarioDto;
import com.artemyasnik.evac.server.dto.ScenarioFullDto;
import com.artemyasnik.evac.server.dto.ScenarioRequest;
import com.artemyasnik.evac.server.dto.ScenarioSummaryDto;

import java.util.ArrayList;
import java.util.List;

public final class ScenarioService {

    private final ScenarioRepository repo;
    private final BuildingRepository buildings;
    private final FloorRepository floors;
    private final MarkRepository marks;

    public ScenarioService(ScenarioRepository repo,
                           BuildingRepository buildings,
                           FloorRepository floors,
                           MarkRepository marks) {
        this.repo = repo;
        this.buildings = buildings;
        this.floors = floors;
        this.marks = marks;
    }

    public ScenarioSummaryDto create(long buildingId, long userId, ScenarioRequest req) {
        requireBuilding(buildingId, userId);
        if (req == null) throw new IllegalArgumentException("Тело запроса обязательно");
        String name = validateName(req.name());
        ParamsDto params = validateParams(req.params() == null ? ParamsDto.defaultParams() : req.params().withDefaults());
        FireDto fire = validateFire(req.fire(), buildingId);

        boolean isFirst = repo.findAllByBuilding(buildingId).isEmpty();
        long id = repo.create(buildingId, name, isFirst, params, fire);
        buildings.touchUpdatedAt(buildingId);

        ScenarioRepository.ScenarioRow row = repo.findByIdAndBuilding(id, buildingId)
                .orElseThrow(() -> new IllegalStateException("Только что созданный сценарий не найден"));
        return toSummary(row);
    }

    public List<ScenarioSummaryDto> list(long buildingId, long userId) {
        requireBuilding(buildingId, userId);
        List<ScenarioSummaryDto> out = new ArrayList<>();
        for (ScenarioRepository.ScenarioRow row : repo.findAllByBuilding(buildingId)) {
            out.add(toSummary(row));
        }
        return out;
    }

    public ScenarioFullDto get(long buildingId, long scenarioId, long userId) {
        requireBuilding(buildingId, userId);
        ScenarioRepository.ScenarioRow row = repo.findByIdAndBuilding(scenarioId, buildingId)
                .orElseThrow(() -> new NotFoundException("Сценарий не найден"));
        return toFullDto(row);
    }

    public ScenarioFullDto update(long buildingId, long scenarioId, long userId, ScenarioRequest req) {
        requireBuilding(buildingId, userId);
        if (req == null) throw new IllegalArgumentException("Тело запроса обязательно");
        String name = validateName(req.name());
        ParamsDto params = validateParams(req.params() == null ? ParamsDto.defaultParams() : req.params().withDefaults());
        FireDto fire = validateFire(req.fire(), buildingId);

        if (!repo.update(scenarioId, buildingId, name, params, fire)) {
            throw new NotFoundException("Сценарий не найден");
        }
        buildings.touchUpdatedAt(buildingId);

        ScenarioRepository.ScenarioRow row = repo.findByIdAndBuilding(scenarioId, buildingId)
                .orElseThrow(() -> new NotFoundException("Сценарий не найден"));
        return toFullDto(row);
    }

    public void delete(long buildingId, long scenarioId, long userId) {
        requireBuilding(buildingId, userId);

        List<ScenarioRepository.ScenarioRow> all = repo.findAllByBuilding(buildingId);
        ScenarioRepository.ScenarioRow target = all.stream()
                .filter(s -> s.id() == scenarioId)
                .findFirst()
                .orElseThrow(() -> new NotFoundException("Сценарий не найден"));

        if (all.size() == 1) {
            throw new IllegalStateException("Нельзя удалить единственный сценарий здания");
        }

        if (!repo.delete(scenarioId, buildingId)) {
            throw new NotFoundException("Сценарий не найден");
        }

        if (target.isDefault()) {
            List<ScenarioRepository.ScenarioRow> remaining = repo.findAllByBuilding(buildingId);
            if (!remaining.isEmpty()) {
                repo.setDefault(remaining.get(0).id(), buildingId);
            }
        }
        buildings.touchUpdatedAt(buildingId);
    }

    public void setDefault(long buildingId, long scenarioId, long userId) {
        requireBuilding(buildingId, userId);
        repo.findByIdAndBuilding(scenarioId, buildingId)
                .orElseThrow(() -> new NotFoundException("Сценарий не найден"));
        repo.setDefault(scenarioId, buildingId);
        buildings.touchUpdatedAt(buildingId);
    }

    private void requireBuilding(long buildingId, long userId) {
        buildings.findByIdAndUser(buildingId, userId)
                .orElseThrow(() -> new NotFoundException("Здание не найдено"));
    }

    private FireDto validateFire(FireDto fire, long buildingId) {
        if (fire == null) {
            return null;
        }
        if (fire.floorId() == null) {
            throw new IllegalArgumentException("У очага пожара должен быть floorId");
        }
        if (fire.x() == null || fire.y() == null || !Double.isFinite(fire.x()) || !Double.isFinite(fire.y())) {
            throw new IllegalArgumentException("У очага пожара должны быть конечные координаты x и y");
        }
        FloorRepository.FloorRow floor = floors.findByIdAndBuilding(fire.floorId(), buildingId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Этаж с id=" + fire.floorId() + " не найден в этом здании"));
        if (fire.x() < floor.vbX() || fire.x() > floor.vbX() + floor.vbW()
                || fire.y() < floor.vbY() || fire.y() > floor.vbY() + floor.vbH())
            throw new IllegalArgumentException("Очаг должен находиться в пределах плана этажа");
        return fire;
    }

    public static ParamsDto validateParams(ParamsDto params) {
        if (params.people() == null || params.people() < 0 || params.people() > 10_000)
            throw new IllegalArgumentException("Количество людей должно быть от 0 до 10000");
        range(params.speed(), 0.05, 10, "Скорость движения");
        range(params.scale(), 0.0001, 1_000_000, "Масштаб");
        range(params.stairTime(), 0.01, 3600, "Время лестницы");
        range(params.exitFlow(), 0.001, 1000, "Пропускная способность выхода");
        range(params.stairFlow(), 0.001, 1000, "Пропускная способность лестницы");
        range(params.fireRate(), 0, 100, "Скорость огня");
        return params;
    }

    private static void range(Double value, double min, double max, String label) {
        if (value == null || !Double.isFinite(value) || value < min || value > max)
            throw new IllegalArgumentException(label + ": допустимый диапазон от " + min + " до " + max);
    }

    private static String validateName(String name) {
        if (name == null) {
            throw new IllegalArgumentException("Название сценария обязательно");
        }
        String n = name.trim();
        if (n.isEmpty()) {
            throw new IllegalArgumentException("Название сценария не может быть пустым");
        }
        if (n.length() > 200) {
            throw new IllegalArgumentException("Название сценария длиннее 200 символов");
        }
        return n;
    }

    private static ScenarioSummaryDto toSummary(ScenarioRepository.ScenarioRow row) {
        return new ScenarioSummaryDto(
                row.id(),
                row.name(),
                row.isDefault(),
                row.createdAt(),
                row.updatedAt()
        );
    }

    private ScenarioFullDto toFullDto(ScenarioRepository.ScenarioRow row) {
        return new ScenarioFullDto(
                row.id(),
                row.name(),
                row.isDefault(),
                row.params(),
                row.fire(),
                marks.findAllByScenario(row.id()),
                row.createdAt(),
                row.updatedAt()
        );
    }
}
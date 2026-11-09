package com.artemyasnik.evac.server.building;

import com.artemyasnik.evac.server.dto.BuildingDto;
import com.artemyasnik.evac.server.dto.BuildingRequest;
import com.artemyasnik.evac.server.dto.BuildingSummaryDto;
import com.artemyasnik.evac.server.dto.FloorDto;
import com.artemyasnik.evac.server.dto.ScenarioSummaryDto;
import com.artemyasnik.evac.server.parser.SvgSafety;

import java.util.ArrayList;
import java.util.List;

public final class BuildingService {

    private final BuildingRepository repo;
    private final FloorRepository floorRepo;
    private final ScenarioRepository scenarioRepo;

    public BuildingService(BuildingRepository repo,
                           FloorRepository floorRepo,
                           ScenarioRepository scenarioRepo) {
        this.repo = repo;
        this.floorRepo = floorRepo;
        this.scenarioRepo = scenarioRepo;
    }

    public BuildingSummaryDto create(long userId, BuildingRequest req) {
        if (req == null) throw new IllegalArgumentException("Тело запроса обязательно");
        String name = validateName(req.name());
        String desc = normalizeDescription(req.description());
        long id = repo.create(userId, name, desc);
        BuildingRepository.BuildingRow row = repo.findByIdAndUser(id, userId)
                .orElseThrow(() -> new IllegalStateException("Только что созданное здание не найдено"));
        return toSummary(row);
    }

    public List<BuildingSummaryDto> list(long userId) {
        List<BuildingSummaryDto> out = new ArrayList<>();
        for (BuildingRepository.BuildingRow row : repo.findAllByUser(userId)) {
            out.add(toSummary(row));
        }
        return out;
    }

    public BuildingDto get(long id, long userId) {
        BuildingRepository.BuildingRow row = repo.findByIdAndUser(id, userId)
                .orElseThrow(() -> new NotFoundException("Здание не найдено"));

        List<FloorDto> floors = new ArrayList<>();
        for (FloorRepository.FloorRow fr : floorRepo.findAllByBuilding(id)) {
            floors.add(new FloorDto(
                    fr.id(), fr.name(), fr.level(), SvgSafety.sanitize(fr.svg()),
                    new double[]{fr.vbX(), fr.vbY(), fr.vbW(), fr.vbH()}
            ));
        }

        List<ScenarioSummaryDto> scenarios = new ArrayList<>();
        for (ScenarioRepository.ScenarioRow sr : scenarioRepo.findAllByBuilding(id)) {
            scenarios.add(new ScenarioSummaryDto(
                    sr.id(), sr.name(), sr.isDefault(), sr.createdAt(), sr.updatedAt()
            ));
        }

        return new BuildingDto(
                row.id(),
                row.name(),
                row.description(),
                floors,
                scenarios,
                row.createdAt(),
                row.updatedAt()
        );
    }

    public BuildingSummaryDto update(long id, long userId, BuildingRequest req) {
        if (req == null) throw new IllegalArgumentException("Тело запроса обязательно");
        String name = validateName(req.name());
        String desc = normalizeDescription(req.description());
        if (!repo.update(id, userId, name, desc)) {
            throw new NotFoundException("Здание не найдено");
        }
        BuildingRepository.BuildingRow row = repo.findByIdAndUser(id, userId)
                .orElseThrow(() -> new NotFoundException("Здание не найдено"));
        return toSummary(row);
    }

    public void delete(long id, long userId) {
        if (!repo.delete(id, userId)) {
            throw new NotFoundException("Здание не найдено");
        }
    }

    private static String validateName(String name) {
        if (name == null) {
            throw new IllegalArgumentException("Название здания обязательно");
        }
        String n = name.trim();
        if (n.isEmpty()) {
            throw new IllegalArgumentException("Название здания не может быть пустым");
        }
        if (n.length() > 200) {
            throw new IllegalArgumentException("Название здания длиннее 200 символов");
        }
        return n;
    }

    private static String normalizeDescription(String desc) {
        if (desc == null) {
            return "";
        }
        String d = desc.trim();
        if (d.length() > 2000) {
            throw new IllegalArgumentException("Описание длиннее 2000 символов");
        }
        return d;
    }

    private static BuildingSummaryDto toSummary(BuildingRepository.BuildingRow row) {
        return new BuildingSummaryDto(
                row.id(),
                row.name(),
                row.description(),
                row.floorCount(),
                row.scenarioCount(),
                row.createdAt(),
                row.updatedAt()
        );
    }
}
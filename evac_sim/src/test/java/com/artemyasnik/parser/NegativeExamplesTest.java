package com.artemyasnik.parser;

import org.junit.jupiter.api.Test;

import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.*;

class NegativeExamplesTest {

    @Test
    void singleIdConnectorIsRejected() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> {
            try (InputStream is = getClass().getResourceAsStream("/examples/example_negative.svg")) {
                assertNotNull(is);
                new SvgFloorPlanParser().parse(is);
            }
        });
        assertTrue(ex.getMessage().contains("data-connects"),
                "Сообщение должно упоминать data-connects, получено: " + ex.getMessage());
    }

    @Test
    void missingDataConnectsIsRejected() {
        // генерируем SVG прямо в памяти — не нужен отдельный файл
        String svg = """
                <?xml version="1.0" encoding="UTF-8"?>
                <svg xmlns="http://www.w3.org/2000/svg"
                     width="200" height="200" viewBox="0 0 200 200"
                     data-scale="0.02" data-floor="1">
                    <rect x="10" y="10" width="80" height="80"
                          data-type="room" data-id="R1" data-capacity="2"/>
                    <line x1="100" y1="50" x2="150" y2="50"
                          data-type="door" data-id="D1"
                          stroke="red"/>
                </svg>
                """;
        assertThrows(IllegalArgumentException.class, () ->
                new SvgFloorPlanParser().parse(new java.io.ByteArrayInputStream(svg.getBytes())));
    }
}
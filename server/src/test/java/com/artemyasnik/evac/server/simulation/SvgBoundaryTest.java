package com.artemyasnik.evac.server.simulation;

import com.artemyasnik.evac.server.dto.FloorDto;
import com.artemyasnik.evac.server.parser.SvgSafety;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SvgBoundaryTest {
    @Test void allRenderingPathsKeepGeometryButRemoveActiveAndExternalContent() {
        String safe = SvgSafety.sanitize("""
                <?xml-stylesheet href="https://outside.invalid/style.css"?>
                <svg xmlns="http://www.w3.org/2000/svg" onload="danger()" style="fill:white;background-image:image-set('https://outside.invalid/a.png' 1x)">
                  <script>danger()</script><image href="https://outside.invalid/a.png"/>
                  <style>.wall {fill:black; background-image:image-set('https://outside.invalid/b.png' 1x)}</style>
                  <defs><rect id="wall" width="5" height="100"/></defs><use href="#wall"/>
                </svg>
                """);
        assertFalse(safe.contains("outside.invalid"));
        assertFalse(safe.contains("onload"));
        assertFalse(safe.contains("<script"));
        assertTrue(safe.contains("<use"));
        assertTrue(safe.contains("fill:black"));
        assertThrows(IllegalArgumentException.class, () -> SvgSafety.sanitize("<!DOCTYPE svg [<!ENTITY x SYSTEM 'file:///etc/passwd'>]><svg>&x;</svg>"));
        assertThrows(IllegalArgumentException.class, () -> SvgSafety.sanitize("<svg xmlns='http://www.w3.org/2000/svg' style='transform:translateX(10px)'/>"));
    }

    @Test void rootSvgTransformIsAppliedOnceToModelAndNotToFutureOverlays() {
        String svg = """
                <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 200 100" data-scale="1" transform="translate(20 0)">
                  <rect data-type="room" data-id="R" data-capacity="2" x="0" y="0" width="10" height="10"/>
                  <line data-type="exit" data-id="E" data-connects="R,OUTSIDE" data-throughput="1" x1="10" y1="0" x2="10" y2="10"/>
                </svg>
                """;
        var document = SvgSafety.read(svg);
        assertFalse(document.getDocumentElement().hasAttribute("transform"));
        assertTrue(SvgSafety.serialize(document).contains("<g transform="));
        var prepared = new SemanticNetworkBuilder().build(List.of(new FloorDto(1, "First", 1, svg, new double[]{0, 0, 200, 100})), 1, 1, 4);
        assertEquals(25, prepared.network().nodes().getFirst().x(), 1e-9);
        assertEquals(30, prepared.network().passages().getFirst().beforePath().getLast().x(), 1e-9);
    }
}

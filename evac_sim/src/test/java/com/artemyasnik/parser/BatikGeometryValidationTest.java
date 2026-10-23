package com.artemyasnik.parser;

import com.artemyasnik.model.BoundingBox;
import com.artemyasnik.model.FloorPlan;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.xml.sax.SAXException;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class BatikGeometryValidationTest {
    private FloorPlan parse(String svg) throws Exception {
        return new SvgFloorPlanParser().parse(new ByteArrayInputStream(svg.getBytes(StandardCharsets.UTF_8)));
    }

    private String plan(String content) {
        return "<svg xmlns='http://www.w3.org/2000/svg' data-scale='1' data-floor='1'>" + content + "</svg>";
    }

    private String room(String attributes) {
        return "<rect data-type='room' data-id='R' data-capacity='2' width='10' height='10' " + attributes + "/>";
    }

    @Test
    void cubicBezierUsesTheCurveInsteadOfItsControlPoints() throws Exception {
        FloorPlan result = parse(plan("<path data-type='room' data-id='R' data-capacity='2' d='M0 0 C0 100 100 100 100 0 Z'/>"));
        BoundingBox box = result.getRooms().get("R").getBoundingBox();
        assertEquals(75, box.maxY(), .1);
        assertEquals(100, box.maxX(), .1);
        assertTrue(result.getRooms().get("R").getGeometry().size() > 10);
    }

    @Test
    void ellipticalArcAndRelativeCommandsAreSupported() throws Exception {
        FloorPlan result = parse(plan("<path data-type='room' data-id='R' data-capacity='2' d='m10 20 a50 50 0 0 1 100 0 z'/>"));
        BoundingBox box = result.getRooms().get("R").getBoundingBox();
        assertEquals(10, box.minX(), .1);
        assertEquals(110, box.maxX(), .1);
        assertEquals(50, box.height(), .5); // Дуга Batik аппроксимируется кубическими сегментами.
    }

    @Test
    void inheritedTransformsAffectPhysicalCoordinatesAndDimensions() throws Exception {
        FloorPlan result = parse(plan("<g transform='translate(10 20)'><rect data-type='room' data-id='R' data-capacity='2' width='10' height='20' transform='rotate(90)'/></g>"));
        BoundingBox box = result.getRooms().get("R").getBoundingBox();
        assertEquals(-10, box.minX(), 1e-9);
        assertEquals(10, box.maxX(), 1e-9);
        assertEquals(20, box.minY(), 1e-9);
        assertEquals(30, box.maxY(), 1e-9);
        assertEquals(200, result.getRooms().get("R").getAreaSqM(), 1e-9);
    }

    @Test
    void polygonAcceptsStandardWhitespaceAndCommaSyntax() throws Exception {
        FloorPlan result = parse(plan("<polygon data-type='room' data-id='R' data-capacity='2' points='0 0,10 0,10 10,0 10'/>"));
        assertEquals(100, result.getRooms().get("R").getAreaSqM(), 1e-9);
    }

    @Test
    void duplicateIdsAcrossDifferentElementTypesAreRejected() {
        String svg = plan(room("") + "<line data-type='exit' data-id='R' data-connects='R,OUTSIDE' x2='1'/>");
        assertTrue(assertThrows(IllegalArgumentException.class, () -> parse(svg)).getMessage().contains("data-id"));
    }

    @Test
    void unknownConnectorEndpointIsRejectedAtImport() {
        String svg = plan(room("") + "<line data-type='door' data-id='D' data-connects='R,missing' x2='1'/>");
        assertTrue(assertThrows(IllegalArgumentException.class, () -> parse(svg)).getMessage().contains("missing"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"R,OUTSIDE,another", "R,", ",R", "R,R"})
    void malformedConnectorReferencesAreRejected(String references) {
        String svg = plan(room("") + "<line data-type='exit' data-id='E' data-connects='" + references + "' x2='1'/>");
        assertThrows(IllegalArgumentException.class, () -> parse(svg));
    }

    @ParameterizedTest
    @ValueSource(strings = {"NaN", "Infinity", "0", "-1", "bad", ""})
    void invalidScaleIsNeverReplacedByADefault(String value) {
        assertThrows(IllegalArgumentException.class, () -> parse("<svg data-scale='" + value + "'>" + room("") + "</svg>"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"NaN", "Infinity", "0", "-1", "bad"})
    void invalidThroughputIsRejected(String value) {
        String svg = plan(room("") + "<line data-type='exit' data-id='E' data-connects='R,OUTSIDE' data-throughput='" + value + "' x2='1'/>");
        assertThrows(IllegalArgumentException.class, () -> parse(svg));
    }

    @ParameterizedTest
    @ValueSource(strings = {"-1", "2.3", "NaN", "2147483648", "bad"})
    void invalidCapacityIsRejected(String value) {
        assertThrows(IllegalArgumentException.class, () -> parse(plan(room("").replace("data-capacity='2'", "data-capacity='" + value + "'"))));
    }

    @Test
    void missingCapacityAndFractionalFloorAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> parse(plan(room("").replace("data-capacity='2'", ""))));
        assertThrows(IllegalArgumentException.class, () -> parse(plan(room("")).replace("data-floor='1'", "data-floor='1.5'")));
    }

    @Test
    void malformedPathIsRejectedByBatik() {
        assertThrows(IllegalArgumentException.class, () -> parse(plan("<path data-type='room' data-id='R' data-capacity='2' d='M0 0 C10 20'/>")));
    }

    @Test
    void externalEntitiesAreRejectedBeforeResolvingAnyResource() {
        String svg = "<!DOCTYPE svg [<!ENTITY external SYSTEM 'file:///does-not-exist'>]><svg><text>&external;</text></svg>";
        assertThrows(SAXException.class, () -> parse(svg));
    }

    @Test
    void nestedViewportsAreRejectedInsteadOfLosingTheirCoordinateSystem() {
        assertThrows(IllegalArgumentException.class, () -> parse(plan("<svg x='100'>" + room("") + "</svg>")));
    }
}

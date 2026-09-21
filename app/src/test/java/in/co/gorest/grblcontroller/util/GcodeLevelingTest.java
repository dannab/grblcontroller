package in.co.gorest.grblcontroller.util;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class GcodeLevelingTest {

    private static final List<String> POINTS = Arrays.asList(
            ";GRBLCONTROLLER_LEVELING_V2 WCS=G54 UNITS=G21",
            "0,0,0",
            "100,0,1",
            "0,100,2"
    );

    @Test
    public void compensatesAbsoluteLinearMovements() throws Exception {
        List<String> output = GcodeLeveling.transform(POINTS, Arrays.asList(
                "G21 G90 G54",
                "G0 X0 Y0",
                "G1 Z-1",
                "G1 X100"
        ));

        assertTrue(output.contains("G1 Z-1.000"));
        assertTrue(output.contains("G1 X100 Z0.000"));
    }

    @Test
    public void compensatesIncrementalXyWithPlaneDelta() throws Exception {
        List<String> output = GcodeLeveling.transform(POINTS, Arrays.asList(
                "G21 G90 G54",
                "G0 X0 Y0",
                "G1 Z-1",
                "G91",
                "G1 X100",
                "G1 Y50 Z-0.5"
        ));

        assertTrue(output.contains("G1 X100 Z1.000"));
        assertTrue(output.contains("G1 Y50 Z0.500"));
    }

    @Test
    public void rejectsCamArcs() throws Exception {
        assertError(GcodeLeveling.ErrorCode.ARC_UNSUPPORTED, 4, Arrays.asList(
                "G21 G90 G54",
                "G0 X0 Y0",
                "G1 Z-1",
                "G2 X10 Y10 I5 J0"
        ));
    }

    @Test
    public void rejectsInchFiles() throws Exception {
        assertError(GcodeLeveling.ErrorCode.UNSUPPORTED_UNITS, 1, Arrays.asList(
                "G20 G90 G54",
                "G0 X0 Y0"
        ));
    }

    @Test
    public void rejectsDifferentWorkCoordinateSystem() throws Exception {
        assertError(GcodeLeveling.ErrorCode.WCS_MISMATCH, 1, Arrays.asList(
                "G21 G90 G55",
                "G0 X0 Y0"
        ));
    }

    @Test
    public void rejectsAbsoluteZBeforeInitialXy() throws Exception {
        assertError(GcodeLeveling.ErrorCode.INITIAL_XY_UNKNOWN, 2, Arrays.asList(
                "G21 G90 G54",
                "G0 Z5",
                "G0 X0 Y0"
        ));
    }

    @Test
    public void legacyPointsRequireNewContext() throws Exception {
        try {
            GcodeLeveling.transform(Arrays.asList(
                    "0,0,0", "100,0,1", "0,100,2"),
                    Arrays.asList("G21 G90 G54"));
            fail("Expected point context error");
        } catch (GcodeLeveling.LevelingException error) {
            assertEquals(GcodeLeveling.ErrorCode.POINT_CONTEXT_MISSING, error.code);
        }
    }

    @Test
    public void collectedScanCanHaveMoreThanThreePointsWithoutEnteringLeveling() throws Exception {
        List<String> scan = Arrays.asList(
                ";GRBLCONTROLLER_LEVELING_V2 WCS=G54 UNITS=G21",
                "0,0,0", "10,0,0", "0,10,0", "10,10,0", "20,10,0");
        assertEquals(5, GcodeLeveling.inspectPointsText(String.join("\n", scan)).getPointCount());
        try {
            GcodeLeveling.transform(scan, Arrays.asList("G21 G90 G54", "G0 X0 Y0"));
            fail("Leveling must require an explicit three-point set");
        } catch (GcodeLeveling.LevelingException error) {
            assertEquals(GcodeLeveling.ErrorCode.WRONG_POINT_COUNT, error.code);
        }
    }

    private static void assertError(GcodeLeveling.ErrorCode expectedCode,
                                    int expectedLine, List<String> gcode) throws Exception {
        try {
            GcodeLeveling.transform(POINTS, gcode);
            fail("Expected " + expectedCode);
        } catch (GcodeLeveling.LevelingException error) {
            assertEquals(expectedCode, error.code);
            assertEquals(expectedLine, error.lineNumber);
        }
    }
}

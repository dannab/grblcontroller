package in.co.gorest.grblcontroller.util;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class SimpleGcodeMakerTest {

    @Test
    public void polylineUsesOnlyFirstZAndAlternatesPassDirection() {
        List<PlacementPoints.Point> points = Arrays.asList(
                new PlacementPoints.Point(0, 0, 10),
                new PlacementPoints.Point(10, 5, 99),
                new PlacementPoints.Point(20, 0, -50));
        SimpleGcodeMaker maker = new SimpleGcodeMaker(
                0, 0, 10, 5, 10,
                2, 3, 5, 100, 200, true);

        String gcode = maker.polylineCut(points);

        assertEquals(2, maker.getDepthPassCount());
        assertEquals(1.5, maker.getEffectiveDepthStep(), 0.000001);
        assertTrue(gcode.contains("G00 Z15.000"));
        assertTrue(gcode.contains("G01 Z8.500 F100.000"));
        assertTrue(gcode.contains("G01 Z7.000 F100.000"));
        assertFalse(gcode.contains("Z99.000"));
        assertFalse(gcode.contains("Z-50.000"));

        int forwardMiddle = gcode.indexOf("G01 X10.000 Y5.000");
        int forwardEnd = gcode.indexOf("G01 X20.000 Y0.000");
        int reverseMiddle = gcode.indexOf("G01 X10.000 Y5.000", forwardMiddle + 1);
        int reverseStart = gcode.indexOf("G01 X0.000 Y0.000");
        assertTrue(forwardMiddle < forwardEnd);
        assertTrue(forwardEnd < reverseMiddle);
        assertTrue(reverseMiddle < reverseStart);
    }
}

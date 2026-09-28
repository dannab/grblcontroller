package in.co.gorest.grblcontroller.util;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

public class PlacementPointsTest {

    @Test
    public void readsCoordinateOnlyFileInOriginalOrder() throws Exception {
        List<PlacementPoints.Point> points = PlacementPoints.parse(Arrays.asList(
                "1.0,2.0,3.0",
                "",
                "-4.5 5.5 99.0",
                "6,7,-8"));

        assertEquals(3, points.size());
        assertEquals(1.0, points.get(0).x, 0.0);
        assertEquals(99.0, points.get(1).z, 0.0);
        assertEquals(6.0, points.get(2).x, 0.0);
    }

    @Test
    public void reportsExactMalformedLine() throws Exception {
        try {
            PlacementPoints.parse(Arrays.asList("0,0,0", "10,broken,2"));
            fail("Expected invalid coordinate");
        } catch (PlacementPoints.ParseException error) {
            assertEquals(2, error.lineNumber);
            assertEquals("10,broken,2", error.lineText);
        }
    }
}

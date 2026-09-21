package in.co.gorest.grblcontroller.util;

import org.junit.Test;
import static org.junit.Assert.*;
import java.util.Arrays;

public class FluidNcSdListingTest {
    @Test public void legacyPathsPreserveSpacesAndPunctuation() {
        FluidNcSdListing list = new FluidNcSdListing();
        list.accept("[FILE:/lavori/pezzo (2).nc|SIZE:29547]");
        assertEquals(Arrays.asList("/lavori/pezzo (2).nc"), list.files);
    }
    @Test public void nestedDirectoriesAndSiblingsResolveCorrectly() {
        FluidNcSdListing list = new FluidNcSdListing();
        list.accept("[DIR:lavori]");
        list.accept("[FILE:  uno.nc|SIZE:20]");
        list.accept("[DIR: finitura]");
        list.accept("[FILE:   due.nc|SIZE:30]");
        list.accept("[DIR:altri]");
        list.accept("[FILE:  tre.nc|SIZE:40]");
        list.accept("[FILE: root.nc|SIZE:50]");
        assertEquals(Arrays.asList("/lavori/uno.nc", "/lavori/finitura/due.nc",
                "/altri/tre.nc", "/root.nc"), list.files);
    }
    @Test public void ignoresMalformedAndAmbiguousPaths() {
        FluidNcSdListing list = new FluidNcSdListing();
        list.accept("[FILE:   orphan.nc|SIZE:2]");
        list.accept("[FILE:bad\n$H|SIZE:2]");
        list.accept("[MSG:hello]");
        assertTrue(list.files.isEmpty());
    }
}

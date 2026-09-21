package in.co.gorest.grblcontroller.util;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class GrblUtilsFluidNcTest {

    @Test
    public void readsMachineNameFromBuildInfo() {
        assertEquals("Fresa laboratorio",
                GrblUtils.getFluidNcMachineName("[MSG:Machine: Fresa laboratorio]"));
    }

    @Test
    public void readsMachineNameFromStartupInfo() {
        assertEquals("DemonX",
                GrblUtils.getFluidNcMachineName("[MSG:INFO: Machine DemonX]"));
    }

    @Test
    public void preservesColonsInsideMachineName() {
        assertEquals("Router: banco 1",
                GrblUtils.getFluidNcMachineName("[MSG:Machine: Router: banco 1]"));
    }

    @Test
    public void ignoresOtherFluidNcMessages() {
        assertNull(GrblUtils.getFluidNcMachineName(
                "[MSG:Mode=STA:SSID=FluidNC:Status=Connected]"));
        assertNull(GrblUtils.getFluidNcMachineName("[MSG:INFO: Board None]"));
    }
}

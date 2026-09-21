package in.co.gorest.grblcontroller.service;

import org.junit.Test;

import static org.junit.Assert.*;

public class PhoneBatteryStatusTest {
    @Test public void scalesLevelAndDisplaysCharging() {
        PhoneBatteryStatus status = PhoneBatteryStatus.fromValues(3, 4, true, true, true);
        assertEquals(75, status.percent);
        assertEquals("Batteria telefono: 75% · in carica", status.displayText());
        assertFalse(status.isLow());
        assertEquals("\"batteryPercent\":75,\"batteryCharging\":true,\"batteryPlugged\":true",
                status.jsonFields());
    }

    @Test public void unknownIsNotZeroOrLowBattery() {
        PhoneBatteryStatus status = PhoneBatteryStatus.fromValues(-1, 100, true, false, false);
        assertEquals(-1, status.percent);
        assertFalse(status.isLow());
        assertEquals("Batteria telefono: non disponibile", status.displayText());
        assertTrue(status.jsonFields().contains("\"batteryPercent\":null"));
    }

    @Test public void invalidScaleAndMissingBatteryAreUnknown() {
        assertSame(PhoneBatteryStatus.UNKNOWN,
                PhoneBatteryStatus.fromValues(10, 0, true, false, false));
        assertSame(PhoneBatteryStatus.UNKNOWN,
                PhoneBatteryStatus.fromValues(10, 100, false, false, false));
    }

    @Test public void marksLowBatteryIncludingZero() {
        assertTrue(PhoneBatteryStatus.fromValues(0, 100, true, false, false).isLow());
        assertTrue(PhoneBatteryStatus.fromValues(20, 100, true, false, false).isLow());
        assertFalse(PhoneBatteryStatus.fromValues(21, 100, true, false, false).isLow());
    }

    @Test public void pluggedDoesNotNecessarilyMeanCharging() {
        PhoneBatteryStatus status = PhoneBatteryStatus.fromValues(100, 100, true, false, true);
        assertFalse(status.charging);
        assertTrue(status.plugged);
        assertEquals("Batteria telefono: 100% · alimentazione collegata", status.displayText());
        assertEquals(100, PhoneBatteryStatus.fromValues(200, 100, true, false, false).percent);
    }
}

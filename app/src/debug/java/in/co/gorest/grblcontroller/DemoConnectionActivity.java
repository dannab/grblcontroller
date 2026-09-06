/*
 * Copyright (C) 2017 Grbl Controller Contributors
 * Modifications Copyright (C) 2024-2026 Daniele Cicchinelli
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Original project: GRBLController by zeevy
 * https://github.com/zeevy/grblcontroller
 */
package in.co.gorest.grblcontroller;

import android.os.Bundle;
import android.view.Menu;

import com.joanzapata.iconify.IconDrawable;
import com.joanzapata.iconify.fonts.FontAwesomeIcons;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import in.co.gorest.grblcontroller.listeners.ConsoleLoggerListener;
import in.co.gorest.grblcontroller.listeners.FileSenderListener;
import in.co.gorest.grblcontroller.listeners.MachineStatusListener;
import in.co.gorest.grblcontroller.model.Constants;
import in.co.gorest.grblcontroller.model.Position;

/**
 * Debug-only controller simulator for UI testing and documentation captures.
 * It never binds a serial service and all outgoing machine commands are ignored.
 */
public class DemoConnectionActivity extends GrblActivity {

    private static final String DEMO_GCODE =
            "; GRBL Machining demo - rounded plate\n" +
            "G21 G17 G90 G54\n" +
            "G0 Z5\n" +
            "G0 X0 Y0\n" +
            "M3 S12000\n" +
            "G1 Z-1 F180\n" +
            "G1 X40 Y0 F600\n" +
            "G2 X50 Y10 I0 J10\n" +
            "G1 X50 Y30\n" +
            "G2 X40 Y40 I-10 J0\n" +
            "G1 X0 Y40\n" +
            "G2 X-10 Y30 I0 J-10\n" +
            "G1 X-10 Y10\n" +
            "G2 X0 Y0 I10 J0\n" +
            "G0 Z5\n" +
            "M5\n" +
            "M30\n";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        configureDemoMachine();
        loadDemoFile();
    }

    private void configureDemoMachine() {
        machineStatus.setState(Constants.MACHINE_STATUS_IDLE);
        machineStatus.setMachinePosition(new Position(125.000, 80.000, 12.000, 0.000));
        machineStatus.setWorkPosition(new Position(10.000, 15.000, 5.000, 0.000));
        machineStatus.setWorkCoordsOffset(new Position(115.000, 65.000, 7.000, 0.000));
        machineStatus.setFeedRate(600.0);
        machineStatus.setSpindleSpeed(12000.0);
        machineStatus.setPlannerBuffer(Constants.DEFAULT_PLANNER_BUFFER);
        machineStatus.setSerialRxBuffer(Constants.DEFAULT_SERIAL_RX_BUFFER);
        machineStatus.setOverridePercents(100, 100, 100);
        machineStatus.setParserState("G1 G54 G17 G21 G90 G94 M5 M9 T0 F600 S12000");
        machineStatus.setCompileTimeOptions(new MachineStatusListener.CompileTimeOptions(
                "VNM", Constants.DEFAULT_PLANNER_BUFFER, Constants.DEFAULT_SERIAL_RX_BUFFER));
        applySubtitle("DEMO — simulazione offline");
    }

    private void loadDemoFile() {
        File directory = getExternalFilesDir(null);
        if (directory == null) directory = getFilesDir();
        File demo = new File(directory, "grbl_machining_demo.nc");
        try (FileOutputStream output = new FileOutputStream(demo, false)) {
            output.write(DEMO_GCODE.getBytes(StandardCharsets.UTF_8));
            FileSenderListener sender = FileSenderListener.getInstance();
            sender.setGcodeFile(demo);
            sender.setRowsInFile(DEMO_GCODE.split("\\n").length);
            sender.setRowsSent(0);
            sender.setStatus(FileSenderListener.STATUS_IDLE);
            sender.setLastComment("GRBL Machining demo - rounded plate");
            ConsoleLoggerListener.getInstance().offerMessage("Grbl 1.1h ['$' for help]");
            ConsoleLoggerListener.getInstance().offerMessage("[MSG:Demo mode - serial output disabled]");
        } catch (IOException ignored) {
            // Demo mode remains usable even if the sample cannot be persisted.
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_main, menu);
        menu.removeItem(R.id.action_connect);
        menu.findItem(R.id.action_grbl_reset).setIcon(new IconDrawable(this,
                FontAwesomeIcons.fa_power_off).colorRes(R.color.colorWhite).sizeDp(24));
        return super.onCreateOptionsMenu(menu);
    }

    @Override
    public void onGcodeCommandReceived(String command) {
        ConsoleLoggerListener.getInstance().offerMessage("[DEMO] " + command);
    }

    @Override
    public void onGrblRealTimeCommandReceived(byte command) {
        ConsoleLoggerListener.getInstance().offerMessage("[DEMO realtime] 0x"
                + Integer.toHexString(command & 0xff).toUpperCase());
    }
}

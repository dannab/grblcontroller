/*
 * Copyright (C) 2026 Daniele Cicchinelli
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package in.co.gorest.grblcontroller.util;

/**
 * Removes Telnet control sequences from the FluidNC byte stream.
 *
 * FluidNC normally behaves like a transparent serial stream, but a Telnet
 * client must still tolerate option negotiation. Unsupported options are
 * declined so their bytes can never reach the GRBL line parser.
 */
public final class TelnetProtocolDecoder {

    public interface Listener {
        void onDataByte(int value);
        void onReply(byte[] reply);
    }

    private static final int IAC = 255;
    private static final int DONT = 254;
    private static final int DO = 253;
    private static final int WONT = 252;
    private static final int WILL = 251;
    private static final int SB = 250;
    private static final int SE = 240;

    private static final int DATA = 0;
    private static final int COMMAND = 1;
    private static final int OPTION = 2;
    private static final int SUBNEGOTIATION = 3;
    private static final int SUBNEGOTIATION_IAC = 4;

    private final Listener listener;
    private int state = DATA;
    private int pendingCommand;

    public TelnetProtocolDecoder(Listener listener) {
        this.listener = listener;
    }

    public void accept(int rawValue) {
        int value = rawValue & 0xff;
        switch (state) {
            case DATA:
                if (value == IAC) state = COMMAND;
                else listener.onDataByte(value);
                break;

            case COMMAND:
                if (value == IAC) {
                    listener.onDataByte(IAC);
                    state = DATA;
                } else if (value == WILL || value == WONT || value == DO || value == DONT) {
                    pendingCommand = value;
                    state = OPTION;
                } else if (value == SB) {
                    state = SUBNEGOTIATION;
                } else {
                    state = DATA;
                }
                break;

            case OPTION:
                if (pendingCommand == WILL) {
                    listener.onReply(new byte[]{(byte) IAC, (byte) DONT, (byte) value});
                } else if (pendingCommand == DO) {
                    listener.onReply(new byte[]{(byte) IAC, (byte) WONT, (byte) value});
                }
                state = DATA;
                break;

            case SUBNEGOTIATION:
                if (value == IAC) state = SUBNEGOTIATION_IAC;
                break;

            case SUBNEGOTIATION_IAC:
                state = value == SE ? DATA : SUBNEGOTIATION;
                break;

            default:
                state = DATA;
        }
    }
}

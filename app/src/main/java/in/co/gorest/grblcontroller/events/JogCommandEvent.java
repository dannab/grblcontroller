/*
 *  /**
 *  * Copyright (C) 2017  Grbl Controller Contributors
 *  *
 *  * This program is free software; you can redistribute it and/or modify
 *  * it under the terms of the GNU General Public License as published by
 *  * the Free Software Foundation; either version 2 of the License, or
 *  * (at your option) any later version.
 *  *
 *  * This program is distributed in the hope that it will be useful,
 *  * but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  * GNU General Public License for more details.
 *  *
 *  * You should have received a copy of the GNU General Public License along
 *  * with this program; if not, write to the Free Software Foundation, Inc.,
 *  * 51 Franklin Street, Fifth Floor, Boston, MA 02110-1301 USA.
 *  * <http://www.gnu.org/licenses/>
 *
 */

package in.co.gorest.grblcontroller.events;

import java.util.concurrent.atomic.AtomicLong;

public class JogCommandEvent {

    private static final AtomicLong NEXT_GESTURE_TOKEN = new AtomicLong();

    private String command;
    private String status;
    private boolean accepted;
    private final long gestureToken;

    public JogCommandEvent(String command){
        this(command, newGestureToken());
    }

    public JogCommandEvent(String command, long gestureToken){
        this.command = command;
        this.gestureToken = gestureToken;
    }

    /** Un token identifica l'intera pressione, incluse tutte le ripetizioni. */
    public static long newGestureToken(){ return NEXT_GESTURE_TOKEN.incrementAndGet(); }

    /** Usato dal service per invalidare atomicamente ogni gesto gia' iniziato. */
    public static long currentGestureToken(){ return NEXT_GESTURE_TOKEN.get(); }

    public String getCommand(){ return this.command; }
    public void setCommand(String command){ this.command = command; }

    public String getStatus(){ return this.status; }
    public void setStatus(String status){ this.status = status; }

    public long getGestureToken(){ return gestureToken; }

    public boolean isAccepted(){ return accepted; }
    public void markAccepted(){ accepted = true; }

}

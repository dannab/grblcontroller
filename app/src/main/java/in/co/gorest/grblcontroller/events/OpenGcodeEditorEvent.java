/*
 * Copyright (C) 2024-2026 Daniele Cicchinelli
 *
 * Based on GRBLController by zeevy
 * https://github.com/zeevy/grblcontroller
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin Street, Fifth Floor, Boston, MA 02110-1301 USA.
 * <http://www.gnu.org/licenses/>
 */
package in.co.gorest.grblcontroller.events;

/**
 * Richiesta di aprire l'editor GCode e posizionare il cursore su una riga
 * specifica (1-based, come mostrato nell'editor). Usato dal "Run from line"
 * per portare l'utente esattamente sulla riga di ripartenza.
 */
public class OpenGcodeEditorEvent {

    private final int line;

    public OpenGcodeEditorEvent(int line) {
        this.line = line;
    }

    public int getLine() {
        return line;
    }
}

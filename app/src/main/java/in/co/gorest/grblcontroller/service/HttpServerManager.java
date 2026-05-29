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
package in.co.gorest.grblcontroller.service;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.util.Log;

import java.io.File;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Collections;
import java.util.Enumeration;

import in.co.gorest.grblcontroller.R;

/**
 * Thin facade around {@link HttpServerService}.
 *
 * The actual server lives inside a foreground Service; this class is just a
 * convenience for the rest of the app to start/stop and to query status.
 *
 * Lifecycle rule (security): the server only starts if the user has both
 * enabled it AND set a password of at least {@link #MIN_PASSWORD_LENGTH}
 * characters. {@code start()} silently no-ops otherwise; a UI surface (e.g.
 * SettingsActivity) is expected to show an explanatory warning.
 */
public class HttpServerManager {

    private static final String TAG = HttpServerManager.class.getSimpleName();

    public static final int DEFAULT_PORT = 8888;
    public static final int MIN_PASSWORD_LENGTH = 4;

    private static HttpServerManager instance;

    /** Connected machine name (e.g. Bluetooth device name). May be null. */
    private static volatile String machineName;

    public static synchronized HttpServerManager getInstance() {
        if (instance == null) instance = new HttpServerManager();
        return instance;
    }

    private HttpServerManager() {}

    /** Set the human-readable machine identifier shown on the HTTP page header. */
    public void setMachineName(String name) {
        machineName = name;
    }

    /** Returns the current machine name, or null. Safe to call from any thread. */
    public static String getMachineName() {
        return machineName;
    }

    public boolean isRunning() {
        return HttpServerService.getRunning() != null;
    }

    public int getRunningPort() {
        HttpServerService r = HttpServerService.getRunning();
        return r != null ? r.getPort() : -1;
    }

    /**
     * Starts the foreground service if the user has enabled the server AND
     * set a password. No-ops otherwise — including when called from a context
     * that is not allowed to start a foreground service (e.g. Application
     * onCreate triggered by Firebase in the background on API 31+); the
     * resulting exception is logged and swallowed.
     */
    public void start(Context ctx) {
        if (!preferencesPermitStart(ctx)) return;
        Intent i = new Intent(ctx.getApplicationContext(), HttpServerService.class);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ctx.getApplicationContext().startForegroundService(i);
            } else {
                ctx.getApplicationContext().startService(i);
            }
        } catch (Exception e) {
            // ForegroundServiceStartNotAllowedException, IllegalStateException,
            // SecurityException — all non-fatal here.
            Log.w(TAG, "startService failed", e);
        }
    }

    public void stop(Context ctx) {
        try {
            ctx.getApplicationContext().stopService(
                    new Intent(ctx.getApplicationContext(), HttpServerService.class));
        } catch (Exception e) {
            Log.w(TAG, "stopService failed", e);
        }
    }

    public synchronized void restart(Context ctx) {
        stop(ctx);
        start(ctx);
    }

    /**
     * Returns "http://&lt;ip&gt;:&lt;port&gt;" or null if the server is not running.
     */
    public String getDisplayUrl() {
        HttpServerService r = HttpServerService.getRunning();
        if (r == null) return null;
        String ip = getLocalIpv4();
        if (ip == null) ip = "?";
        return "http://" + ip + ":" + r.getPort();
    }

    /**
     * Reason why {@link #start(Context)} would refuse, suitable for showing to
     * the user in the Settings screen. Returns null when start would succeed.
     */
    public static String getStartBlockReason(Context ctx) {
        SharedPreferences sp = ctx.getSharedPreferences(
                ctx.getString(R.string.shared_preference_key), Context.MODE_PRIVATE);
        if (!sp.getBoolean(ctx.getString(R.string.preference_http_server_enabled), false)) {
            return ctx.getString(R.string.text_http_server_off);
        }
        String pw = sp.getString(ctx.getString(R.string.preference_http_server_password), "");
        if (pw == null || pw.length() < MIN_PASSWORD_LENGTH) {
            return ctx.getString(R.string.text_http_server_no_password);
        }
        return null;
    }

    // ---- package-private helpers used by HttpServerService -------------------

    static boolean preferencesPermitStart(Context ctx) {
        SharedPreferences sp = ctx.getSharedPreferences(
                ctx.getString(R.string.shared_preference_key), Context.MODE_PRIVATE);
        if (!sp.getBoolean(ctx.getString(R.string.preference_http_server_enabled), false)) {
            return false;
        }
        String pw = sp.getString(ctx.getString(R.string.preference_http_server_password), "");
        return pw != null && pw.length() >= MIN_PASSWORD_LENGTH;
    }

    static int parsePort(String s) {
        try {
            int p = Integer.parseInt(s.trim());
            if (p < 1024 || p > 65535) return DEFAULT_PORT;
            return p;
        } catch (Exception e) {
            return DEFAULT_PORT;
        }
    }

    static File getAppMediaDir(Context ctx) {
        File[] dirs = ctx.getExternalMediaDirs();
        if (dirs == null || dirs.length == 0 || dirs[0] == null) return null;
        if (!dirs[0].exists()) dirs[0].mkdirs();
        return dirs[0];
    }

    /**
     * Returns an IPv4 the user can plausibly reach from another device on the
     * same LAN. Prefers WiFi (wlan-prefixed and ap-prefixed interfaces) over
     * any other interface, which avoids showing the cellular IP on a
     * multi-homed device.
     */
    public static String getLocalIpv4() {
        try {
            String wifi = findIpv4(true);
            if (wifi != null) return wifi;
            return findIpv4(false);
        } catch (Exception e) {
            Log.w(TAG, "getLocalIpv4 failed", e);
        }
        return null;
    }

    private static String findIpv4(boolean wifiOnly) throws Exception {
        Enumeration<NetworkInterface> ifs = NetworkInterface.getNetworkInterfaces();
        if (ifs == null) return null;
        for (NetworkInterface nif : Collections.list(ifs)) {
            if (nif.isLoopback() || !nif.isUp()) continue;
            if (wifiOnly) {
                String n = nif.getName();
                if (n == null || !(n.startsWith("wlan") || n.startsWith("ap"))) continue;
            }
            for (InetAddress addr : Collections.list(nif.getInetAddresses())) {
                if (addr.isLoopbackAddress()) continue;
                if (addr instanceof Inet4Address) {
                    return addr.getHostAddress();
                }
            }
        }
        return null;
    }
}

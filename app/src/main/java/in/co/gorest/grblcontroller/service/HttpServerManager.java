package in.co.gorest.grblcontroller.service;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import java.io.File;
import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Collections;
import java.util.Enumeration;

import in.co.gorest.grblcontroller.R;

public class HttpServerManager {

    private static final String TAG = HttpServerManager.class.getSimpleName();
    public static final int DEFAULT_PORT = 8888;

    private static HttpServerManager instance;

    private GcodeHttpServer server;
    private int runningPort = -1;

    public static synchronized HttpServerManager getInstance() {
        if (instance == null) instance = new HttpServerManager();
        return instance;
    }

    private HttpServerManager() {}

    public synchronized boolean isRunning() {
        return server != null && server.isAlive();
    }

    public synchronized int getRunningPort() {
        return runningPort;
    }

    public synchronized void start(Context ctx) {
        if (isRunning()) return;
        SharedPreferences sp = getPrefs(ctx);
        if (!sp.getBoolean(ctx.getString(R.string.preference_http_server_enabled), false)) {
            return;
        }
        int port = parsePort(sp.getString(
                ctx.getString(R.string.preference_http_server_port),
                String.valueOf(DEFAULT_PORT)));
        String password = sp.getString(
                ctx.getString(R.string.preference_http_server_password), "");

        File rootDir = getAppMediaDir(ctx);
        if (rootDir == null) {
            Log.w(TAG, "No media dir available, server not started");
            return;
        }

        try {
            GcodeHttpServer s = new GcodeHttpServer(port, rootDir, password);
            s.start(GcodeHttpServer.SOCKET_READ_TIMEOUT, false);
            this.server = s;
            this.runningPort = port;
            Log.i(TAG, "HTTP server started on port " + port);
        } catch (IOException e) {
            Log.e(TAG, "Failed to start HTTP server", e);
            this.server = null;
            this.runningPort = -1;
        }
    }

    public synchronized void stop() {
        if (server != null) {
            try {
                server.stop();
            } catch (Exception e) {
                Log.w(TAG, "stop error", e);
            }
            server = null;
            runningPort = -1;
        }
    }

    public synchronized void restart(Context ctx) {
        stop();
        start(ctx);
    }

    /** Returns the human-readable URL (e.g. "http://192.168.1.50:8888") or null if server not running. */
    public synchronized String getDisplayUrl() {
        if (!isRunning()) return null;
        String ip = getLocalIpv4();
        if (ip == null) ip = "?";
        return "http://" + ip + ":" + runningPort;
    }

    private static int parsePort(String s) {
        try {
            int p = Integer.parseInt(s.trim());
            if (p < 1024 || p > 65535) return DEFAULT_PORT;
            return p;
        } catch (Exception e) {
            return DEFAULT_PORT;
        }
    }

    private static SharedPreferences getPrefs(Context ctx) {
        return ctx.getSharedPreferences(
                ctx.getString(R.string.shared_preference_key), Context.MODE_PRIVATE);
    }

    private static File getAppMediaDir(Context ctx) {
        File[] dirs = ctx.getExternalMediaDirs();
        if (dirs == null || dirs.length == 0 || dirs[0] == null) return null;
        if (!dirs[0].exists()) dirs[0].mkdirs();
        return dirs[0];
    }

    public static String getLocalIpv4() {
        try {
            Enumeration<NetworkInterface> ifs = NetworkInterface.getNetworkInterfaces();
            for (NetworkInterface nif : Collections.list(ifs)) {
                if (nif.isLoopback() || !nif.isUp()) continue;
                for (InetAddress addr : Collections.list(nif.getInetAddresses())) {
                    if (addr.isLoopbackAddress()) continue;
                    if (addr instanceof Inet4Address) {
                        return addr.getHostAddress();
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "getLocalIpv4 failed", e);
        }
        return null;
    }
}

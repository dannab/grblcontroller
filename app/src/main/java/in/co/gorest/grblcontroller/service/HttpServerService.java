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

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import java.io.File;
import java.io.IOException;

import fi.iki.elonen.NanoHTTPD;
import in.co.gorest.grblcontroller.R;
import in.co.gorest.grblcontroller.SettingsActivity;

/**
 * Foreground Service that owns the lifecycle of {@link GcodeHttpServer}.
 *
 * Running the HTTP server as a foreground service is required by Android policy
 * for any user-visible network listener, and gives the user a persistent
 * notification so they know a server is exposed on their device.
 */
public class HttpServerService extends Service {

    private static final String TAG = HttpServerService.class.getSimpleName();
    public static final String CHANNEL_ID = "grbl_http_server";
    public static final int NOTIF_ID = 4242;

    private static volatile HttpServerService running;

    private GcodeHttpServer server;
    private int port = -1;

    public static HttpServerService getRunning() {
        return running;
    }

    public int getPort() {
        return port;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (server != null && server.isAlive()) {
            return START_STICKY;
        }

        SharedPreferences sp = getSharedPreferences(
                getString(R.string.shared_preference_key), Context.MODE_PRIVATE);

        if (!sp.getBoolean(getString(R.string.preference_http_server_enabled), false)) {
            stopSelfSafely();
            return START_NOT_STICKY;
        }

        String password = sp.getString(
                getString(R.string.preference_http_server_password), "");
        if (password == null || password.length() < HttpServerManager.MIN_PASSWORD_LENGTH) {
            Log.w(TAG, "HTTP server enabled but password missing/too short; refusing to start");
            stopSelfSafely();
            return START_NOT_STICKY;
        }

        int p = HttpServerManager.parsePort(sp.getString(
                getString(R.string.preference_http_server_port),
                String.valueOf(HttpServerManager.DEFAULT_PORT)));

        File rootDir = HttpServerManager.getAppMediaDir(this);
        if (rootDir == null) {
            Log.w(TAG, "No media dir available, server not started");
            stopSelfSafely();
            return START_NOT_STICKY;
        }

        // Foreground notification must be posted BEFORE the listening socket
        // binds, so the system doesn't kill the service before startForeground.
        startForeground(NOTIF_ID, buildNotification(p));

        try {
            GcodeHttpServer s = new GcodeHttpServer(p, rootDir, password);
            s.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false);
            this.server = s;
            this.port = p;
            running = this;
            // Refresh notification with the resolved URL once the socket is up.
            postNotification(buildNotification(p));
            Log.i(TAG, "HTTP server started on port " + p);
        } catch (IOException e) {
            Log.e(TAG, "Failed to start HTTP server", e);
            stopSelfSafely();
            return START_NOT_STICKY;
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        if (server != null) {
            try {
                server.stop();
            } catch (Exception e) {
                Log.w(TAG, "stop error", e);
            }
            server = null;
        }
        port = -1;
        if (running == this) running = null;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void stopSelfSafely() {
        try {
            stopForeground(true);
        } catch (Exception ignore) {}
        stopSelf();
    }

    private void postNotification(Notification n) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) nm.notify(NOTIF_ID, n);
    }

    private Notification buildNotification(int p) {
        ensureChannel();
        String ip = HttpServerManager.getLocalIpv4();
        String url = (ip != null) ? ("http://" + ip + ":" + p)
                                  : getString(R.string.text_http_server_notif_no_ip, p);
        String loginHint = getString(
                R.string.text_http_server_login_hint, HttpServerManager.USERNAME);

        Intent open = new Intent(this, SettingsActivity.class);
        int piFlags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            piFlags |= PendingIntent.FLAG_IMMUTABLE;
        }
        PendingIntent pi = PendingIntent.getActivity(this, 0, open, piFlags);

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_device_hub_black_24dp)
                .setContentTitle(getString(R.string.text_http_server_notif_title))
                .setContentText(url + " — " + loginHint)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(url + "\n" + loginHint))
                .setContentIntent(pi)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .build();
    }

    private void ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm == null) return;
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                NotificationChannel ch = new NotificationChannel(
                        CHANNEL_ID,
                        getString(R.string.text_http_server_channel_name),
                        NotificationManager.IMPORTANCE_LOW);
                ch.setDescription(getString(R.string.text_http_server_channel_desc));
                ch.setShowBadge(false);
                nm.createNotificationChannel(ch);
            }
        }
    }
}

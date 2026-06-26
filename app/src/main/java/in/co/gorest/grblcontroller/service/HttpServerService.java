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
import android.graphics.Color;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;
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

    /** Auto-stop the "find the phone" ring after this long if not stopped manually. */
    private static final long RING_DURATION_MS = 30_000L;

    private GcodeHttpServer server;
    private int port = -1;

    // ---- "find the phone" ringing state ----
    private MediaPlayer ringPlayer;
    private Handler ringStopHandler;
    private int savedAlarmVolume = -1;
    private final Runnable autoStopRing = new Runnable() {
        @Override public void run() { stopRinging(); }
    };

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
            GcodeHttpServer s = new GcodeHttpServer(p, rootDir, password,
                    new GcodeHttpServer.RingHandler() {
                        @Override public void ring() { ringPhone(); }
                        @Override public void stopRing() { stopRinging(); }
                    },
                    buildWebPalette());
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
    public void onTaskRemoved(Intent rootIntent) {
        // When the user closes the app (swipes it from recents) the hosting
        // process is often killed by the system. That silently kills the
        // NanoHTTPD listening socket — so the server stops answering — while
        // this foreground notification lingers as a zombie. Stop cleanly here
        // so the notification disappears together with the server.
        stopSelfSafely();
        super.onTaskRemoved(rootIntent);
    }

    @Override
    public void onDestroy() {
        stopRinging();
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

    /**
     * Makes the phone ring loudly to help locate it in the workshop. Plays the
     * default alarm tone on the ALARM stream (so it sounds even when the ringer
     * is on silent/vibrate), forces the alarm volume to max, vibrates, and
     * auto-stops after {@link #RING_DURATION_MS}. The previous alarm volume is
     * restored on stop. Called from a NanoHTTPD worker thread.
     */
    synchronized void ringPhone() {
        try {
            // Stop any in-progress ring first, but don't restore volume yet —
            // we're about to force it to max again.
            internalStopRinging(false);

            AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
            if (am != null) {
                // Only capture the original volume on the first ring of a burst.
                if (savedAlarmVolume < 0) {
                    savedAlarmVolume = am.getStreamVolume(AudioManager.STREAM_ALARM);
                }
                am.setStreamVolume(AudioManager.STREAM_ALARM,
                        am.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0);
            }

            Uri uri = RingtoneManager.getActualDefaultRingtoneUri(this, RingtoneManager.TYPE_ALARM);
            if (uri == null) uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM);
            if (uri == null) uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE);
            if (uri != null) {
                MediaPlayer mp = new MediaPlayer();
                mp.setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build());
                mp.setDataSource(this, uri);
                mp.setLooping(true);
                mp.prepare();
                mp.start();
                ringPlayer = mp;
            }

            startVibration();

            if (ringStopHandler == null) ringStopHandler = new Handler(Looper.getMainLooper());
            ringStopHandler.removeCallbacks(autoStopRing);
            ringStopHandler.postDelayed(autoStopRing, RING_DURATION_MS);
            Log.i(TAG, "ringPhone started");
        } catch (Exception e) {
            Log.w(TAG, "ringPhone failed", e);
        }
    }

    synchronized void stopRinging() {
        internalStopRinging(true);
    }

    private void internalStopRinging(boolean restoreVolume) {
        if (ringStopHandler != null) ringStopHandler.removeCallbacks(autoStopRing);
        if (ringPlayer != null) {
            try { if (ringPlayer.isPlaying()) ringPlayer.stop(); } catch (Exception ignore) {}
            try { ringPlayer.release(); } catch (Exception ignore) {}
            ringPlayer = null;
        }
        try {
            Vibrator v = getVibrator();
            if (v != null) v.cancel();
        } catch (Exception ignore) {}
        if (restoreVolume && savedAlarmVolume >= 0) {
            AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
            if (am != null) {
                try {
                    am.setStreamVolume(AudioManager.STREAM_ALARM, savedAlarmVolume, 0);
                } catch (Exception ignore) {}
            }
            savedAlarmVolume = -1;
        }
    }

    private void startVibration() {
        try {
            Vibrator v = getVibrator();
            if (v == null || !v.hasVibrator()) return;
            long[] pattern = {0, 600, 400};
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                // repeat at index 0 → loops until cancel()
                v.vibrate(VibrationEffect.createWaveform(pattern, 0),
                        new AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_ALARM)
                                .build());
            } else {
                //noinspection deprecation
                v.vibrate(pattern, 0);
            }
        } catch (Exception e) {
            Log.w(TAG, "vibrate failed", e);
        }
    }

    private Vibrator getVibrator() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            VibratorManager vm = (VibratorManager) getSystemService(VIBRATOR_MANAGER_SERVICE);
            return vm != null ? vm.getDefaultVibrator() : null;
        }
        //noinspection deprecation
        return (Vibrator) getSystemService(VIBRATOR_SERVICE);
    }

    /**
     * Builds the web-page colour palette from the app theme colours so the
     * HTTP interface matches the in-app look. Text colours are picked (white or
     * near-black) for readable contrast, so it keeps working if the palette
     * changes later.
     */
    private GcodeHttpServer.Palette buildWebPalette() {
        String scheme = in.co.gorest.grblcontroller.util.ThemeHelper.getScheme(this);
        String primary = colorHex(in.co.gorest.grblcontroller.util.ThemeHelper.primaryColorRes(scheme));
        String primaryDark = colorHex(in.co.gorest.grblcontroller.util.ThemeHelper.primaryDarkColorRes(scheme));
        String accent = colorHex(in.co.gorest.grblcontroller.util.ThemeHelper.accentColorRes(scheme));
        return new GcodeHttpServer.Palette(
                primary, primaryDark, accent,
                readableTextOn(primary), readableTextOn(accent));
    }

    private String colorHex(int colorRes) {
        return String.format("#%06X", 0xFFFFFF & getResources().getColor(colorRes, getTheme()));
    }

    /** Returns near-black for light backgrounds, white for dark ones (WCAG-ish). */
    private String readableTextOn(String hex) {
        int c = Color.parseColor(hex);
        double r = Color.red(c) / 255.0, g = Color.green(c) / 255.0, b = Color.blue(c) / 255.0;
        double luminance = 0.2126 * r + 0.7152 * g + 0.0722 * b;
        return luminance > 0.6 ? "#212121" : "#ffffff";
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

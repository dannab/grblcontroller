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

package in.co.gorest.grblcontroller;

import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.appcompat.widget.Toolbar;
import androidx.core.app.ActivityCompat;
import androidx.cardview.widget.CardView;
import androidx.databinding.DataBindingUtil;
import androidx.databinding.Observable;
import androidx.fragment.app.Fragment;
import androidx.viewpager.widget.ViewPager;

import java.io.File;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.google.android.material.tabs.TabLayout;
import com.joanzapata.iconify.IconDrawable;
import com.joanzapata.iconify.Iconify;
import com.joanzapata.iconify.fonts.FontAwesomeIcons;
import com.joanzapata.iconify.fonts.FontAwesomeModule;
import com.joanzapata.iconify.widget.IconTextView;

import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

import es.dmoral.toasty.Toasty;
import in.co.gorest.grblcontroller.BR;
import in.co.gorest.grblcontroller.databinding.ActivityMainBinding;
import in.co.gorest.grblcontroller.events.ConsoleMessageEvent;
import in.co.gorest.grblcontroller.events.GrblAlarmEvent;
import in.co.gorest.grblcontroller.events.GrblErrorEvent;
import in.co.gorest.grblcontroller.events.StreamingCompleteEvent;
import in.co.gorest.grblcontroller.events.OpenGcodeEditorEvent;
import in.co.gorest.grblcontroller.events.StreamingStartedEvent;
import in.co.gorest.grblcontroller.events.UiToastEvent;
import in.co.gorest.grblcontroller.helpers.EnhancedSharedPreferences;
import in.co.gorest.grblcontroller.helpers.NotificationHelper;
import in.co.gorest.grblcontroller.helpers.ReaderViewPagerTransformer;
import in.co.gorest.grblcontroller.listeners.ConsoleLoggerListener;
import in.co.gorest.grblcontroller.listeners.FileSenderListener;
import in.co.gorest.grblcontroller.listeners.MachineStatusListener;
import in.co.gorest.grblcontroller.model.Constants;
import in.co.gorest.grblcontroller.model.Position;
import in.co.gorest.grblcontroller.service.FileStreamerIntentService;
import in.co.gorest.grblcontroller.service.HttpServerManager;
import in.co.gorest.grblcontroller.service.GrblBluetoothSerialService;
import in.co.gorest.grblcontroller.ui.BaseFragment;
import in.co.gorest.grblcontroller.ui.GcodeEditorFragment;
import in.co.gorest.grblcontroller.ui.GrblFragmentPagerAdapter;
import in.co.gorest.grblcontroller.util.GcodeDropChecker;
import in.co.gorest.grblcontroller.util.GrblUtils;

public abstract class GrblActivity extends AppCompatActivity implements BaseFragment.OnFragmentInteractionListener{

    static {
        AppCompatDelegate.setCompatVectorFromResourcesEnabled(true);
    }

    protected EnhancedSharedPreferences sharedPref;

    /** Colour scheme this activity was themed with, to detect changes on resume. */
    private String appliedColorScheme;
    protected ConsoleLoggerListener consoleLogger = null;
    protected MachineStatusListener machineStatus = null;
    protected GrblBluetoothSerialService grblBluetoothSerialService = null;

    public static boolean isAppRunning;

    private Toast lastToast;
    private ViewPager tabViewPager = null;
    private CharSequence lastBaseSubtitle = null;
    private TextView toolbarTitleView = null;
    private TextView toolbarSubtitleView = null;

    // ------------------------------------------------------------------
    // Controllo affondi Z al caricamento file (vedi GcodeDropChecker)
    // ------------------------------------------------------------------
    private ExecutorService zDropExecutor;
    private File lastZDropChecked = null;
    private final Observable.OnPropertyChangedCallback gcodeFileCallback =
            new Observable.OnPropertyChangedCallback() {
                @Override
                public void onPropertyChanged(Observable sender, int propertyId) {
                    if (propertyId == BR.gcodeFile) {
                        File f = FileSenderListener.getInstance().getGcodeFile();
                        // Evita doppi check sullo stesso file (es. CamTabFragment
                        // imposta lo stesso file più volte di seguito)
                        if (f != null && f.exists() && !f.equals(lastZDropChecked)) {
                            lastZDropChecked = f;
                            runZDropCheckIfEnabled(f);
                        }
                    }
                }
            };

    // ------------------------------------------------------------------
    // Ripristino coordinate di lavoro alla (ri)connessione — opzionale,
    // attivabile da Impostazioni (preference_restore_wpos_on_connect).
    // Lo snapshot viene congelato al tap su "Connetti" (prima che il primo
    // status report sovrascriva le caselle XYZA) e reinviato con G10 L20 P0
    // appena la macchina raggiunge IDLE.
    // ------------------------------------------------------------------
    private Position pendingWorkPositionRestore = null;
    private final Observable.OnPropertyChangedCallback workPositionRestoreCallback =
            new Observable.OnPropertyChangedCallback() {
                @Override
                public void onPropertyChanged(Observable sender, int propertyId) {
                    if (propertyId == BR.state) sendPendingWorkPositionRestore();
                }
            };

    /**
     * Da chiamare al tap su "Connetti", PRIMA di stabilire il collegamento.
     * Se l'opzione è attiva e nelle caselle XYZA sono rimasti dei valori (≠0),
     * chiede conferma; in caso affermativo congela i valori e li reinvia con
     * G10 L20 P0 al primo IDLE post-connessione. In ogni caso esegue {@code onProceed},
     * che avvia la connessione vera e propria.
     */
    protected void promptRestoreWorkPositionThenConnect(final Runnable onProceed) {
        final Position wpos = machineStatus.getWorkPosition();
        boolean enabled = sharedPref.getBoolean(
                getString(R.string.preference_restore_wpos_on_connect), false);
        boolean hasValues = enabled && wpos != null
                && (wpos.getCordX() != 0.0 || wpos.getCordY() != 0.0
                 || wpos.getCordZ() != 0.0 || wpos.getCordA() != 0.0);

        if (!hasValues) {
            onProceed.run();
            return;
        }

        final Position snapshot = new Position(
                wpos.getCordX(), wpos.getCordY(), wpos.getCordZ(), wpos.getCordA());

        new AlertDialog.Builder(this)
                .setTitle(R.string.text_restore_wpos_title)
                .setMessage(getString(R.string.text_restore_wpos_desc,
                        snapshot.getCordX(), snapshot.getCordY(),
                        snapshot.getCordZ(), snapshot.getCordA()))
                .setPositiveButton(getString(R.string.text_yes_confirm), (d, w) -> {
                    pendingWorkPositionRestore = snapshot;
                    machineStatus.addOnPropertyChangedCallback(workPositionRestoreCallback);
                    onProceed.run();
                })
                .setNegativeButton(getString(R.string.text_no_confirm), (d, w) -> onProceed.run())
                .setOnCancelListener(d -> onProceed.run())
                .show();
    }

    /**
     * Invia il G10 L20 P0 congelato non appena la macchina è IDLE, poi si disarma.
     * L'asse A è incluso solo se il 4° asse è abilitato, per non generare errori
     * sui controller a 3 assi.
     */
    private void sendPendingWorkPositionRestore() {
        if (pendingWorkPositionRestore == null) return;
        if (!Constants.MACHINE_STATUS_IDLE.equals(machineStatus.getState())) return;

        // setState() (e quindi questo callback) gira sull'executor di lettura
        // seriale, NON sul main thread: congelo e disarmo subito, poi marshallo
        // l'invio del comando sul thread UI come fa il resto dell'app.
        final Position p = pendingWorkPositionRestore;
        pendingWorkPositionRestore = null;
        machineStatus.removeOnPropertyChangedCallback(workPositionRestoreCallback);

        final StringBuilder command = new StringBuilder("G10L20P0")
                .append("X").append(p.getCordX())
                .append("Y").append(p.getCordY())
                .append("Z").append(p.getCordZ());
        if (sharedPref.getBoolean(getString(R.string.preference_enable_additional_axis), false)) {
            command.append("A").append(p.getCordA());
        }

        runOnUiThread(() -> onGcodeCommandReceived(command.toString()));
    }

    /**
     * Sets the connection/machine label (toolbar line 1) and refreshes the
     * server URL on line 2 if the HTTP server is active.
     */
    protected void applySubtitle(CharSequence base) {
        lastBaseSubtitle = base;
        if (toolbarTitleView == null || toolbarSubtitleView == null) return;
        toolbarTitleView.setText(base == null ? "" : base);
        String url = HttpServerManager.getInstance().getDisplayUrl();
        toolbarSubtitleView.setText(url == null ? getString(R.string.text_http_server_off) : url);
    }

    /** Re-applies the last subtitle, picking up server state changes. */
    protected void refreshSubtitle() {
        if (lastBaseSubtitle != null) applySubtitle(lastBaseSubtitle);
    }

    @SuppressLint("SourceLockedOrientationActivity")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        in.co.gorest.grblcontroller.util.ThemeHelper.apply(this, true);
        appliedColorScheme = in.co.gorest.grblcontroller.util.ThemeHelper.getScheme(this);
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);

        ActivityMainBinding binding = DataBindingUtil.setContentView(this, R.layout.activity_main);
        sharedPref = EnhancedSharedPreferences.getInstance(GrblController.getInstance(), getString(R.string.shared_preference_key));

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) getSupportActionBar().setDisplayShowTitleEnabled(false);
        toolbarTitleView = findViewById(R.id.toolbar_title);
        toolbarSubtitleView = findViewById(R.id.toolbar_subtitle);
        applySubtitle(getString(R.string.text_not_connected));

        // Da Android 13 (API 33) POST_NOTIFICATIONS è un permesso runtime:
        // senza, le notifiche dei servizi (connessione, streaming, server HTTP)
        // restano invisibili anche se i servizi funzionano.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && ActivityCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS)
                        != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 9100);
        }

        applicationSetup();
        binding.setMachineStatus(machineStatus);

        CardView viewLastToast = findViewById(R.id.view_last_toast);
        viewLastToast.setOnLongClickListener(view -> {
            if(lastToast != null) lastToast.show();
            return true;
        });

        for(int resourceId: new Integer[]{R.id.wpos_edit_x, R.id.wpos_edit_y, R.id.wpos_edit_z, R.id.wpos_edit_a}){
            IconTextView positionTextView = findViewById(resourceId);
            positionTextView.setOnClickListener(v -> setWorkPosition(v.getTag().toString()));
        }

        Iconify.with(new FontAwesomeModule());
        setupTabLayout();
        checkPowerManagement();

        // Ascolta il caricamento di nuovi file GCode per il check affondi Z
        FileSenderListener.getInstance().addOnPropertyChangedCallback(gcodeFileCallback);
        zDropExecutor = Executors.newSingleThreadExecutor();

        //preference cam z step and deep to are set to zero to ensure that
        // there are no unwanted z-dips in onboard cam operations
        sharedPref.edit().putString(getString(R.string.preference_cam_z_step), "0").apply();
        sharedPref.edit().putString(getString(R.string.preference_cam_z_deep), "0").apply();



    }

    @Override
    protected void onResume(){
        super.onResume();
        // If the colour scheme was changed in Settings, re-theme the main screen.
        String current = in.co.gorest.grblcontroller.util.ThemeHelper.getScheme(this);
        if (appliedColorScheme != null && !appliedColorScheme.equals(current)) {
            recreate();
            return;
        }
        refreshSubtitle();
    }

    @Override
    public void onDestroy(){
        super.onDestroy();

        FileSenderListener.getInstance().removeOnPropertyChangedCallback(gcodeFileCallback);
        machineStatus.removeOnPropertyChangedCallback(workPositionRestoreCallback);
        if (zDropExecutor != null) { zDropExecutor.shutdownNow(); zDropExecutor = null; }

        stopService(new Intent(this, FileStreamerIntentService.class));
        ConsoleLoggerListener.resetClass();
        FileSenderListener.resetClass();
        MachineStatusListener.resetClass();
        isAppRunning = false;
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        lastToast = null;
    }

    @SuppressLint("MissingSuperCall")
    @Override
    public void onBackPressed(){ moveTaskToBack(true); }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {

        if(menu != null){
            MenuItem actionGrblSoftReset = menu.findItem(R.id.action_grbl_reset);
            actionGrblSoftReset.setIcon(new IconDrawable(this, FontAwesomeIcons.fa_power_off).colorRes(R.color.colorWhite).sizeDp(24));

        }

        return super.onCreateOptionsMenu(menu);
    }

    @SuppressLint("NonConstantResourceId")
    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();

        switch (id){
            case R.id.app_settings:
                startActivity(new Intent(getApplicationContext(), SettingsActivity.class));
                return true;

            case R.id.app_about:
                startActivity(new Intent(getApplicationContext(), AboutActivity.class));
                return true;

            case R.id.share:
                try {
                    Intent sharingIntent = new Intent(android.content.Intent.ACTION_SEND);
                    sharingIntent.setType("text/plain");
                    sharingIntent.putExtra(android.content.Intent.EXTRA_SUBJECT, getString(R.string.app_name));
                    sharingIntent.putExtra(android.content.Intent.EXTRA_TEXT, getString(R.string.text_share_body));
                    startActivity(Intent.createChooser(sharingIntent, getString(R.string.text_app_share)));
                }catch (ActivityNotFoundException e){
                    showToastMessage(getString(R.string.text_no_app_for_action), true, true);
                }

                return true;

        }

        return super.onOptionsItemSelected(item);
    }

    protected void applicationSetup(){
        NotificationHelper notificationHelper = new NotificationHelper(this);
        notificationHelper.createChannels();

        consoleLogger = ConsoleLoggerListener.getInstance();
        machineStatus = MachineStatusListener.getInstance();
        machineStatus.setJogging(sharedPref.getDouble(getString(R.string.preference_jogging_step_size), 1.00),
                sharedPref.getDouble(getString(R.string.preference_jogging_step_size_z), 0.1),
                sharedPref.getDouble(getString(R.string.preference_jogging_step_size_a), 1.0),
                sharedPref.getDouble(getString(R.string.preference_jogging_feed_rate), 2400.0),
                sharedPref.getBoolean(getString(R.string.preference_jogging_in_inches), false));
        machineStatus.setVerboseOutput(sharedPref.getBoolean(getString(R.string.preference_console_verbose_mode), false));
        machineStatus.setIgnoreError20(sharedPref.getBoolean(getString(R.string.preference_ignore_error_20), false));
        machineStatus.setUsbBaudRate(Integer.parseInt(sharedPref.getString(getString(R.string.usb_serial_baud_rate), Constants.USB_BAUD_RATE)));
        machineStatus.setSingleStepMode(sharedPref.getBoolean(getString(R.string.preference_single_step_mode), false));
        machineStatus.setCustomStartUpString(sharedPref.getString(getString(R.string.preference_start_up_string), ""));

    }

    protected void setupTabLayout(){
        TabLayout tabLayout = findViewById(R.id.tab_layout);

        // Icone identiche su tablet e telefono, cambia solo la dimensione.
        int iconSizeDp = isTablet(this) ? 32 : 21;
        FontAwesomeIcons[] tabIcons = {
                FontAwesomeIcons.fa_arrows_alt,    // Jogging
                FontAwesomeIcons.fa_file_text,     // File sender
                FontAwesomeIcons.fa_crosshairs,    // Probing
                FontAwesomeIcons.fa_television,    // Console
                FontAwesomeIcons.fa_object_group,  // CAM (Computer Aided Manufacturing)
                FontAwesomeIcons.fa_cube,          // Visualizzatore 3D
                FontAwesomeIcons.fa_code,          // Editor GCode
        };
        for (FontAwesomeIcons icon : tabIcons) {
            tabLayout.addTab(tabLayout.newTab().setIcon(
                    new IconDrawable(this, icon).colorRes(R.color.colorAccent).sizeDp(iconSizeDp)));
        }

        tabLayout.setTabGravity(TabLayout.GRAVITY_FILL);

        final ViewPager viewPager = findViewById(R.id.tab_layout_pager);
        tabViewPager = viewPager;
        final GrblFragmentPagerAdapter pagerAdapter = new GrblFragmentPagerAdapter(getSupportFragmentManager(), tabLayout.getTabCount());
        viewPager.setAdapter(pagerAdapter);
        viewPager.addOnPageChangeListener(new TabLayout.TabLayoutOnPageChangeListener(tabLayout));
        viewPager.setPageTransformer(false, new ReaderViewPagerTransformer(ReaderViewPagerTransformer.TransformType.DEPTH));
        viewPager.setOffscreenPageLimit(1);


        tabLayout.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
            @Override
            public void onTabSelected(TabLayout.Tab tab) {
                viewPager.setCurrentItem(tab.getPosition());
            }

            @Override
            public void onTabUnselected(TabLayout.Tab tab) {}
            @Override
            public void onTabReselected(TabLayout.Tab tab) {}
        });
    }

    private void setWorkPosition(final String axisLabel){
        LayoutInflater inflater = LayoutInflater.from(this);
        View v = inflater.inflate(R.layout.dialog_input_decimal_signed, null, false);

        AlertDialog.Builder alertDialogBuilder = new AlertDialog.Builder(this);
        alertDialogBuilder.setView(v);
        alertDialogBuilder.setTitle(getString(R.string.test_set_cordinate_system, axisLabel));
        alertDialogBuilder.setMessage(getString(R.string.test_set_cordinate_system_description, axisLabel));

        final EditText editText = v.findViewById(R.id.dialog_input_decimal_signed);
        if(axisLabel.equalsIgnoreCase("X")) editText.setText(String.valueOf(machineStatus.getWorkPosition().getCordX()));
        if(axisLabel.equalsIgnoreCase("Y")) editText.setText(String.valueOf(machineStatus.getWorkPosition().getCordY()));
        if(axisLabel.equalsIgnoreCase("Z")) editText.setText(String.valueOf(machineStatus.getWorkPosition().getCordZ()));
        if(axisLabel.equalsIgnoreCase("A")) editText.setText(String.valueOf(machineStatus.getWorkPosition().getCordA()));
        editText.setSelection(editText.getText().length());

        alertDialogBuilder.setCancelable(true)
                .setPositiveButton(getString(R.string.text_ok), (dialog, id) -> {
                    String axisValue = editText.getText().toString();
                    if(axisValue.length() > 0){
                        sendCommandIfIdle("G10L20P0" + axisLabel + axisValue);
                    }
                })
                .setNeutralButton("/2",
                        new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface dialog, int id) {

                                Double val = Double.parseDouble( editText.getText().toString());
                                val=val/2;
                                String axisValue=val.toString();
                                if(axisValue.length() > 0){
                                    sendCommandIfIdle("G10L20P0" + axisLabel + axisValue);
                                }
                            }
                        })
                .setNegativeButton(getString(R.string.text_cancel), (dialog, id) -> dialog.cancel());

        AlertDialog dialog = alertDialogBuilder.create();
        if(dialog.getWindow() != null) dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
        dialog.show();
    }

    private void sendCommandIfIdle(String command){
        if(machineStatus.getState().equals(Constants.MACHINE_STATUS_IDLE)){
            onGcodeCommandReceived(command);
        }else{
            showToastMessage(getString(R.string.text_machine_not_idle), true, true);
        }
    }

    protected void showToastMessage(String message){
        this.showToastMessage(message, false, false);
    }

    @SuppressLint("ShowToast")
    protected void showToastMessage(String message, Boolean longToast, Boolean isWarning){
        if(isWarning){
            lastToast = Toasty.warning(this, message, longToast ? Toast.LENGTH_LONG : Toast.LENGTH_SHORT, true);
        }else{
            lastToast = Toasty.success(this, message, longToast ? Toast.LENGTH_LONG : Toast.LENGTH_SHORT);
        }

        lastToast.setGravity(Gravity.FILL_HORIZONTAL|Gravity.TOP, 0, 120);
        lastToast.show();
    }

    @Override
    public void onGcodeCommandReceived(String command) {

    }

    @Override
    public void onGrblRealTimeCommandReceived(byte command) {

    }

    public static boolean isTablet(Context context){
        return (context.getResources().getConfiguration().screenLayout & Configuration.SCREENLAYOUT_SIZE_MASK) >= Configuration.SCREENLAYOUT_SIZE_LARGE;
    }

    private void checkPowerManagement(){
        if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);

            if(pm != null && !pm.isIgnoringBatteryOptimizations(getPackageName())){

                new AlertDialog.Builder(this)
                        .setTitle(getString(R.string.text_power_management_warning_title))
                        .setMessage(getString(R.string.text_power_management_warning_description))
                        .setPositiveButton(getString(R.string.text_settings), (dialog, which) -> {
                            try {
                                Intent myIntent = new Intent();
                                myIntent.setAction(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS);
                                startActivity(myIntent);
                            } catch (RuntimeException ignored) {}
                        })
                        .setNegativeButton(getString(R.string.text_cancel), null)
                        .setCancelable(false)
                        .show();

            }
        }
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onGrblAlarmEvent(GrblAlarmEvent event){
        consoleLogger.offerMessage(event.toString());
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void  onGrblErrorEvent(GrblErrorEvent event){
        consoleLogger.offerMessage(event.toString());
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onConsoleMessageEvent(ConsoleMessageEvent event){
        consoleLogger.offerMessage(event.getMessage());
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onUiToastEvent(UiToastEvent event){
        showToastMessage(event.getMessage(), event.getLongToast(), event.getIsWarning());
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void OnStreamingCompleteEvent(StreamingCompleteEvent event){
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void OnStreamingStartEvent(StreamingStartedEvent event){
        if(sharedPref.getBoolean(getString(R.string.preference_keep_screen_on), false)){
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onOpenGcodeEditorEvent(OpenGcodeEditorEvent event){
        // L'editor GCode è l'ultimo tab: lo apriamo e gli chiediamo di
        // posizionarsi sulla riga richiesta (consumata quando il file è caricato).
        GcodeEditorFragment.requestGotoLine(event.getLine());
        if(tabViewPager != null && tabViewPager.getAdapter() != null){
            tabViewPager.setCurrentItem(tabViewPager.getAdapter().getCount() - 1);
        }
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        Fragment fragment = getSupportFragmentManager().findFragmentByTag("android:switcher:" + R.id.tab_layout_pager + ":" + 1);
        if(fragment != null) fragment.onActivityResult(requestCode, resultCode, data);
    }

    public boolean onKeyDown(int keyCode, KeyEvent event){

        if(keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_VOLUME_MUTE){
            if(machineStatus.getState().equals(Constants.MACHINE_STATUS_RUN)){
                onGrblRealTimeCommandReceived(GrblUtils.GRBL_PAUSE_COMMAND);
                return true;
            }

            if(machineStatus.getState().equals(Constants.MACHINE_STATUS_HOLD)){
                onGrblRealTimeCommandReceived(GrblUtils.GRBL_RESUME_COMMAND);
                return true;
            }
        }

        return false;
    }

    // ----------------------------------------------------------------------
    // Check affondi Z al caricamento file
    // ----------------------------------------------------------------------

    private void runZDropCheckIfEnabled(final File file) {
        if (zDropExecutor == null || zDropExecutor.isShutdown()) return;

        boolean enabled = sharedPref.getBoolean(
                getString(R.string.preference_check_z_drop_enabled), false);
        if (!enabled) return;

        final float depthThresh = parseFloatPref(R.string.preference_check_z_drop_depth, 1.0f);
        final float angleThresh = parseFloatPref(R.string.preference_check_z_drop_angle, 60f);
        final boolean ignoreG0  = sharedPref.getBoolean(
                getString(R.string.preference_check_z_drop_ignore_g0), false);

        zDropExecutor.submit(() -> {
            final List<GcodeDropChecker.DropWarning> warnings =
                    GcodeDropChecker.check(file, depthThresh, angleThresh, ignoreG0);
            runOnUiThread(() -> showZDropDialog(file, warnings, depthThresh, angleThresh));
        });
    }

    private float parseFloatPref(int keyResId, float defaultValue) {
        try {
            String raw = sharedPref.getString(getString(keyResId),
                    String.valueOf(defaultValue));
            if (raw == null || raw.isEmpty()) return defaultValue;
            return Float.parseFloat(raw.replace(',', '.'));
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    /** "1234" per un affondo su singola riga, "1234–1250" per una corsa di discesa. */
    private static String lineRange(GcodeDropChecker.DropWarning w) {
        if (w.lineNumber == w.lineNumberEnd) return String.valueOf(w.lineNumber);
        return w.lineNumber + "–" + w.lineNumberEnd;
    }

    private void showZDropDialog(File file,
                                 List<GcodeDropChecker.DropWarning> warnings,
                                 float depthThresh, float angleThresh) {
        if (isFinishing() || isDestroyed()) return;
        if (warnings == null || warnings.isEmpty()) {
            // Nessun affondo sospetto: feedback discreto, niente dialog
            showToastMessage(getString(R.string.text_z_drop_ok,
                    depthThresh, angleThresh), false, false);
            return;
        }

        // Costruisce il body: lista delle prime 30 discese sospette
        StringBuilder sb = new StringBuilder();
        sb.append(getString(R.string.text_z_drop_thresholds,
                depthThresh, angleThresh, file.getName()));

        // Trova la peggiore (più profonda + più ripida) per il titolo
        GcodeDropChecker.DropWarning worst = warnings.get(0);
        for (GcodeDropChecker.DropWarning w : warnings) {
            if (w.dz < worst.dz) worst = w;
        }
        sb.append(getString(R.string.text_z_drop_worst,
                worst.dz, worst.angleDeg, lineRange(worst)));

        int max = Math.min(30, warnings.size());
        sb.append(getString(R.string.text_z_drop_first_entries, max));
        for (int i = 0; i < max; i++) {
            GcodeDropChecker.DropWarning w = warnings.get(i);
            sb.append(getString(R.string.text_z_drop_row,
                    lineRange(w), w.zBefore, w.zAfter, w.dz, w.angleDeg));
        }
        if (warnings.size() > max) {
            sb.append(getString(R.string.text_z_drop_more, warnings.size() - max));
        }

        TextView body = new TextView(this);
        body.setText(sb.toString());
        body.setTypeface(android.graphics.Typeface.MONOSPACE);
        body.setTextSize(11f);
        body.setPadding(40, 20, 40, 20);
        body.setHorizontallyScrolling(true);

        android.widget.HorizontalScrollView hScroll = new android.widget.HorizontalScrollView(this);
        hScroll.addView(body);
        android.widget.ScrollView vScroll = new android.widget.ScrollView(this);
        vScroll.addView(hScroll);

        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.text_z_drop_title, warnings.size()))
                .setView(vScroll)
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

}

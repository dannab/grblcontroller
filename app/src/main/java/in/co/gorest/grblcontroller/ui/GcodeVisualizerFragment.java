/*
 * Copyright (C) 2013-2018 Will Winder
 * Part of Universal Gcode Sender (UGS)
 * https://github.com/winder/Universal-G-Code-Sender
 *
 * Android porting and modifications Copyright (C) 2026 Daniele Cicchinelli
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

package in.co.gorest.grblcontroller.ui;

import android.opengl.GLSurfaceView;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.databinding.DataBindingUtil;
import androidx.databinding.Observable;

import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

import in.co.gorest.grblcontroller.BR;
import in.co.gorest.grblcontroller.R;
import in.co.gorest.grblcontroller.databinding.FragmentGcodeVisualizerBinding;
import in.co.gorest.grblcontroller.events.BluetoothDisconnectEvent;
import in.co.gorest.grblcontroller.listeners.FileSenderListener;
import in.co.gorest.grblcontroller.listeners.MachineStatusListener;

public class GcodeVisualizerFragment extends BaseFragment {

    private static final String TAG = GcodeVisualizerFragment.class.getSimpleName();

    // -------------------------------------------------------------------------
    // Singleton listeners
    // -------------------------------------------------------------------------
    private MachineStatusListener machineStatus;
    private FileSenderListener fileSender;

    // -------------------------------------------------------------------------
    // Callback per osservare FileSenderListener
    // -------------------------------------------------------------------------
    private final Observable.OnPropertyChangedCallback fileSenderCallback =
            new Observable.OnPropertyChangedCallback() {
                @Override
                public void onPropertyChanged(Observable sender, int propertyId) {

                    // Nuovo file selezionato → carica nel visualizzatore
                    if (propertyId == BR.gcodeFile) {
                        Log.d(TAG, "gcodeFile changed: " + fileSender.getGcodeFile());
                        if (fileSender.getGcodeFile() != null
                                && fileSender.getGcodeFile().exists()) {
                            loadFile(fileSender.getGcodeFile().getAbsolutePath());
                        }
                    }

                    // Riga confermata → aggiorna segmenti grigi e posizione tool
                    if (propertyId == BR.rowsSent) {
                        glSurfaceView.queueEvent(() -> {
                            renderer.setCurrentCommandNumber(fileSender.getRowsSent());
                            renderer.setWorkCoordinate(
                                    machineStatus.getWorkPosition().getCordX(),
                                    machineStatus.getWorkPosition().getCordY(),
                                    machineStatus.getWorkPosition().getCordZ()
                            );
                        });
                        glSurfaceView.requestRender();
                    }
                }
            };

    // -------------------------------------------------------------------------
    // Viste
    // -------------------------------------------------------------------------
    private GLSurfaceView glSurfaceView;
    private ImageButton btnResetView;
    private ImageButton btnZoomFit;

    // -------------------------------------------------------------------------
    // Renderer OpenGL ES
    // -------------------------------------------------------------------------
    private GcodeRenderer renderer;

    // -------------------------------------------------------------------------
    // Gesture
    // -------------------------------------------------------------------------
    private ScaleGestureDetector scaleDetector;
    private float lastTouchX, lastTouchY;

    // -------------------------------------------------------------------------
    // Factory method
    // -------------------------------------------------------------------------
    public GcodeVisualizerFragment() {}

    public static GcodeVisualizerFragment newInstance() {
        return new GcodeVisualizerFragment();
    }

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        machineStatus = MachineStatusListener.getInstance();
        fileSender    = FileSenderListener.getInstance();
    }

    @Override
    public void onStart() {
        super.onStart();
        EventBus.getDefault().register(this);
        fileSender.addOnPropertyChangedCallback(fileSenderCallback);
    }

    @Override
    public void onStop() {
        super.onStop();
        EventBus.getDefault().unregister(this);
        fileSender.removeOnPropertyChangedCallback(fileSenderCallback);
    }

    @Override
    public void onResume() {
        super.onResume();
        if (glSurfaceView != null) glSurfaceView.onResume();
    }

    @Override
    public void onPause() {
        super.onPause();
        if (glSurfaceView != null) glSurfaceView.onPause();
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {

        // DataBinding — identico agli altri fragment del progetto
        FragmentGcodeVisualizerBinding binding = DataBindingUtil.inflate(
                inflater, R.layout.fragment_gcode_visualizer, container, false);
        binding.setMachineStatus(machineStatus);
        binding.setFileSender(fileSender);
        View view = binding.getRoot();

        // --- Viste ---
        glSurfaceView = view.findViewById(R.id.glSurfaceView);
        btnResetView  = view.findViewById(R.id.btnResetView);
        btnZoomFit    = view.findViewById(R.id.btnZoomFit);

        // --- Configura GLSurfaceView ---
        glSurfaceView.setEGLContextClientVersion(2);
        glSurfaceView.setEGLConfigChooser(8, 8, 8, 8, 16, 0);
        glSurfaceView.setPreserveEGLContextOnPause(true);

        renderer = new GcodeRenderer(requireContext());
        glSurfaceView.setRenderer(renderer);
        glSurfaceView.setRenderMode(GLSurfaceView.RENDERMODE_WHEN_DIRTY);

        // --- Gesture ---
        scaleDetector = new ScaleGestureDetector(requireContext(), new PinchZoomListener());
        glSurfaceView.setOnTouchListener(this::onTouch);

        // --- Pulsanti ---
        btnResetView.setOnClickListener(v -> {
            glSurfaceView.queueEvent(() -> renderer.resetView());
            glSurfaceView.requestRender();
        });
        btnZoomFit.setOnClickListener(v -> {
            glSurfaceView.queueEvent(() -> renderer.zoomFit());
            glSurfaceView.requestRender();
        });

        // --- File già caricato in precedenza ---
        if (fileSender.getGcodeFile() != null && fileSender.getGcodeFile().exists()) {
            Log.d(TAG, "File già presente al momento della creazione: "
                    + fileSender.getGcodeFile().getAbsolutePath());
            loadFile(fileSender.getGcodeFile().getAbsolutePath());
        } else {
            Log.d(TAG, "Nessun file presente al momento della creazione");
        }

        return view;
    }

    // -------------------------------------------------------------------------
    // EventBus
    // -------------------------------------------------------------------------

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onBluetoothDisconnectEvent(BluetoothDisconnectEvent event) {
        glSurfaceView.queueEvent(() -> renderer.setCurrentCommandNumber(0));
        glSurfaceView.requestRender();
    }

    // -------------------------------------------------------------------------
    // Caricamento file
    // -------------------------------------------------------------------------

    private void loadFile(String filePath) {
        Log.d(TAG, "loadFile chiamato con: " + filePath);
        glSurfaceView.queueEvent(() -> {
            Log.d(TAG, "queueEvent eseguito, avvio parsing: " + filePath);
            renderer.loadFile(filePath, false);
            glSurfaceView.requestRender();
        });
    }

    // -------------------------------------------------------------------------
    // Touch
    // -------------------------------------------------------------------------

    @SuppressWarnings("ClickableViewAccessibility")
    private boolean onTouch(View v, MotionEvent event) {
        scaleDetector.onTouchEvent(event);

        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_POINTER_DOWN:
                lastTouchX = event.getX(0);
                lastTouchY = event.getY(0);
                break;

            case MotionEvent.ACTION_MOVE:
                if (scaleDetector.isInProgress()) break;

                float dx = event.getX(0) - lastTouchX;
                float dy = event.getY(0) - lastTouchY;

                if (event.getPointerCount() >= 2) {
                    final float fdx = dx, fdy = dy;
                    glSurfaceView.queueEvent(() -> renderer.pan(fdx, fdy));
                } else {
                    final float rx = dx * 0.5f, ry = dy * 0.5f;
                    glSurfaceView.queueEvent(() -> renderer.rotate(rx, ry));
                }

                glSurfaceView.requestRender();
                lastTouchX = event.getX(0);
                lastTouchY = event.getY(0);
                break;
        }
        return true;
    }

    private class PinchZoomListener extends ScaleGestureDetector.SimpleOnScaleGestureListener {
        @Override
        public boolean onScale(ScaleGestureDetector detector) {
            float factor = detector.getScaleFactor();
            glSurfaceView.queueEvent(() -> renderer.zoom(factor));
            glSurfaceView.requestRender();
            return true;
        }
    }
}

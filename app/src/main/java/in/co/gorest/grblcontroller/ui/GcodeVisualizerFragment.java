/*
 * Copyright (C) 2013-2018 Will Winder
 * Part of Universal Gcode Sender (UGS)
 * https://github.com/winder/Universal-G-Code-Sender
 *
 * Android porting and modifications Copyright (C) 2024-2026 Daniele Cicchinelli
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
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageButton;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.databinding.DataBindingUtil;
import androidx.databinding.Observable;

import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

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
    private Button btnZoomIn;
    private Button btnZoomOut;

    // -------------------------------------------------------------------------
    // Renderer OpenGL ES
    // -------------------------------------------------------------------------
    private GcodeRenderer renderer;

    /** Modello parsato dell'ultimo file caricato. Vive nel FRAGMENT (non
     *  nella view): quando il ViewPager distrugge e ricrea la view, il
     *  renderer nuovo riceve questo modello senza dover ri-parsare. */
    private volatile GcodeRenderer.ParsedModel cachedModel;
    /** Path del file a cui si riferisce cachedModel (o il parse in corso). */
    private String cachedModelPath;
    /** Impronta (lastModified + dimensione) del file al momento del parse:
     *  l'import SAF e il CAM sovrascrivono lo STESSO percorso, quindi il solo
     *  path non basta a capire se il contenuto e cambiato. */
    private long cachedModelStamp;
    /** true se l'ISTANZA CORRENTE di renderer ha già ricevuto il modello.
     *  Va azzerato a ogni onCreateView (renderer nuovo = scena vuota). */
    private volatile boolean rendererHasModel;

    /** Esegue il parsing GCode fuori dal GL thread, così la vista 3D non si
     *  congela durante il load di file grandi. Single-thread: i load sono
     *  serializzati. */
    private ExecutorService parseExecutor;

    /** Generazione del load: un parse terminato viene scartato se nel
     *  frattempo è stato richiesto un file più recente. */
    private final AtomicInteger loadGeneration = new AtomicInteger();

    // -------------------------------------------------------------------------
    // Gesture: rotazione a 1 dito.
    // Pinch zoom e pan a 2 dita rimossi su richiesta dell'utente (il pan a 2
    // dita interferiva con lo swipe del ViewPager tra tab). Zoom ora gestito
    // dai pulsanti +/- in alto. Pan rimosso del tutto: per cambiare la zona
    // visibile si usa zoom fit e reset vista.
    // -------------------------------------------------------------------------
    private float lastTouchX, lastTouchY;

    /** Fattore di zoom applicato a ogni click del pulsante "+". */
    private static final float ZOOM_STEP_IN = 1.25f;
    /** Fattore di zoom applicato a ogni click del pulsante "-". Inverso esatto di IN. */
    private static final float ZOOM_STEP_OUT = 1.0f / ZOOM_STEP_IN;

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
        parseExecutor = Executors.newSingleThreadExecutor();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (parseExecutor != null) parseExecutor.shutdownNow();
    }

    @Override
    public void onStart() {
        super.onStart();
        EventBus.getDefault().register(this);
        // NOTA: registrazione di fileSenderCallback spostata in onResume per
        // evitare il parse pesante quando il fragment è solo pre-caricato dal
        // ViewPager come tab adiacente (es. sei su CAM e il visualizer si
        // mette a parsare 256k righe senza che tu lo veda).
    }

    @Override
    public void onStop() {
        super.onStop();
        EventBus.getDefault().unregister(this);
    }

    @Override
    public void onResume() {
        super.onResume();
        if (glSurfaceView != null) glSurfaceView.onResume();
        fileSender.addOnPropertyChangedCallback(fileSenderCallback);

        // Auto-load del file già selezionato: lo facciamo qui (non in
        // onCreateView) perché con BEHAVIOR_RESUME_ONLY_CURRENT_FRAGMENT
        // questo viene chiamato solo quando il tab è davvero visibile.
        // Tre casi:
        //  - file nuovo → parse in background;
        //  - stesso file ma renderer ricreato dal ViewPager → riusa il
        //    modello in cache, niente re-parse;
        //  - stesso file e renderer già popolato → niente da fare.
        if (fileSender.getGcodeFile() != null && fileSender.getGcodeFile().exists()) {
            java.io.File f = fileSender.getGcodeFile();
            String path = f.getAbsolutePath();
            if (!path.equals(cachedModelPath) || fileStamp(f) != cachedModelStamp) {
                Log.d(TAG, "onResume: carico file " + path);
                loadFile(path);
            } else if (!rendererHasModel && cachedModel != null) {
                Log.d(TAG, "onResume: renderer nuovo, riuso modello in cache");
                applyModelToRenderer(cachedModel);
            }
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        if (glSurfaceView != null) glSurfaceView.onPause();
        fileSender.removeOnPropertyChangedCallback(fileSenderCallback);
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
        btnZoomIn     = view.findViewById(R.id.btnZoomIn);
        btnZoomOut    = view.findViewById(R.id.btnZoomOut);

        // --- Configura GLSurfaceView ---
        glSurfaceView.setEGLContextClientVersion(2);
        glSurfaceView.setEGLConfigChooser(8, 8, 8, 8, 16, 0);
        glSurfaceView.setPreserveEGLContextOnPause(true);

        renderer = new GcodeRenderer();
        // Renderer appena creato = scena vuota: se c'è un modello in cache
        // verrà riapplicato da onResume (vedi rendererHasModel).
        rendererHasModel = false;
        glSurfaceView.setRenderer(renderer);
        glSurfaceView.setRenderMode(GLSurfaceView.RENDERMODE_WHEN_DIRTY);

        // --- Gesture: solo rotazione a 1 dito ---
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
        btnZoomIn.setOnClickListener(v -> {
            glSurfaceView.queueEvent(() -> renderer.zoom(ZOOM_STEP_IN));
            glSurfaceView.requestRender();
        });
        btnZoomOut.setOnClickListener(v -> {
            glSurfaceView.queueEvent(() -> renderer.zoom(ZOOM_STEP_OUT));
            glSurfaceView.requestRender();
        });

        // --- File già caricato in precedenza ---
        // Spostato in onResume per evitare il parse pesante quando il fragment
        // viene solo pre-creato dal ViewPager (vicino al tab corrente) senza
        // essere effettivamente visibile.

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

    /** Impronta del contenuto del file: cambia se il file viene sovrascritto. */
    private static long fileStamp(java.io.File f) {
        return f.lastModified() + 31L * f.length();
    }

    private void loadFile(String filePath) {
        Log.d(TAG, "loadFile chiamato con: " + filePath);
        cachedModelPath = filePath;
        cachedModelStamp = fileStamp(new java.io.File(filePath));
        cachedModel = null;

        // Parsing su thread background: il GL thread riceve solo i buffer
        // già pronti, quindi la vista resta fluida anche con file enormi.
        final int generation = loadGeneration.incrementAndGet();
        parseExecutor.execute(() -> {
            Log.d(TAG, "parsing in background: " + filePath);
            GcodeRenderer.ParsedModel model = GcodeRenderer.parseFile(filePath);
            if (model == null) {
                Log.w(TAG, "parsing fallito o file vuoto: " + filePath);
                return;
            }
            if (generation != loadGeneration.get()) {
                Log.d(TAG, "parse scartato, richiesto file più recente");
                return;
            }
            // Il modello resta in cache nel fragment: se il ViewPager ricrea
            // la view, il renderer nuovo lo riceve senza ri-parsare.
            cachedModel = model;
            applyModelToRenderer(model);
        });
    }

    /**
     * Consegna un modello parsato al renderer corrente sul GL thread,
     * ripristinando anche il progresso del gray-out (righe già inviate).
     * Chiamabile sia dal main thread (onResume) sia dal thread di parsing.
     */
    private void applyModelToRenderer(GcodeRenderer.ParsedModel model) {
        final GLSurfaceView surface = glSurfaceView;
        final GcodeRenderer targetRenderer = renderer;
        if (surface == null || targetRenderer == null) return;
        rendererHasModel = true;
        surface.queueEvent(() -> {
            targetRenderer.setModel(model);
            targetRenderer.setCurrentCommandNumber(fileSender.getRowsSent());
        });
        surface.requestRender();
    }

    // -------------------------------------------------------------------------
    // Touch
    // -------------------------------------------------------------------------

    @SuppressWarnings("ClickableViewAccessibility")
    private boolean onTouch(View v, MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                // Chiediamo al parent (ViewPager) di NON intercettare il
                // movimento orizzontale finché stiamo manipolando la vista 3D,
                // altrimenti uno swipe laterale farebbe cambiare tab invece di
                // ruotare il modello.
                if (v.getParent() != null) {
                    v.getParent().requestDisallowInterceptTouchEvent(true);
                }
                lastTouchX = event.getX(0);
                lastTouchY = event.getY(0);
                break;

            case MotionEvent.ACTION_MOVE:
                // Solo rotazione a 1 dito. Touch multipli vengono ignorati:
                // niente più pinch zoom (sostituito dai pulsanti +/-) né
                // pan a 2 dita (rimosso per non interferire con il ViewPager).
                if (event.getPointerCount() != 1) break;

                float dx = event.getX(0) - lastTouchX;
                float dy = event.getY(0) - lastTouchY;

                final float rx = dx * 0.5f, ry = dy * 0.5f;
                glSurfaceView.queueEvent(() -> renderer.rotate(rx, ry));
                glSurfaceView.requestRender();

                lastTouchX = event.getX(0);
                lastTouchY = event.getY(0);
                break;

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                // Tocco finito: il ViewPager può tornare libero di intercettare
                // gli swipe per cambiare tab.
                if (v.getParent() != null) {
                    v.getParent().requestDisallowInterceptTouchEvent(false);
                }
                break;
        }
        return true;
    }
}

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


import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.opengl.Matrix;
import android.util.Log;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.Arrays;
import java.util.Locale;

public class GcodeRenderer implements GLSurfaceView.Renderer {

    private static final String TAG = "GcodeRenderer";

    // -------------------------------------------------------------------------
    // GLSL Shader sources (inline — nessun file .glsl esterno necessario)
    // -------------------------------------------------------------------------

    /**
     * Vertex shader: riceve posizione (vec3), colore (vec3) e numero di riga
     * GCode (float). Il gray-out delle righe già eseguite avviene QUI, in GPU:
     * aggiornare il progresso costa un singolo glUniform1f invece di
     * ricostruire e ricaricare l'intero color buffer a ogni notifica.
     * aLineNumber = 0 (assi, tool) non viene mai ingrigito.
     */
    private static final String VERTEX_SHADER_SRC =
            "uniform mat4 uMVPMatrix;\n" +
                    "uniform float uCurrentLine;\n" +
                    "attribute vec3 aPosition;\n" +
                    "attribute vec3 aColor;\n" +
                    "attribute float aLineNumber;\n" +
                    "varying vec3 vColor;\n" +
                    "void main() {\n" +
                    "    gl_Position = uMVPMatrix * vec4(aPosition, 1.0);\n" +
                    "    if (aLineNumber > 0.5 && aLineNumber <= uCurrentLine) {\n" +
                    "        vColor = vec3(0.4, 0.4, 0.4);\n" +
                    "    } else {\n" +
                    "        vColor = aColor;\n" +
                    "    }\n" +
                    "}\n";

    /**
     * Fragment shader: disegna il pixel con il colore interpolato.
     */
    private static final String FRAGMENT_SHADER_SRC =
            "precision mediump float;\n" +
                    "varying vec3 vColor;\n" +
                    "void main() {\n" +
                    "    gl_FragColor = vec4(vColor, 1.0);\n" +
                    "}\n";

    // -------------------------------------------------------------------------
    // Colori (stesso schema di VisualizerUtils.Color originale)
    // -------------------------------------------------------------------------
    private static final float[] COLOR_WHITE  = {1.0f, 1.0f, 1.0f};
    private static final float[] COLOR_RED    = {1.0f, 0.0f, 0.0f};
    private static final float[] COLOR_BLUE   = {0.0f, 0.4f, 1.0f};
    private static final float[] COLOR_GREEN  = {0.0f, 1.0f, 0.0f};
    private static final float[] COLOR_YELLOW = {1.0f, 1.0f, 0.0f};

    // -------------------------------------------------------------------------
    // Modello parsato (immutabile, prodotto da parseFile su thread background)
    // -------------------------------------------------------------------------

    /**
     * Risultato del parsing di un file GCode: buffer pronti per la GPU
     * più i metadati di scena (bounding box, primo punto).
     * I FloatBuffer direct vengono allocati sul thread di parsing, così il
     * GL thread deve solo fare glBufferData.
     */
    public static final class ParsedModel {
        final FloatBuffer vertexBuffer;
        final FloatBuffer colorBuffer;
        final FloatBuffer lineNumberBuffer;
        final int vertexCount;
        final int segmentCount;
        final float minX, maxX, minY, maxY, minZ, maxZ;
        final float centerX, centerY, centerZ;
        final float maxSide;
        final float firstX, firstY, firstZ;

        private ParsedModel(ModelBuilder b, float firstX, float firstY, float firstZ) {
            this.vertexBuffer     = b.verts.toDirectBuffer();
            this.colorBuffer      = b.colors.toDirectBuffer();
            this.lineNumberBuffer = b.lineNums.toDirectBuffer();
            this.vertexCount      = b.lineNums.size();
            this.segmentCount     = b.lineNums.size() / 2;
            this.minX = b.minX; this.maxX = b.maxX;
            this.minY = b.minY; this.maxY = b.maxY;
            this.minZ = b.minZ; this.maxZ = b.maxZ;
            this.centerX = (b.minX + b.maxX) / 2f;
            this.centerY = (b.minY + b.maxY) / 2f;
            this.centerZ = (b.minZ + b.maxZ) / 2f;
            float dx = b.maxX - b.minX;
            float dy = b.maxY - b.minY;
            float dz = b.maxZ - b.minZ;
            float side = Math.max(dx, Math.max(dy, dz));
            this.maxSide = (side == 0) ? 1f : side;
            this.firstX = firstX;
            this.firstY = firstY;
            this.firstZ = firstZ;
        }
    }

    /** Array float crescente, senza boxing né oggetti per segmento. */
    private static final class FloatList {
        private float[] data = new float[4096];
        private int size = 0;

        void add(float v) {
            if (size == data.length) data = Arrays.copyOf(data, data.length * 2);
            data[size++] = v;
        }

        int size() { return size; }

        FloatBuffer toDirectBuffer() {
            ByteBuffer bb = ByteBuffer.allocateDirect(size * 4);
            bb.order(ByteOrder.nativeOrder());
            FloatBuffer fb = bb.asFloatBuffer();
            fb.put(data, 0, size);
            fb.position(0);
            return fb;
        }
    }

    /** Stato di costruzione del modello durante il parsing. */
    private static final class ModelBuilder {
        final FloatList verts    = new FloatList();
        final FloatList colors   = new FloatList();
        final FloatList lineNums = new FloatList();
        float minX = Float.MAX_VALUE, maxX = -Float.MAX_VALUE;
        float minY = Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
        float minZ = Float.MAX_VALUE, maxZ = -Float.MAX_VALUE;

        void updateExtremes(float x, float y, float z) {
            minX = Math.min(minX, x); maxX = Math.max(maxX, x);
            minY = Math.min(minY, y); maxY = Math.max(maxY, y);
            minZ = Math.min(minZ, z); maxZ = Math.max(maxZ, z);
        }

        void addSegment(float x1, float y1, float z1,
                        float x2, float y2, float z2,
                        float[] color, int lineNum) {
            verts.add(x1); verts.add(y1); verts.add(z1);
            verts.add(x2); verts.add(y2); verts.add(z2);
            for (int i = 0; i < 2; i++) {
                colors.add(color[0]); colors.add(color[1]); colors.add(color[2]);
                lineNums.add(lineNum);
            }
            // Entrambi gli estremi: il punto di partenza può non coincidere
            // con la fine del segmento precedente (es. riposizionamenti via
            // comandi non-motion che aggiornano solo lo stato del parser).
            updateExtremes(x1, y1, z1);
            updateExtremes(x2, y2, z2);
        }

        int segmentCount() { return lineNums.size() / 2; }
    }

    // -------------------------------------------------------------------------
    // Stato scena (solo GL thread)
    // -------------------------------------------------------------------------

    private FloatBuffer vertexBuffer;
    private FloatBuffer colorBuffer;
    private FloatBuffer lineNumberBuffer;
    private int vertexCount = 0;

    /** Riga GCode corrente: i vertici con lineNumber <= a questo valore
     *  vengono ingrigiti dallo shader. */
    private float currentCommandNumber = 0;

    // Estremi dell'oggetto (per calcolo centro e scala)
    private float centerX, centerY, centerZ;
    private float maxSide = 1.0f;

    // Primo punto reale del file GCode: posizione dove inizia il programma.
    // Lo usiamo come origine logica degli assi XYZ disegnati nella scena.
    // Se nessun file è caricato, gli assi vengono disegnati in (0,0,0).
    private float firstX = 0f, firstY = 0f, firstZ = 0f;
    private boolean hasFirstPoint = false;

    // -------------------------------------------------------------------------
    // Coordinate tool
    // -------------------------------------------------------------------------
    private double workX, workY, workZ;

    // -------------------------------------------------------------------------
    // Matrici OpenGL ES
    // -------------------------------------------------------------------------
    private final float[] projMatrix  = new float[16];
    private final float[] viewMatrix  = new float[16];
    private final float[] modelMatrix = new float[16];
    private final float[] mvpMatrix   = new float[16];
    private final float[] tempMatrix  = new float[16];

    // -------------------------------------------------------------------------
    // Vista: rotazione, pan, zoom
    // (corrispondono a rotation, eye, zoomMultiplier del VisualizerCanvas)
    // -------------------------------------------------------------------------
    private float rotationX   = 0.0f;   // gradi attorno all'asse Y
    private float rotationY   = -30.0f; // gradi attorno all'asse X
    private float panX        = 0.0f;
    private float panY        = 0.0f;
    private float zoomLevel   = 1.0f;
    private float scaleBase   = 1.0f;

    private int viewportW = 1, viewportH = 1;

    // -------------------------------------------------------------------------
    // OpenGL handles
    // -------------------------------------------------------------------------
    private int shaderProgram   = 0;
    private int attrPosition    = -1;
    private int attrColor       = -1;
    private int attrLineNumber  = -1;
    private int unifMVPMatrix   = -1;
    private int unifCurrentLine = -1;

    // VBO handles
    private final int[] vboHandles = new int[3]; // [0]=vertex, [1]=color, [2]=lineNumber
    private boolean vboDirty = true;

    // -------------------------------------------------------------------------
    // Buffer cachati per assi e tool: niente allocazioni per-frame.
    // -------------------------------------------------------------------------
    private FloatBuffer axisVertexBuffer;
    private FloatBuffer axisColorBuffer;
    private final FloatBuffer toolVertexBuffer;
    private final FloatBuffer toolColorBuffer;

    public GcodeRenderer() {
        toolVertexBuffer = makeFloatBuffer(new float[6]);
        toolColorBuffer = makeFloatBuffer(new float[]{
                COLOR_YELLOW[0], COLOR_YELLOW[1], COLOR_YELLOW[2],
                COLOR_YELLOW[0], COLOR_YELLOW[1], COLOR_YELLOW[2]
        });
        rebuildAxisBuffers();
    }

    // =========================================================================
    // GLSurfaceView.Renderer — equivalenti di init/reshape/display JOGL
    // =========================================================================

    /**
     * Corrisponde a init() di JOGL.
     * Chiamato una volta sola quando il contesto GL è pronto.
     */
    @Override
    public void onSurfaceCreated(GL10 unused, EGLConfig config) {
        GLES20.glClearColor(0f, 0f, 0f, 1f);
        GLES20.glEnable(GLES20.GL_DEPTH_TEST);
        GLES20.glDepthFunc(GLES20.GL_LEQUAL);

        shaderProgram = buildShaderProgram(VERTEX_SHADER_SRC, FRAGMENT_SHADER_SRC);

        attrPosition    = GLES20.glGetAttribLocation(shaderProgram, "aPosition");
        attrColor       = GLES20.glGetAttribLocation(shaderProgram, "aColor");
        attrLineNumber  = GLES20.glGetAttribLocation(shaderProgram, "aLineNumber");
        unifMVPMatrix   = GLES20.glGetUniformLocation(shaderProgram, "uMVPMatrix");
        unifCurrentLine = GLES20.glGetUniformLocation(shaderProgram, "uCurrentLine");

        GLES20.glGenBuffers(3, vboHandles, 0);

        // Se c'era già un modello caricato prima che la superficie fosse
        // pronta (o il contesto GL è stato ricreato), ricarichiamo i VBO.
        if (vertexCount > 0) {
            vboDirty = true;
        }
    }

    /**
     * Corrisponde a reshape() di JOGL.
     * Chiamato quando le dimensioni della superficie cambiano.
     */
    @Override
    public void onSurfaceChanged(GL10 unused, int width, int height) {
        if (height == 0) height = 1;
        viewportW = width;
        viewportH = height;
        GLES20.glViewport(0, 0, width, height);

        float aspect = (float) width / height;

        // Proiezione ortografica (come nel VisualizerCanvas originale con ortho=true)
        // glOrtho(-0.51*aspect, 0.51*aspect, -0.51, 0.51, -10, 10)
        Matrix.orthoM(projMatrix, 0,
                -0.51f * aspect, 0.51f * aspect,
                -0.51f, 0.51f,
                -10f, 10f);

        recalcScale();
    }

    /**
     * Corrisponde a display() di JOGL.
     * Chiamato ogni volta che GLSurfaceView vuole un frame.
     */
    @Override
    public void onDrawFrame(GL10 unused) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT | GLES20.GL_DEPTH_BUFFER_BIT);

        // --- Aggiorna VBO se necessario (solo dopo un nuovo load) ---
        if (vboDirty && vertexCount > 0) {
            uploadVBOs();
            vboDirty = false;
        }

        // --- Costruisce la matrice MVP ---
        // View: camera fissa, oggetto traslato/ruotato (come in VisualizerCanvas)
        Matrix.setLookAtM(viewMatrix, 0,
                0, 0, 1.5f,   // eye
                0, 0, 0,      // center
                0, 1, 0);     // up

        // Model: scala + rotazione + traslazione
        Matrix.setIdentityM(modelMatrix, 0);
        float scale = scaleBase * zoomLevel;
        Matrix.scaleM(modelMatrix, 0, scale, scale, scale);
        Matrix.rotateM(modelMatrix, 0, rotationX, 0, 1, 0);
        Matrix.rotateM(modelMatrix, 0, rotationY, 1, 0, 0);
        Matrix.translateM(modelMatrix, 0,
                -centerX + panX / scale,
                -centerY + panY / scale,
                -centerZ);

        // MVP = Proj * View * Model
        Matrix.multiplyMM(tempMatrix, 0, viewMatrix, 0, modelMatrix, 0);
        Matrix.multiplyMM(mvpMatrix,  0, projMatrix, 0, tempMatrix,  0);

        GLES20.glUseProgram(shaderProgram);
        GLES20.glUniformMatrix4fv(unifMVPMatrix, 1, false, mvpMatrix, 0);

        // --- Disegna assi XYZ nell'origine (sempre visibili) ---
        drawAxes();

        // --- Disegna percorso GCode ---
        drawLines();

        // --- Disegna tool (come renderTool in VisualizerCanvas) ---
        drawTool();
    }

    // =========================================================================
    // Rendering interno
    // =========================================================================

    /**
     * Disegna tutte le linee GCode usando i VBO.
     * Corrisponde a renderModel() del VisualizerCanvas.
     * Il gray-out del percorso già eseguito è fatto dallo shader tramite
     * uCurrentLine: nessun rebuild di buffer durante lo streaming.
     */
    private void drawLines() {
        if (vertexCount == 0) return;

        GLES20.glUniform1f(unifCurrentLine, currentCommandNumber);

        // Vertex buffer
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vboHandles[0]);
        GLES20.glEnableVertexAttribArray(attrPosition);
        GLES20.glVertexAttribPointer(attrPosition, 3, GLES20.GL_FLOAT, false, 0, 0);

        // Color buffer
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vboHandles[1]);
        GLES20.glEnableVertexAttribArray(attrColor);
        GLES20.glVertexAttribPointer(attrColor, 3, GLES20.GL_FLOAT, false, 0, 0);

        // Line number buffer (per il gray-out in shader)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vboHandles[2]);
        GLES20.glEnableVertexAttribArray(attrLineNumber);
        GLES20.glVertexAttribPointer(attrLineNumber, 1, GLES20.GL_FLOAT, false, 0, 0);

        GLES20.glLineWidth(3.0f);
        GLES20.glDrawArrays(GLES20.GL_LINES, 0, vertexCount);

        GLES20.glDisableVertexAttribArray(attrPosition);
        GLES20.glDisableVertexAttribArray(attrColor);
        GLES20.glDisableVertexAttribArray(attrLineNumber);
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0);
    }

    /**
     * Disegna il cursore tool nella posizione corrente di lavoro.
     * Corrisponde a renderTool() del VisualizerCanvas.
     * Riusa sempre lo stesso FloatBuffer: zero allocazioni per frame.
     */
    private void drawTool() {
        float toolHeight = 0.05f / (scaleBase * zoomLevel); // altezza in coordinate oggetto

        toolVertexBuffer.clear();
        toolVertexBuffer.put((float) workX).put((float) workY).put((float) workZ);
        toolVertexBuffer.put((float) workX).put((float) workY).put((float) (workZ + toolHeight));
        toolVertexBuffer.position(0);

        GLES20.glUniform1f(unifCurrentLine, 0f);
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0); // usa client array per il tool

        GLES20.glEnableVertexAttribArray(attrPosition);
        GLES20.glVertexAttribPointer(attrPosition, 3, GLES20.GL_FLOAT, false, 0, toolVertexBuffer);

        GLES20.glEnableVertexAttribArray(attrColor);
        GLES20.glVertexAttribPointer(attrColor, 3, GLES20.GL_FLOAT, false, 0, toolColorBuffer);

        GLES20.glDisableVertexAttribArray(attrLineNumber);
        GLES20.glVertexAttrib1f(attrLineNumber, 0f);

        GLES20.glLineWidth(4.0f); // nota: su ES 2.0 il line width max può essere limitato dall'hardware
        GLES20.glDrawArrays(GLES20.GL_LINES, 0, 2);

        GLES20.glDisableVertexAttribArray(attrPosition);
        GLES20.glDisableVertexAttribArray(attrColor);
    }

    /**
     * Disegna i tre assi XYZ piazzati sul PRIMO PUNTO del file GCode.
     * X = rosso, Y = verde, Z = blu.
     *
     * Razionale: gli assi rappresentano l'origine logica del job, che è
     * dove il programma inizia il primo movimento — NON la work position
     * corrente del tool (che si muove durante l'esecuzione) e NON l'origine
     * (0,0,0) macchina (che spesso è altrove rispetto al pezzo).
     * Se nessun file è caricato, fallback su (0,0,0).
     *
     * I buffer sono cachati e ricostruiti solo quando cambia il modello.
     */
    private void drawAxes() {
        GLES20.glUniform1f(unifCurrentLine, 0f);
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0);

        GLES20.glEnableVertexAttribArray(attrPosition);
        GLES20.glVertexAttribPointer(attrPosition, 3, GLES20.GL_FLOAT, false, 0, axisVertexBuffer);

        GLES20.glEnableVertexAttribArray(attrColor);
        GLES20.glVertexAttribPointer(attrColor, 3, GLES20.GL_FLOAT, false, 0, axisColorBuffer);

        GLES20.glDisableVertexAttribArray(attrLineNumber);
        GLES20.glVertexAttrib1f(attrLineNumber, 0f);

        GLES20.glLineWidth(3.0f);
        GLES20.glDrawArrays(GLES20.GL_LINES, 0, 6); // 3 assi × 2 vertici

        GLES20.glDisableVertexAttribArray(attrPosition);
        GLES20.glDisableVertexAttribArray(attrColor);
    }

    /** Ricostruisce i buffer degli assi (al load del modello, non per frame). */
    private void rebuildAxisBuffers() {
        // Lunghezza asse: proporzionale all'oggetto caricato, minimo visibile
        float axisLen = hasFirstPoint ? maxSide * 0.15f : 0.05f;
        if (axisLen == 0) axisLen = 0.05f;

        // Origine = primo punto del file GCode
        float ox = hasFirstPoint ? firstX : 0f;
        float oy = hasFirstPoint ? firstY : 0f;
        float oz = hasFirstPoint ? firstZ : 0f;

        float[] av = {
                // Asse X — rosso
                ox, oy, oz,   ox + axisLen, oy, oz,
                // Asse Y — verde
                ox, oy, oz,   ox, oy + axisLen, oz,
                // Asse Z — blu
                ox, oy, oz,   ox, oy, oz + axisLen
        };

        float[] ac = {
                // X rosso
                1f, 0f, 0f,   1f, 0f, 0f,
                // Y verde
                0f, 1f, 0f,   0f, 1f, 0f,
                // Z blu
                0f, 0f, 1f,   0f, 0f, 1f
        };

        axisVertexBuffer = makeFloatBuffer(av);
        axisColorBuffer  = makeFloatBuffer(ac);
    }

    // =========================================================================
    // Caricamento modello
    // =========================================================================

    /**
     * Installa un modello parsato nella scena.
     * DEVE essere chiamato dal thread GL (via queueEvent). Il parsing vero
     * avviene altrove (thread background) tramite {@link #parseFile}.
     */
    public void setModel(ParsedModel model) {
        vertexBuffer     = model.vertexBuffer;
        colorBuffer      = model.colorBuffer;
        lineNumberBuffer = model.lineNumberBuffer;
        vertexCount      = model.vertexCount;

        centerX = model.centerX;
        centerY = model.centerY;
        centerZ = model.centerZ;
        maxSide = model.maxSide;

        firstX = model.firstX;
        firstY = model.firstY;
        firstZ = model.firstZ;
        hasFirstPoint = true;

        // Nuovo file: nessuna riga ancora eseguita.
        currentCommandNumber = 0;

        recalcScale();
        rebuildAxisBuffers();
        vboDirty = true;

        Log.i(TAG, String.format(Locale.US,
                "GCode caricato: %d segmenti, X(%.2f,%.2f) Y(%.2f,%.2f) Z(%.2f,%.2f)",
                model.segmentCount,
                model.minX, model.maxX, model.minY, model.maxY, model.minZ, model.maxZ));
    }

    // =========================================================================
    // Parsing file GCode — statico, chiamabile da qualunque thread
    // =========================================================================

    /**
     * Carica e parsa un file GCode producendo buffer pronti per la GPU.
     * Pensato per girare su un thread di BACKGROUND: non tocca lo stato del
     * renderer. Il risultato va consegnato al GL thread con
     * {@code glSurfaceView.queueEvent(() -> renderer.setModel(model))}.
     *
     * @return il modello parsato, o null se il file è illeggibile o non
     *         contiene segmenti disegnabili.
     */
    public static ParsedModel parseFile(String filePath) {
        ModelBuilder b = new ModelBuilder();

        // Primo punto reale del tracciato (origine logica degli assi).
        float firstPosX = 0f, firstPosY = 0f, firstPosZ = 0f;

        try (BufferedReader reader = new BufferedReader(new FileReader(filePath))) {

            // --- Parser GCode minimale ---
            // Questo parser semplificato gestisce G0/G1/G2/G3.
            // Per un parser completo, integrare la libreria GcodeParser
            // già presente in UGS o una equivalente Android.

            String raw;
            float x = 0, y = 0, z = 0;
            boolean isAbsolute = true;
            int lineNum = 0;

            // Flag che diventa true quando ABBIAMO STABILITO completamente la
            // posizione del tool, cioè X e Y sono stati specificati nel file
            // almeno una volta. Solo da quel momento in poi possiamo disegnare
            // segmenti reali: tutto ciò che viene prima non corrisponde a un
            // movimento descritto dal file (è un'eredità del default 0,0,0 del
            // parser, non una posizione realmente nota).
            // Z resta facoltativo: file 2D senza Z usano Z=0 di default.
            boolean xSpecified = false;
            boolean ySpecified = false;
            boolean firstPositionSet = false;

            // Stato modale: persiste tra le righe come da spec GCode
            int modalMotion = 0; // 0=G0 rapid, 1=G1 linear, 2=G2 arc CW, 3=G3 arc CCW
            int plane       = 17; // 17=XY (default), 18=XZ, 19=YZ — piano per archi G2/G3

            while ((raw = reader.readLine()) != null) {
                String line = stripComments(raw);
                if (line.isEmpty()) continue;

                // Conta SOLO le righe che anche lo streamer conta come inviate
                // (non vuote dopo rimozione commenti e %): così lineNum resta
                // allineato a rowsSent e il gray-out non deriva.
                lineNum++;

                // Detect comandi G con word boundary corretto
                // containsGCode evita falsi positivi (G1 vs G10, G2 vs G20 ecc.)
                boolean hasG0  = containsGCode(line, 0);
                boolean hasG1  = containsGCode(line, 1);
                boolean hasG2  = containsGCode(line, 2);
                boolean hasG3  = containsGCode(line, 3);
                boolean hasG17 = containsGCode(line, 17);
                boolean hasG18 = containsGCode(line, 18);
                boolean hasG19 = containsGCode(line, 19);
                boolean hasG90 = containsGCode(line, 90);
                boolean hasG91 = containsGCode(line, 91);

                if (hasG90) isAbsolute = true;
                if (hasG91) isAbsolute = false;
                if (hasG17) plane = 17;
                if (hasG18) plane = 18;
                if (hasG19) plane = 19;

                // Aggiorna stato modale
                if      (hasG0) modalMotion = 0;
                else if (hasG1) modalMotion = 1;
                else if (hasG2) modalMotion = 2;
                else if (hasG3) modalMotion = 3;

                // Leggi coordinate presenti nella riga
                boolean xInLine = line.indexOf('X') >= 0;
                boolean yInLine = line.indexOf('Y') >= 0;
                float nx = parseCoord(line, 'X', x);
                float ny = parseCoord(line, 'Y', y);
                float nz = parseCoord(line, 'Z', z);

                if (!isAbsolute) { nx += x; ny += y; nz += z; }

                // Una coordinata si considera "specificata dal file" solo
                // quando la lettera corrispondente appare effettivamente
                // nella riga (in modalità assoluta) o tramite incremento
                // (in modalità relativa, sempre via lettera presente).
                if (xInLine) xSpecified = true;
                if (yInLine) ySpecified = true;

                // Parametri arco: I/J/K = offset incrementale dal punto
                // iniziale verso il centro dell'arco. Se assenti default 0
                // (standard GCode). Servono solo per G2/G3.
                float iVal = parseCoord(line, 'I', 0f);
                float jVal = parseCoord(line, 'J', 0f);
                float kVal = parseCoord(line, 'K', 0f);
                boolean hasIJK = line.indexOf('I') >= 0
                              || line.indexOf('J') >= 0
                              || line.indexOf('K') >= 0;

                // Crea segmento se c'è movimento esplicito o coordinate cambiate.
                // hasNonMotionG: la riga contiene un G-code che NON è
                // G0/G1/G2/G3/G17/G18/G19/G90/G91 (es. G28, G53, G4...).
                // In quel caso le coordinate sono parametri del comando,
                // non destinazioni di movimento → nessun segmento.
                boolean hasCoords = (xInLine || yInLine || line.indexOf('Z') >= 0);
                boolean posChanged = (nx != x || ny != y || nz != z);
                boolean hasNonMotionG = line.indexOf('G') >= 0
                        && !hasG0 && !hasG1 && !hasG2 && !hasG3
                        && !hasG17 && !hasG18 && !hasG19
                        && !hasG90 && !hasG91;

                if ((hasG0 || hasG1 || hasG2 || hasG3) || (!hasNonMotionG && hasCoords && posChanged)) {
                    // Disegniamo segmenti solo quando X e Y del tool sono entrambi
                    // noti dal file. Altrimenti il punto di partenza del segmento
                    // sarebbe X=0 o Y=0 di default — un'invenzione, non una
                    // posizione descritta nel file.
                    if (xSpecified && ySpecified) {
                        if (!firstPositionSet) {
                            // Primo punto con X e Y entrambi noti: lo registriamo
                            // come origine del tracciato senza disegnare alcun
                            // segmento (non sappiamo da dove arrivava il tool).
                            firstPositionSet = true;
                            firstPosX = nx; firstPosY = ny; firstPosZ = nz;
                            b.updateExtremes(nx, ny, nz);
                        } else {
                            boolean isArcMotion = (modalMotion == 2 || modalMotion == 3);
                            if (isArcMotion && hasIJK) {
                                // Tessellazione arco G2/G3 in N segmenti
                                // rettilinei. Gli estremi vengono aggiornati
                                // per ciascun segmento intermedio (gli archi
                                // possono uscire dal bbox start/end).
                                appendArcSegments(b, x, y, z, nx, ny, nz,
                                                  iVal, jVal, kVal,
                                                  modalMotion == 2, plane, lineNum);
                            } else {
                                float[] color;
                                if (isArcMotion) {
                                    // G2/G3 senza I/J/K: fallback a corda
                                    // (visivamente sbagliato ma evita di scartare).
                                    color = COLOR_RED;
                                } else if (modalMotion == 0) {
                                    color = COLOR_BLUE;
                                } else if (nz != z && nx == x && ny == y) {
                                    color = COLOR_GREEN;
                                } else {
                                    color = COLOR_WHITE;
                                }
                                b.addSegment(x, y, z, nx, ny, nz, color, lineNum);
                            }
                        }
                    }
                }

                x = nx; y = ny; z = nz;
            }

        } catch (IOException e) {
            Log.e(TAG, "Errore lettura file GCode: " + e.getMessage());
            return null;
        }

        // NESSUNA pulizia finale: l'utente vuole vedere TUTTI i segmenti del
        // file, inclusi G00 di fine programma anche se vanno a (0,0) o altrove.
        // Tutte le linee disegnate corrispondono esattamente a comandi presenti
        // nel file GCode, niente partenze o arrivi inventati.

        if (b.segmentCount() == 0) {
            Log.w(TAG, "Nessun segmento valido nel file GCode.");
            return null;
        }

        return new ParsedModel(b, firstPosX, firstPosY, firstPosZ);
    }

    /**
     * Rimuove commenti {@code (...)} e {@code ;...} e le righe {@code %},
     * con la stessa semantica usata dallo streamer (GcodePreprocessorUtils):
     * il codice DOPO un commento inline viene conservato.
     */
    private static String stripComments(String raw) {
        String line = raw;
        int open;
        while ((open = line.indexOf('(')) >= 0) {
            int close = line.indexOf(')', open);
            if (close < 0) { line = line.substring(0, open); break; }
            line = line.substring(0, open) + line.substring(close + 1);
        }
        int semi = line.indexOf(';');
        if (semi >= 0) line = line.substring(0, semi);
        line = line.trim().toUpperCase();
        if (line.equals("%")) return "";
        return line;
    }

    // =========================================================================
    // Tessellazione archi G2/G3
    // =========================================================================

    /** ~6° per segmento, ovvero ~60 segmenti per cerchio completo. */
    private static final double ARC_ANG_STEP = Math.PI / 30.0;

    /**
     * Approssima un arco G2/G3 con una sequenza di piccoli segmenti rettilinei.
     * Supporta i tre piani standard:
     *   G17 (XY): centro = (x+I, y+J), Z elicoidale
     *   G18 (XZ): centro = (x+I, z+K), Y elicoidale
     *   G19 (YZ): centro = (y+J, z+K), X elicoidale
     *
     * Se i parametri portano a un arco degenere (raggio ≈ 0), ricade su una corda
     * rettilinea per non perdere il movimento dal file.
     */
    private static void appendArcSegments(ModelBuilder b,
                                          float x1, float y1, float z1,
                                          float x2, float y2, float z2,
                                          float iVal, float jVal, float kVal,
                                          boolean cw, int plane, int lineNum) {

        // Proietta sul piano 2D scelto e individua l'asse elicoidale.
        float ax1, ay1, ax2, ay2, ah1, ah2, ioff, joff;
        switch (plane) {
            case 18: // XZ
                ax1 = x1; ay1 = z1; ax2 = x2; ay2 = z2;
                ah1 = y1; ah2 = y2;
                ioff = iVal; joff = kVal;
                break;
            case 19: // YZ
                ax1 = y1; ay1 = z1; ax2 = y2; ay2 = z2;
                ah1 = x1; ah2 = x2;
                ioff = jVal; joff = kVal;
                break;
            default: // 17 = XY
                ax1 = x1; ay1 = y1; ax2 = x2; ay2 = y2;
                ah1 = z1; ah2 = z2;
                ioff = iVal; joff = jVal;
                break;
        }

        float cx = ax1 + ioff;
        float cy = ay1 + joff;
        float r  = (float) Math.hypot(ax1 - cx, ay1 - cy);

        // Arco degenere → fallback a corda dritta (colore arco per coerenza).
        if (r < 1e-5f) {
            b.addSegment(x1, y1, z1, x2, y2, z2, COLOR_RED, lineNum);
            return;
        }

        double startAng = Math.atan2(ay1 - cy, ax1 - cx);
        double endAng   = Math.atan2(ay2 - cy, ax2 - cx);
        double sweep    = endAng - startAng;

        boolean closedLoop = Math.abs(ax1 - ax2) < 1e-5f
                          && Math.abs(ay1 - ay2) < 1e-5f;

        if (cw) {
            // G2 sweep negativo
            if (sweep > 1e-9) sweep -= 2 * Math.PI;
            if (closedLoop)   sweep  = -2 * Math.PI;
        } else {
            // G3 sweep positivo
            if (sweep < -1e-9) sweep += 2 * Math.PI;
            if (closedLoop)    sweep  = 2 * Math.PI;
        }

        int n = Math.max(4, (int) Math.ceil(Math.abs(sweep) / ARC_ANG_STEP));
        double dAng = sweep / n;
        float  dh   = (ah2 - ah1) / n; // interp lineare sull'asse elicoidale

        float prevAx = ax1, prevAy = ay1, prevAh = ah1;

        for (int i = 1; i <= n; i++) {
            double ang = startAng + i * dAng;
            float newAx = cx + r * (float) Math.cos(ang);
            float newAy = cy + r * (float) Math.sin(ang);
            float newAh = ah1 + i * dh;

            // Forza l'ultimo vertice ad essere esattamente il target richiesto
            // dal file, per evitare drift numerico cumulato sui cos/sin.
            if (i == n) {
                newAx = ax2; newAy = ay2; newAh = ah2;
            }

            // Riproietta in 3D in base al piano.
            float sx1, sy1, sz1, sx2, sy2, sz2;
            switch (plane) {
                case 18: // XZ → x=ax, z=ay, y=ah
                    sx1 = prevAx; sy1 = prevAh; sz1 = prevAy;
                    sx2 = newAx;  sy2 = newAh;  sz2 = newAy;
                    break;
                case 19: // YZ → y=ax, z=ay, x=ah
                    sx1 = prevAh; sy1 = prevAx; sz1 = prevAy;
                    sx2 = newAh;  sy2 = newAx;  sz2 = newAy;
                    break;
                default: // 17 XY → x=ax, y=ay, z=ah
                    sx1 = prevAx; sy1 = prevAy; sz1 = prevAh;
                    sx2 = newAx;  sy2 = newAy;  sz2 = newAh;
                    break;
            }

            b.addSegment(sx1, sy1, sz1, sx2, sy2, sz2, COLOR_RED, lineNum);

            prevAx = newAx; prevAy = newAy; prevAh = newAh;
        }
    }

    // =========================================================================
    // Upload VBO
    // =========================================================================

    /** Carica i buffer in GPU (VBO). Solo al load di un nuovo modello. */
    private void uploadVBOs() {
        if (vertexBuffer == null || colorBuffer == null || lineNumberBuffer == null) return;

        // Vertex VBO
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vboHandles[0]);
        GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER,
                vertexBuffer.capacity() * 4,
                vertexBuffer,
                GLES20.GL_STATIC_DRAW);

        // Color VBO
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vboHandles[1]);
        GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER,
                colorBuffer.capacity() * 4,
                colorBuffer,
                GLES20.GL_STATIC_DRAW);

        // Line number VBO
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vboHandles[2]);
        GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER,
                lineNumberBuffer.capacity() * 4,
                lineNumberBuffer,
                GLES20.GL_STATIC_DRAW);

        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0);
    }

    // =========================================================================
    // API vista — chiamati dal Fragment
    // =========================================================================

    public synchronized void rotate(float dx, float dy) {
        rotationX += dx;
        rotationY -= dy;
    }

    public synchronized void pan(float dx, float dy) {
        // Converti pixel → coordinate oggetto
        float scale = scaleBase * zoomLevel;
        panX -= dx / (scale * viewportW);
        panY += dy / (scale * viewportH);
    }

    public synchronized void zoom(float factor) {
        zoomLevel *= factor;
        zoomLevel = Math.max(0.5f, Math.min(30.0f, zoomLevel));
    }

    public synchronized void resetView() {
        rotationX = 0f;
        rotationY = -30f;
        panX = 0f;
        panY = 0f;
        zoomLevel = 1f;
    }

    public synchronized void zoomFit() {
        panX = 0f;
        panY = 0f;
        zoomLevel = 1f;
        recalcScale();
    }

    // =========================================================================
    // Setter dati (thread-safe perché chiamati via queueEvent)
    // =========================================================================

    /**
     * Aggiorna la riga corrente per il gray-out. Costo: un float — lo shader
     * fa il resto, nessun rebuild di buffer.
     */
    public void setCurrentCommandNumber(int n) {
        this.currentCommandNumber = n;
    }

    public void setWorkCoordinate(double x, double y, double z) {
        this.workX = x; this.workY = y; this.workZ = z;
    }

    // =========================================================================
    // Utility
    // =========================================================================

    private void recalcScale() {
        if (maxSide == 0) { scaleBase = 1f; return; }
        // Scala l'oggetto in modo che entri nel frustum ortografico (-0.51, 0.51)
        // Uguale alla logica di VisualizerUtils.findScaleFactor
        scaleBase = (0.9f / maxSide);
    }

    /**
     * Verifica se una riga GCode contiene un comando Gnn specifico.
     * Usa word boundary per evitare falsi positivi:
     * G1 non matcha G10, G11; G2 non matcha G20, G21 ecc.
     * Gestisce sia "G1" che "G01" (con zero padding).
     */
    private static boolean containsGCode(String line, int code) {
        // Cerca "G" seguito dal numero esatto, non seguito da altre cifre
        String pat1 = "G" + code;          // es. "G1"
        String pat2 = "G0" + code;         // es. "G01" (solo per codici < 10)

        for (String pat : new String[]{pat1, pat2}) {
            int idx = line.indexOf(pat);
            while (idx >= 0) {
                int afterIdx = idx + pat.length();
                // Controlla che dopo il numero non ci siano altre cifre
                if (afterIdx >= line.length() || !Character.isDigit(line.charAt(afterIdx))) {
                    // Controlla che prima non ci sia un punto (es. parte di coordinata)
                    if (idx == 0 || !Character.isDigit(line.charAt(idx - 1))) {
                        return true;
                    }
                }
                idx = line.indexOf(pat, idx + 1);
            }
        }
        return false;
    }

    /** Parsa una coordinata (es. X12.34) dalla riga GCode. */
    private static float parseCoord(String line, char axis, float defaultVal) {
        int idx = line.indexOf(axis);
        if (idx < 0) return defaultVal;
        int start = idx + 1;
        int end = start;
        while (end < line.length() &&
                (Character.isDigit(line.charAt(end)) ||
                        line.charAt(end) == '.' ||
                        line.charAt(end) == '-')) {
            end++;
        }
        if (start == end) return defaultVal;
        try {
            return Float.parseFloat(line.substring(start, end));
        } catch (NumberFormatException e) {
            return defaultVal;
        }
    }

    /** Crea un FloatBuffer direct da un array float. */
    private static FloatBuffer makeFloatBuffer(float[] arr) {
        ByteBuffer bb = ByteBuffer.allocateDirect(arr.length * 4);
        bb.order(ByteOrder.nativeOrder());
        FloatBuffer fb = bb.asFloatBuffer();
        fb.put(arr);
        fb.position(0);
        return fb;
    }

    /** Compila e linka il programma GLSL. */
    private static int buildShaderProgram(String vertSrc, String fragSrc) {
        int vert = compileShader(GLES20.GL_VERTEX_SHADER, vertSrc);
        int frag = compileShader(GLES20.GL_FRAGMENT_SHADER, fragSrc);
        int prog = GLES20.glCreateProgram();
        GLES20.glAttachShader(prog, vert);
        GLES20.glAttachShader(prog, frag);
        GLES20.glLinkProgram(prog);
        GLES20.glDeleteShader(vert);
        GLES20.glDeleteShader(frag);
        return prog;
    }

    private static int compileShader(int type, String src) {
        int shader = GLES20.glCreateShader(type);
        GLES20.glShaderSource(shader, src);
        GLES20.glCompileShader(shader);
        int[] status = new int[1];
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0);
        if (status[0] == 0) {
            Log.e(TAG, "Shader compile error: " + GLES20.glGetShaderInfoLog(shader));
            GLES20.glDeleteShader(shader);
            return 0;
        }
        return shader;
    }
}

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


import android.content.Context;
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
import java.util.ArrayList;
import java.util.List;

public class GcodeRenderer implements GLSurfaceView.Renderer {

    private static final String TAG = "GcodeRenderer";

    // -------------------------------------------------------------------------
    // GLSL Shader sources (inline — nessun file .glsl esterno necessario)
    // -------------------------------------------------------------------------

    /**
     * Vertex shader: riceve posizione (vec3) e colore (vec3),
     * applica la matrice MVP e passa il colore al fragment shader.
     */
    private static final String VERTEX_SHADER_SRC =
            "uniform mat4 uMVPMatrix;\n" +
                    "attribute vec3 aPosition;\n" +
                    "attribute vec3 aColor;\n" +
                    "varying vec3 vColor;\n" +
                    "void main() {\n" +
                    "    gl_Position = uMVPMatrix * vec4(aPosition, 1.0);\n" +
                    "    vColor = aColor;\n" +
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
    private static final float[] COLOR_GRAY   = {0.4f, 0.4f, 0.4f};
    private static final float[] COLOR_RED    = {1.0f, 0.0f, 0.0f};
    private static final float[] COLOR_BLUE   = {0.0f, 0.4f, 1.0f};
    private static final float[] COLOR_GREEN  = {0.0f, 1.0f, 0.0f};
    private static final float[] COLOR_YELLOW = {1.0f, 1.0f, 0.0f};

    // -------------------------------------------------------------------------
    // Dati GCode
    // -------------------------------------------------------------------------

    /** Segmento minimo: inizio/fine + flag tipo */
    private static class Segment {
        float x1, y1, z1;
        float x2, y2, z2;
        boolean isArc;
        boolean isFastTraverse;
        boolean isZMove;
        int lineNumber;
    }

    private final List<Segment> segments = new ArrayList<>();
    private int currentCommandNumber = 0;

    // Estremi dell'oggetto (per calcolo centro e scala)
    private float minX, maxX, minY, maxY, minZ, maxZ;
    private float centerX, centerY, centerZ;
    private float maxSide = 1.0f;

    // -------------------------------------------------------------------------
    // Coordinate tool
    // -------------------------------------------------------------------------
    private double workX, workY, workZ;
    private double machineX, machineY, machineZ;

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
    private int shaderProgram  = 0;
    private int attrPosition   = -1;
    private int attrColor      = -1;
    private int unifMVPMatrix  = -1;

    // VBO handles
    private final int[] vboHandles = new int[2]; // [0]=vertex, [1]=color
    private boolean vboDirty = true;

    // Buffer in memoria
    private FloatBuffer vertexBuffer;
    private FloatBuffer colorBuffer;
    private int vertexCount = 0;

    // -------------------------------------------------------------------------
    // Context
    // -------------------------------------------------------------------------
    private final Context context;

    public GcodeRenderer(Context context) {
        this.context = context;
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

        attrPosition = GLES20.glGetAttribLocation(shaderProgram, "aPosition");
        attrColor    = GLES20.glGetAttribLocation(shaderProgram, "aColor");
        unifMVPMatrix= GLES20.glGetUniformLocation(shaderProgram, "uMVPMatrix");

        GLES20.glGenBuffers(2, vboHandles, 0);

        // Se c'erano già dei segmenti caricati prima che la superficie fosse
        // pronta (es. file caricato prima della rotazione), ricostruiamo i buffer.
        if (!segments.isEmpty()) {
            buildVertexBuffers();
            uploadVBOs();
            vboDirty = false;
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

        if (segments.isEmpty()) return;

        // --- Aggiorna VBO se necessario ---
        if (vboDirty) {
            buildVertexBuffers();
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
     */
    private void drawLines() {
        if (vertexCount == 0) return;

        GLES20.glUseProgram(shaderProgram);
        GLES20.glUniformMatrix4fv(unifMVPMatrix, 1, false, mvpMatrix, 0);

        // Vertex buffer
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vboHandles[0]);
        GLES20.glEnableVertexAttribArray(attrPosition);
        GLES20.glVertexAttribPointer(attrPosition, 3, GLES20.GL_FLOAT, false, 0, 0);

        // Color buffer
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vboHandles[1]);
        GLES20.glEnableVertexAttribArray(attrColor);
        GLES20.glVertexAttribPointer(attrColor, 3, GLES20.GL_FLOAT, false, 0, 0);

        GLES20.glDrawArrays(GLES20.GL_LINES, 0, vertexCount);

        GLES20.glDisableVertexAttribArray(attrPosition);
        GLES20.glDisableVertexAttribArray(attrColor);
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0);
    }

    /**
     * Disegna il cursore tool nella posizione corrente di lavoro.
     * Corrisponde a renderTool() del VisualizerCanvas.
     */
    private void drawTool() {
        // Linea verticale gialla nella posizione work coordinate
        float toolHeight = 0.05f / (scaleBase * zoomLevel); // altezza in coordinate oggetto

        float[] tv = {
                (float) workX, (float) workY, (float) workZ,
                (float) workX, (float) workY, (float) workZ + toolHeight
        };
        float[] tc = {
                COLOR_YELLOW[0], COLOR_YELLOW[1], COLOR_YELLOW[2],
                COLOR_YELLOW[0], COLOR_YELLOW[1], COLOR_YELLOW[2]
        };

        FloatBuffer tvBuf = makeFloatBuffer(tv);
        FloatBuffer tcBuf = makeFloatBuffer(tc);

        GLES20.glUseProgram(shaderProgram);
        GLES20.glUniformMatrix4fv(unifMVPMatrix, 1, false, mvpMatrix, 0);

        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0); // usa client array per il tool

        GLES20.glEnableVertexAttribArray(attrPosition);
        GLES20.glVertexAttribPointer(attrPosition, 3, GLES20.GL_FLOAT, false, 0, tvBuf);

        GLES20.glEnableVertexAttribArray(attrColor);
        GLES20.glVertexAttribPointer(attrColor, 3, GLES20.GL_FLOAT, false, 0, tcBuf);

        GLES20.glLineWidth(4.0f); // nota: su ES 2.0 il line width max può essere limitato dall'hardware
        GLES20.glDrawArrays(GLES20.GL_LINES, 0, 2);

        GLES20.glDisableVertexAttribArray(attrPosition);
        GLES20.glDisableVertexAttribArray(attrColor);
    }

    /**
     * Disegna i tre assi XYZ nell'origine (punto zero macchina).
     * X = rosso, Y = verde, Z = blu — identico a renderAxes() del VisualizerCanvas.
     * La lunghezza degli assi è proporzionale all'oggetto caricato.
     */
    private void drawAxes() {
        // Lunghezza asse: 10% del lato più lungo dell'oggetto, minimo visibile
        float axisLen = maxSide * 0.15f;
        if (axisLen == 0) axisLen = 0.05f;

        // Origine = work position (zero pezzo, non zero macchina)
        float ox = (float) workX;
        float oy = (float) workY;
        float oz = (float) workZ;

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

        FloatBuffer avBuf = makeFloatBuffer(av);
        FloatBuffer acBuf = makeFloatBuffer(ac);

        GLES20.glUseProgram(shaderProgram);
        GLES20.glUniformMatrix4fv(unifMVPMatrix, 1, false, mvpMatrix, 0);
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0);

        GLES20.glEnableVertexAttribArray(attrPosition);
        GLES20.glVertexAttribPointer(attrPosition, 3, GLES20.GL_FLOAT, false, 0, avBuf);

        GLES20.glEnableVertexAttribArray(attrColor);
        GLES20.glVertexAttribPointer(attrColor, 3, GLES20.GL_FLOAT, false, 0, acBuf);

        GLES20.glLineWidth(3.0f);
        GLES20.glDrawArrays(GLES20.GL_LINES, 0, 6); // 3 assi × 2 vertici

        GLES20.glDisableVertexAttribArray(attrPosition);
        GLES20.glDisableVertexAttribArray(attrColor);
    }

    // =========================================================================
    // Caricamento file GCode
    // =========================================================================

    /**
     * Carica e parsa un file GCode.
     * DEVE essere chiamato dal thread GL (via queueEvent).
     *
     * @param filePath percorso del file
     * @param processed true se il file è già stato pre-processato
     */
    public void loadFile(String filePath, boolean processed) {
        segments.clear();
        resetExtremes();

        try (BufferedReader reader = new BufferedReader(new FileReader(filePath))) {

            // --- Parser GCode minimale ---
            // Questo parser semplificato gestisce G0/G1/G2/G3.
            // Per un parser completo, integrare la libreria GcodeParser
            // già presente in UGS o una equivalente Android.

            String line;
            float x = 0, y = 0, z = 0;
            boolean isAbsolute = true;
            int lineNum = 0;

            // Stato modale: persiste tra le righe come da spec GCode
            int modalMotion = 0; // 0=G0 rapid, 1=G1 linear, 2=G2 arc CW, 3=G3 arc CCW

            while ((line = reader.readLine()) != null) {
                line = line.trim().toUpperCase();
                if (line.isEmpty()) continue;

                // Rimuovi commenti ( ) e ;
                int commentIdx = line.indexOf('(');
                if (commentIdx >= 0) line = line.substring(0, commentIdx).trim();
                commentIdx = line.indexOf(';');
                if (commentIdx >= 0) line = line.substring(0, commentIdx).trim();
                if (line.isEmpty()) continue;

                lineNum++;

                // Detect comandi G con word boundary corretto
                // containsGCode evita falsi positivi (G1 vs G10, G2 vs G20 ecc.)
                boolean hasG0  = containsGCode(line, 0);
                boolean hasG1  = containsGCode(line, 1);
                boolean hasG2  = containsGCode(line, 2);
                boolean hasG3  = containsGCode(line, 3);
                boolean hasG90 = containsGCode(line, 90);
                boolean hasG91 = containsGCode(line, 91);

                if (hasG90) isAbsolute = true;
                if (hasG91) isAbsolute = false;

                // Aggiorna stato modale
                if      (hasG0) modalMotion = 0;
                else if (hasG1) modalMotion = 1;
                else if (hasG2) modalMotion = 2;
                else if (hasG3) modalMotion = 3;

                // Leggi coordinate presenti nella riga
                float nx = parseCoord(line, 'X', x);
                float ny = parseCoord(line, 'Y', y);
                float nz = parseCoord(line, 'Z', z);

                if (!isAbsolute) { nx += x; ny += y; nz += z; }

                // Crea segmento se c'è movimento esplicito o coordinate cambiate
                boolean hasCoords = (line.contains("X") || line.contains("Y") || line.contains("Z"));
                boolean posChanged = (nx != x || ny != y || nz != z);

                if ((hasG0 || hasG1 || hasG2 || hasG3) || (hasCoords && posChanged)) {
                    Segment seg = new Segment();
                    seg.x1 = x; seg.y1 = y; seg.z1 = z;
                    seg.x2 = nx; seg.y2 = ny; seg.z2 = nz;
                    seg.isFastTraverse = (modalMotion == 0);
                    seg.isArc          = (modalMotion == 2 || modalMotion == 3);
                    seg.isZMove        = (nz != z && nx == x && ny == y);
                    seg.lineNumber     = lineNum;
                    segments.add(seg);
                    updateExtremes(nx, ny, nz);
                }

                x = nx; y = ny; z = nz;
            }

        } catch (IOException e) {
            Log.e(TAG, "Errore lettura file GCode: " + e.getMessage());
            return;
        }

        // Calcola centro e scala
        centerX = (minX + maxX) / 2f;
        centerY = (minY + maxY) / 2f;
        centerZ = (minZ + maxZ) / 2f;

        float dx = maxX - minX;
        float dy = maxY - minY;
        float dz = maxZ - minZ;
        maxSide = Math.max(dx, Math.max(dy, dz));
        if (maxSide == 0) maxSide = 1;

        recalcScale();
        vboDirty = true;

        Log.i(TAG, String.format("GCode caricato: %d segmenti, X(%.2f,%.2f) Y(%.2f,%.2f) Z(%.2f,%.2f)",
                segments.size(), minX, maxX, minY, maxY, minZ, maxZ));
    }

    // =========================================================================
    // Buffer vertex/color (come createVertexBuffers in VisualizerCanvas)
    // =========================================================================

    /**
     * Converte la lista di segmenti in array float per posizioni e colori.
     * Logica identica a createVertexBuffers() del VisualizerCanvas originale.
     */
    private void buildVertexBuffers() {
        int n = segments.size();
        if (n == 0) { vertexCount = 0; return; }

        vertexCount = n * 2; // ogni segmento ha 2 vertici
        float[] verts  = new float[vertexCount * 3];
        float[] colors = new float[vertexCount * 3];

        int vi = 0, ci = 0;

        for (Segment seg : segments) {
            float[] color;

            // --- Schema colori (identico all'originale) ---
            if (seg.isArc)          color = COLOR_RED;
            else if (seg.isFastTraverse) color = COLOR_BLUE;
            else if (seg.isZMove)    color = COLOR_GREEN;
            else                     color = COLOR_WHITE;

            // Grigio per le righe già eseguite
            if (seg.lineNumber <= currentCommandNumber) color = COLOR_GRAY;

            // Vertice 1
            verts[vi++] = seg.x1; verts[vi++] = seg.y1; verts[vi++] = seg.z1;
            colors[ci++] = color[0]; colors[ci++] = color[1]; colors[ci++] = color[2];

            // Vertice 2
            verts[vi++] = seg.x2; verts[vi++] = seg.y2; verts[vi++] = seg.z2;
            colors[ci++] = color[0]; colors[ci++] = color[1]; colors[ci++] = color[2];
        }

        vertexBuffer = makeFloatBuffer(verts);
        colorBuffer  = makeFloatBuffer(colors);
    }

    /** Carica i buffer in GPU (VBO). */
    private void uploadVBOs() {
        if (vertexBuffer == null || colorBuffer == null) return;

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

    public void setCurrentCommandNumber(int n) {
        this.currentCommandNumber = n;
        vboDirty = true;
    }

    public void setWorkCoordinate(double x, double y, double z) {
        this.workX = x; this.workY = y; this.workZ = z;
    }

    public void setMachineCoordinate(double x, double y, double z) {
        this.machineX = x; this.machineY = y; this.machineZ = z;
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

    private void resetExtremes() {
        minX = Float.MAX_VALUE; maxX = -Float.MAX_VALUE;
        minY = Float.MAX_VALUE; maxY = -Float.MAX_VALUE;
        minZ = Float.MAX_VALUE; maxZ = -Float.MAX_VALUE;
    }

    private void updateExtremes(float x, float y, float z) {
        minX = Math.min(minX, x); maxX = Math.max(maxX, x);
        minY = Math.min(minY, y); maxY = Math.max(maxY, y);
        minZ = Math.min(minZ, z); maxZ = Math.max(maxZ, z);
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
    private float parseCoord(String line, char axis, float defaultVal) {
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
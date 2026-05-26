package in.co.gorest.grblcontroller.service;

import android.util.Base64;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import fi.iki.elonen.NanoHTTPD;

public class GcodeHttpServer extends NanoHTTPD {

    private static final String TAG = GcodeHttpServer.class.getSimpleName();

    private static final List<String> ALLOWED_EXT = Arrays.asList(
            "nc", "gcode", "gco", "tap", "cnc", "ngc", "txt");

    private final File rootDir;
    private final String basicAuthHeader;

    public GcodeHttpServer(int port, File rootDir, String password) {
        super(port);
        this.rootDir = rootDir;
        if (password != null && !password.isEmpty()) {
            String creds = "grbl:" + password;
            this.basicAuthHeader = "Basic " + Base64.encodeToString(
                    creds.getBytes(), Base64.NO_WRAP);
        } else {
            this.basicAuthHeader = null;
        }
    }

    @Override
    public Response serve(IHTTPSession session) {
        if (basicAuthHeader != null) {
            String auth = session.getHeaders().get("authorization");
            if (auth == null || !auth.equals(basicAuthHeader)) {
                Response r = newFixedLengthResponse(
                        Response.Status.UNAUTHORIZED, "text/plain", "Auth required");
                r.addHeader("WWW-Authenticate", "Basic realm=\"GrblController\"");
                return r;
            }
        }

        String uri = session.getUri();
        Method method = session.getMethod();

        try {
            if (Method.GET.equals(method) && (uri.equals("/") || uri.equals("/index"))) {
                return renderIndex();
            }
            if (Method.GET.equals(method) && uri.startsWith("/d/")) {
                String name = decode(uri.substring(3));
                return serveDownload(name);
            }
            if (Method.POST.equals(method) && uri.equals("/upload")) {
                return handleUpload(session);
            }
            return newFixedLengthResponse(
                    Response.Status.NOT_FOUND, "text/plain", "Not found");
        } catch (Exception e) {
            Log.e(TAG, "serve error", e);
            return newFixedLengthResponse(
                    Response.Status.INTERNAL_ERROR, "text/plain", "Server error: " + e.getMessage());
        }
    }

    private Response renderIndex() {
        List<File> files = listGcodeFiles();
        StringBuilder html = new StringBuilder();
        html.append("<!DOCTYPE html><html><head><meta charset=\"utf-8\">")
                .append("<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">")
                .append("<title>Grbl Controller - File</title>")
                .append("<style>")
                .append("body{font-family:sans-serif;max-width:720px;margin:1em auto;padding:0 1em;}")
                .append("h1{font-size:1.3em;}")
                .append("table{width:100%;border-collapse:collapse;}")
                .append("th,td{text-align:left;padding:6px 4px;border-bottom:1px solid #ddd;}")
                .append("td.size{text-align:right;white-space:nowrap;color:#666;}")
                .append("form{margin:1.5em 0;padding:1em;background:#f4f4f4;border-radius:6px;}")
                .append(".empty{color:#888;font-style:italic;}")
                .append("</style></head><body>")
                .append("<h1>Grbl Controller — File G-Code</h1>");

        html.append("<form method=\"POST\" action=\"/upload\" enctype=\"multipart/form-data\">")
                .append("<p><strong>Carica file dal PC</strong> (sovrascrive senza avviso se il nome esiste)</p>")
                .append("<input type=\"file\" name=\"file\" multiple required>")
                .append(" <button type=\"submit\">Carica</button>")
                .append("</form>");

        if (files.isEmpty()) {
            html.append("<p class=\"empty\">Nessun file G-Code presente.</p>");
        } else {
            html.append("<table><thead><tr><th>Nome</th><th class=\"size\">Dimensione</th></tr></thead><tbody>");
            for (File f : files) {
                html.append("<tr><td><a href=\"/d/").append(encode(f.getName())).append("\">")
                        .append(escapeHtml(f.getName())).append("</a></td>")
                        .append("<td class=\"size\">").append(formatSize(f.length())).append("</td></tr>");
            }
            html.append("</tbody></table>");
        }
        html.append("</body></html>");
        return newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", html.toString());
    }

    private Response serveDownload(String name) throws IOException {
        File target = safeResolve(name);
        if (target == null || !target.isFile()) {
            return newFixedLengthResponse(
                    Response.Status.NOT_FOUND, "text/plain", "File not found");
        }
        InputStream is = new FileInputStream(target);
        Response r = newFixedLengthResponse(
                Response.Status.OK, "application/octet-stream", is, target.length());
        r.addHeader("Content-Disposition",
                "attachment; filename=\"" + target.getName().replace("\"", "_") + "\"");
        return r;
    }

    private Response handleUpload(IHTTPSession session) throws IOException, ResponseException {
        Map<String, String> tempFiles = new HashMap<>();
        session.parseBody(tempFiles);

        Map<String, List<String>> params = session.getParameters();
        int saved = 0;
        StringBuilder rejected = new StringBuilder();

        for (Map.Entry<String, String> e : tempFiles.entrySet()) {
            String fieldName = e.getKey();
            String tmpPath = e.getValue();

            String originalName = null;
            if (params.containsKey(fieldName) && !params.get(fieldName).isEmpty()) {
                originalName = params.get(fieldName).get(0);
            }
            if (originalName == null || originalName.isEmpty()) continue;

            String safeName = sanitizeFileName(originalName);
            if (!hasAllowedExtension(safeName)) {
                if (rejected.length() > 0) rejected.append(", ");
                rejected.append(escapeHtml(originalName));
                continue;
            }
            File dest = safeResolve(safeName);
            if (dest == null) {
                if (rejected.length() > 0) rejected.append(", ");
                rejected.append(escapeHtml(originalName));
                continue;
            }
            File tmp = new File(tmpPath);
            if (!copyAndReplace(tmp, dest)) {
                if (rejected.length() > 0) rejected.append(", ");
                rejected.append(escapeHtml(originalName));
                continue;
            }
            saved++;
        }

        StringBuilder html = new StringBuilder();
        html.append("<!DOCTYPE html><html><head><meta charset=\"utf-8\">")
                .append("<title>Upload</title></head><body>")
                .append("<p>Caricati: ").append(saved).append(" file.</p>");
        if (rejected.length() > 0) {
            html.append("<p>Rifiutati (estensione non consentita): ").append(rejected).append("</p>");
        }
        html.append("<p><a href=\"/\">Torna alla lista</a></p></body></html>");
        return newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", html.toString());
    }

    private List<File> listGcodeFiles() {
        if (rootDir == null || !rootDir.isDirectory()) return Collections.emptyList();
        File[] arr = rootDir.listFiles();
        if (arr == null) return Collections.emptyList();
        List<File> out = new ArrayList<>();
        for (File f : arr) {
            if (f.isFile() && hasAllowedExtension(f.getName())) out.add(f);
        }
        Collections.sort(out, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
        return out;
    }

    private boolean hasAllowedExtension(String name) {
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) return false;
        String ext = name.substring(dot + 1).toLowerCase(Locale.ROOT);
        return ALLOWED_EXT.contains(ext);
    }

    private String sanitizeFileName(String raw) {
        String name = raw.replace('\\', '/');
        int slash = name.lastIndexOf('/');
        if (slash >= 0) name = name.substring(slash + 1);
        name = name.replaceAll("[\\x00-\\x1f]", "");
        if (name.equals(".") || name.equals("..")) name = "_" + name;
        return name;
    }

    private File safeResolve(String name) throws IOException {
        File f = new File(rootDir, name);
        String rootCanon = rootDir.getCanonicalPath();
        String fCanon = f.getCanonicalPath();
        if (!fCanon.equals(rootCanon) && !fCanon.startsWith(rootCanon + File.separator)) {
            return null;
        }
        return f;
    }

    private boolean copyAndReplace(File src, File dest) {
        try (InputStream in = new FileInputStream(src);
             java.io.OutputStream out = new java.io.FileOutputStream(dest, false)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return true;
        } catch (IOException e) {
            Log.e(TAG, "copy failed", e);
            return false;
        }
    }

    private static String escapeHtml(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;")
                .replace(">", "&gt;").replace("\"", "&quot;");
    }

    private static String encode(String s) {
        try {
            return java.net.URLEncoder.encode(s, "UTF-8").replace("+", "%20");
        } catch (Exception e) {
            return s;
        }
    }

    private static String decode(String s) {
        try {
            return java.net.URLDecoder.decode(s, "UTF-8");
        } catch (Exception e) {
            return s;
        }
    }

    private static String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format(Locale.ROOT, "%.1f KB", bytes / 1024.0);
        return String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024.0));
    }
}

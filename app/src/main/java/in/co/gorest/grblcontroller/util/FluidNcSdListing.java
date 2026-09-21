package in.co.gorest.grblcontroller.util;

import java.util.ArrayList;
import java.util.List;

/** Handles both legacy absolute paths and FluidNC's indented recursive listing. */
public final class FluidNcSdListing {
    public final List<String> files = new ArrayList<>();
    private final List<String> directories = new ArrayList<>();

    public void accept(String line) {
        boolean directory = line.startsWith("[DIR:");
        if (!directory && !line.startsWith("[FILE:")) return;
        int end = directory ? line.lastIndexOf(']') : line.lastIndexOf("|SIZE:");
        int start = directory ? 5 : 6;
        if (end <= start) return;
        String raw = line.substring(start, end);
        // New FILE records add one separator space before indentation.
        if (!directory && raw.startsWith(" ")) raw = raw.substring(1);
        int depth = 0;
        while (depth < raw.length() && raw.charAt(depth) == ' ') depth++;
        String name = raw.substring(depth);
        if (name.isEmpty() || name.indexOf('\r') >= 0 || name.indexOf('\n') >= 0) return;
        if (directory) {
            while (directories.size() > depth) directories.remove(directories.size() - 1);
            if (directories.size() == depth) directories.add(name);
            return;
        }
        if (!name.startsWith("/")) {
            if (depth > directories.size()) return; // Never guess an ambiguous path.
            StringBuilder path = new StringBuilder("/");
            for (int i = 0; i < depth; i++) path.append(directories.get(i)).append('/');
            name = path.append(name).toString();
        }
        if (!files.contains(name)) files.add(name);
    }
}

package com.fongmi.android.tv.cache;

import android.os.Environment;

import com.fongmi.android.tv.App;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;

public final class CachePaths {

    private static final String DIRECTORY = "FluxDown";

    private CachePaths() {
    }

    public static File root() {
        File base = App.get().getExternalFilesDir(Environment.DIRECTORY_MOVIES);
        if (base == null) base = App.get().getFilesDir();
        File root = new File(base, DIRECTORY);
        if (!root.isDirectory() && !root.mkdirs() && !root.isDirectory()) throw new IllegalStateException("Unable to create cache directory");
        return root;
    }

    public static File resolve(String fileName) throws IOException {
        if (fileName == null || fileName.trim().isEmpty() || fileName.equals(".") || fileName.equals("..") || fileName.indexOf('/') >= 0 || fileName.indexOf('\\') >= 0 || fileName.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("Invalid cache file name");
        }
        return requireOwned(new File(root(), fileName));
    }

    public static boolean isOwned(File file) {
        try {
            requireOwned(file);
            return true;
        } catch (IOException | IllegalArgumentException | SecurityException e) {
            return false;
        }
    }

    public static File requireOwned(File file) throws IOException {
        if (file == null) throw new IllegalArgumentException("Missing cache file");
        File root = root().getCanonicalFile();
        File target = file.getCanonicalFile();
        if (target.equals(root) || !target.toPath().startsWith(root.toPath())) throw new SecurityException("Path outside cache directory");
        return target;
    }

    public static File requireReadableFile(File file) throws IOException {
        File target = requireOwned(file);
        if (!target.isFile() || !target.canRead()) throw new FileNotFoundException("Cache file not found");
        return target;
    }
}

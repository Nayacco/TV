package com.fongmi.fluxdown;

import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;

import java.io.File;
import java.io.IOException;

/** Connects FluxDown's managed command path to the executable installed with the APK. */
public final class FFmpegRuntime {

    private FFmpegRuntime() {
    }

    public static File prepare(String dataDir, String nativeLibraryDir) throws IOException {
        File executable = new File(nativeLibraryDir, "libffmpeg.so");
        if (!executable.isFile() || !executable.canExecute()) {
            throw new IOException("Packaged Android FFmpeg is missing or not executable: " + executable);
        }
        File bin = new File(dataDir, "bin");
        if (!bin.isDirectory() && !bin.mkdirs() && !bin.isDirectory()) {
            throw new IOException("Unable to create FluxDown component directory: " + bin);
        }
        File link = new File(bin, "ffmpeg");
        try {
            if (Os.readlink(link.getAbsolutePath()).equals(executable.getAbsolutePath())) return link;
        } catch (ErrnoException error) {
            if (error.errno != OsConstants.ENOENT) {
                throw new IOException("Refusing to overwrite an unmanaged FFmpeg path: " + link, error);
            }
        }
        // Only the symlink is writable. The executable remains in the APK's read-only native
        // directory, which is essential for Android 10+ execution restrictions. Refresh before
        // openLocal: restored tasks must never discover the previous installation's native path.
        File temporary = new File(bin, ".ffmpeg-link-" + java.util.UUID.randomUUID());
        boolean linked = false;
        try {
            Os.symlink(executable.getAbsolutePath(), temporary.getAbsolutePath());
            linked = true;
            Os.rename(temporary.getAbsolutePath(), link.getAbsolutePath());
            linked = false;
        } catch (ErrnoException error) {
            throw new IOException("Unable to connect packaged FFmpeg to FluxDown", error);
        } finally {
            if (linked) {
                try {
                    Os.unlink(temporary.getAbsolutePath());
                } catch (ErrnoException error) {
                    if (error.errno != OsConstants.ENOENT) {
                        throw new IOException("Unable to remove temporary FFmpeg link", error);
                    }
                }
            }
        }
        return link;
    }
}

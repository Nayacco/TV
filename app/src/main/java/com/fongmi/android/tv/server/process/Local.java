package com.fongmi.android.tv.server.process;

import static fi.iki.elonen.NanoHTTPD.getMimeTypeForFile;

import com.fongmi.android.tv.server.FileResponder;
import com.fongmi.android.tv.server.Nano;
import com.fongmi.android.tv.server.impl.Process;
import com.fongmi.android.tv.utils.FileUtil;
import com.fongmi.android.tv.utils.Formatters;
import com.github.catvod.utils.Path;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Locale;
import java.util.Map;

import fi.iki.elonen.NanoHTTPD.IHTTPSession;
import fi.iki.elonen.NanoHTTPD.Response;

public class Local implements Process {

    private static final String FILE = "/file";

    @Override
    public boolean isRequest(IHTTPSession session, String url) {
        return isFile(url) || url.equals("/upload") || url.equals("/newFolder") || url.equals("/delFolder") || url.equals("/delFile");
    }

    @Override
    public Response doResponse(IHTTPSession session, String url, Map<String, String> files) {
        try {
            if (isFile(url)) return getFile(session, url);
            Map<String, String> params = session.getParms();
            if (url.equals("/upload")) upload(params, files);
            else if (url.equals("/newFolder")) newFolder(params);
            else if (url.equals("/delFolder") || url.equals("/delFile")) delete(params);
            else return null;
            return Nano.ok();
        } catch (Exception e) {
            return Nano.error(e.getMessage());
        }
    }

    private static boolean isFile(String url) {
        return url.equals(FILE) || url.startsWith(FILE + "/");
    }

    private Response getFile(IHTTPSession session, String url) throws IOException {
        String path = url.substring(FILE.length());
        File file = resolveFile(session, path);
        if (file.isDirectory()) return getFolder(file);
        if (!file.isFile()) throw new FileNotFoundException("File not found");
        return FileResponder.respond(session.getMethod(), session.getHeaders(), file, getMimeTypeForFile(path));
    }

    private void upload(Map<String, String> params, Map<String, String> files) throws IOException {
        if (files.isEmpty()) throw new IllegalArgumentException("Missing upload file");
        File directory = resolvePath(requirePath(params));
        if (!directory.isDirectory()) throw new FileNotFoundException("Upload directory not found");
        for (Map.Entry<String, String> entry : files.entrySet()) {
            File source = new File(entry.getValue());
            String name = requireName(params.get(entry.getKey()));
            if (!source.isFile() || !source.canRead()) throw new FileNotFoundException("Upload file not found");
            storeUpload(source, directory, name);
        }
    }

    private void storeUpload(File source, File directory, String name) throws IOException {
        if (name.toLowerCase(Locale.ROOT).endsWith(".zip")) {
            if (!FileUtil.zipDecompress(source, directory)) throw new IOException("Unable to extract archive");
        } else {
            FileUtil.copyAtomically(source, resolveChild(directory, name));
        }
    }

    private void newFolder(Map<String, String> params) throws IOException {
        File directory = resolvePath(requirePath(params));
        if (!directory.isDirectory()) throw new FileNotFoundException("Parent directory not found");
        File folder = resolveChild(directory, requireName(params.get("name")));
        if (!folder.mkdirs() && !folder.isDirectory()) throw new IOException("Unable to create directory");
    }

    private void delete(Map<String, String> params) throws IOException {
        File target = resolveDeletePath(requirePath(params));
        if (!target.exists()) throw new FileNotFoundException("File not found");
        deleteRecursively(target);
    }

    private Response getFolder(File directory) {
        File root = Path.root();
        String rootPath = root.getAbsolutePath();
        JsonArray files = new JsonArray();
        Path.list(directory).forEach(file -> files.add(buildFileInfo(file, rootPath)));
        JsonObject info = new JsonObject();
        info.addProperty("parent", parentOf(directory, root, rootPath));
        info.add("files", files);
        return Nano.ok(info.toString());
    }

    private JsonObject buildFileInfo(File file, String rootPath) {
        JsonObject info = new JsonObject();
        info.addProperty("name", file.getName());
        info.addProperty("path", relativeTo(file, rootPath));
        info.addProperty("time", Formatters.LOCAL_DATETIME.format(Instant.ofEpochMilli(file.lastModified()).atZone(ZoneId.systemDefault())));
        info.addProperty("dir", file.isDirectory() ? 1 : 0);
        return info;
    }

    private static String requirePath(Map<String, String> params) {
        String path = params.get("path");
        if (path == null) throw new IllegalArgumentException("Missing path");
        return path;
    }

    private static String requireName(String name) {
        if (name == null || name.isEmpty() || name.equals(".") || name.equals("..") || name.indexOf('/') >= 0 || name.indexOf('\\') >= 0 || name.indexOf('\0') >= 0) throw new IllegalArgumentException("Invalid file name");
        return name;
    }

    private static File resolvePath(String path) throws IOException {
        return resolve(new File(Path.root(), relativePath(path)));
    }

    private static File resolveFile(IHTTPSession session, String path) throws IOException {
        File file = Path.local(path).getCanonicalFile();
        if (isLoopback(session) || isWithin(file, Path.root())) return file;
        throw new SecurityException("Path outside shared storage");
    }

    private static File resolveChild(File directory, String name) throws IOException {
        return resolve(new File(directory, name));
    }

    private static File resolveDeletePath(String path) throws IOException {
        File root = Path.root().getCanonicalFile();
        File target = new File(root, relativePath(path)).getAbsoluteFile();
        if (resolve(target).equals(root)) throw new SecurityException("Storage root cannot be deleted");
        return target;
    }

    private static String relativePath(String path) {
        while (path.startsWith(File.separator)) path = path.substring(1);
        return path;
    }

    private static File resolve(File file) throws IOException {
        File target = file.getCanonicalFile();
        if (!isWithin(target, Path.root())) throw new SecurityException("Path outside storage root");
        return target;
    }

    private static boolean isWithin(File file, File directory) throws IOException {
        return file.toPath().startsWith(directory.getCanonicalFile().toPath());
    }

    private static void deleteRecursively(File file) throws IOException {
        boolean symbolicLink = !file.getCanonicalFile().equals(file.getAbsoluteFile());
        if (file.isDirectory() && !symbolicLink) {
            File[] children = file.listFiles();
            if (children == null) throw new IOException("Unable to list directory");
            for (File child : children) deleteRecursively(child);
        }
        if (!file.delete() && file.exists()) throw new IOException("Unable to delete file");
    }

    private static boolean isLoopback(IHTTPSession session) {
        String address = session.getRemoteIpAddress();
        return address != null && (address.startsWith("127.") || address.equals("::1") || address.equals("0:0:0:0:0:0:0:1"));
    }

    private static String relativeTo(File file, String rootPath) {
        String path = file.getAbsolutePath();
        return path.startsWith(rootPath) ? path.substring(rootPath.length()) : path;
    }

    private static String parentOf(File dir, File rootDir, String rootPath) {
        if (dir.equals(rootDir)) return ".";
        File parent = dir.getParentFile();
        if (parent == null || parent.equals(rootDir)) return "";
        return relativeTo(parent, rootPath);
    }

}

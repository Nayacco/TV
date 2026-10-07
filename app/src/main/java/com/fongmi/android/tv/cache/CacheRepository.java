package com.fongmi.android.tv.cache;

import android.text.TextUtils;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.db.AppDatabase;
import com.fongmi.android.tv.service.CacheDownloadService;
import com.fongmi.fluxdown.FluxDownEngine;
import com.fongmi.fluxdown.FluxTaskInfo;
import com.google.gson.reflect.TypeToken;

import java.io.File;
import java.lang.reflect.Type;
import java.net.URLConnection;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/** Owns FongMi metadata and joins it to FluxDown's persistent task stream by task UUID. */
public final class CacheRepository implements FluxDownEngine.Listener {

    public interface EnqueueCallback {
        void onSuccess(CacheMetadata metadata, boolean existed);

        void onError(String message);
    }

    private static final Type HEADERS_TYPE = new TypeToken<Map<String, String>>() {}.getType();

    private final ExecutorService serial = Executors.newSingleThreadExecutor();
    private final Map<String, FluxTaskInfo> pendingTasks = new HashMap<>();
    private final Map<String, Operation> operations = new HashMap<>();
    private final Set<String> ignoredTaskIds = new HashSet<>();
    private long nextOperationId;

    private enum Action { CREATE, RESTART, PAUSE, RESUME, CANCEL, DELETE }

    private record Operation(long id, Action action) {
    }

    private static class Loader {
        static final CacheRepository INSTANCE = new CacheRepository();
    }

    public static CacheRepository get() {
        return Loader.INSTANCE;
    }

    private CacheRepository() {
    }

    public void restore() {
        serial.execute(() -> {
            if (dao().countRunning() == 0) return;
            try {
                CacheDownloadService.start(App.get());
            } catch (RuntimeException e) {
                markActiveFailed(e.getMessage());
            }
        });
    }

    public synchronized void startEngine() {
        File data = new File(App.get().getFilesDir(), "fluxdown");
        if (!data.isDirectory() && !data.mkdirs() && !data.isDirectory()) {
            throw new IllegalStateException("Unable to create FluxDown data directory");
        }
        // The Kotlin facade is idempotent and clears its own opening session after a failed start.
        // Calling through each time avoids a stale Java-side flag preventing a later retry.
        FluxDownEngine.start(data.getAbsolutePath(), CachePaths.root().getAbsolutePath(), this);
    }

    public void load(Consumer<List<CacheMetadata>> callback) {
        serial.execute(() -> {
            List<CacheMetadata> items = dao().findAll();
            App.post(() -> callback.accept(items));
        });
    }

    public void invalidateCompleted(String cacheKey, String message) {
        serial.execute(() -> {
            CacheMetadata item = dao().findCompleted(cacheKey);
            if (item == null) return;
            dao().markFailed(cacheKey, TextUtils.isEmpty(message) ? "Cached file is missing" : message, System.currentTimeMillis());
            CacheEvent.refresh();
            stopIfIdle();
        });
    }

    public void enqueue(CacheRequest request, EnqueueCallback callback) {
        serial.execute(() -> {
            CacheMetadata existing = dao().find(request.cacheKey());
            if (existing != null) {
                if (CacheMetadata.FAILED.equals(existing.getStatus()) || completedFileMissing(existing)) {
                    restart(existing, request, callback);
                } else {
                    success(callback, existing, true);
                }
                return;
            }
            long now = System.currentTimeMillis();
            CacheMetadata metadata = metadata(request, now);
            long inserted = dao().insert(metadata);
            if (inserted == -1) {
                success(callback, dao().find(request.cacheKey()), true);
                return;
            }
            CacheEvent.refresh();
            Operation operation = begin(metadata.getCacheKey(), Action.CREATE);
            create(metadata, request, callback, operation);
        });
    }

    public void pause(String cacheKey) {
        serial.execute(() -> control(cacheKey, true));
    }

    public void resume(String cacheKey) {
        serial.execute(() -> {
            Operation current = operations.get(cacheKey);
            if (current != null && current.action() != Action.PAUSE && current.action() != Action.RESUME) return;
            CacheMetadata item = dao().find(cacheKey);
            if (item == null || item.isCompleted()) return;
            if (CacheMetadata.FAILED.equals(item.getStatus()) || TextUtils.isEmpty(item.getFluxdownTaskId())) {
                restart(item, request(item), null);
            } else {
                control(cacheKey, false);
            }
        });
    }

    private void control(String cacheKey, boolean pause) {
        Operation current = operations.get(cacheKey);
        if (current != null && current.action() != Action.PAUSE && current.action() != Action.RESUME) return;
        CacheMetadata item = dao().find(cacheKey);
        if (item == null || item.isCompleted() || TextUtils.isEmpty(item.getFluxdownTaskId())) return;
        Operation control = begin(cacheKey, pause ? Action.PAUSE : Action.RESUME);
        try {
            CacheDownloadService.start(App.get());
            startEngine();
        } catch (RuntimeException e) {
            failControl(cacheKey, control, e.getMessage());
            return;
        }
        FluxDownEngine.Callback callback = operation(value -> serial.execute(() -> {
            if (!isCurrent(cacheKey, control)) return;
            end(cacheKey, control);
            CacheMetadata latest = dao().find(cacheKey);
            if (latest == null || latest.isCompleted()) return;
            dao().updateProgress(cacheKey, pause ? CacheMetadata.PAUSED : CacheMetadata.WAITING,
                    latest.getDownloadedBytes(), latest.getTotalBytes(), 0, System.currentTimeMillis(), null);
            CacheEvent.refresh();
            stopIfIdle();
        }), error -> serial.execute(() -> failControl(cacheKey, control, error)));
        if (pause) FluxDownEngine.pause(item.getFluxdownTaskId(), callback);
        else FluxDownEngine.resume(item.getFluxdownTaskId(), callback);
    }

    public void cancel(String cacheKey) {
        serial.execute(() -> {
            CacheMetadata item = dao().find(cacheKey);
            if (item == null || item.isCompleted()) return;
            Operation operation = begin(cacheKey, Action.CANCEL);
            if (TextUtils.isEmpty(item.getFluxdownTaskId())) {
                markCanceled(cacheKey, null);
                end(cacheKey, operation);
                return;
            }
            ignoredTaskIds.add(item.getFluxdownTaskId());
            pendingTasks.remove(item.getFluxdownTaskId());
            try {
                startEngine();
                FluxDownEngine.delete(item.getFluxdownTaskId(), true, operation(
                        value -> serial.execute(() -> finishCancel(cacheKey, operation, null)),
                        error -> serial.execute(() -> finishCancel(cacheKey, operation, error))));
            } catch (Exception e) {
                finishCancel(cacheKey, operation, e.getMessage());
            }
        });
    }

    public void delete(String cacheKey) {
        serial.execute(() -> {
            CacheMetadata item = dao().find(cacheKey);
            if (item == null) return;
            Operation operation = begin(cacheKey, Action.DELETE);
            if (TextUtils.isEmpty(item.getFluxdownTaskId())) {
                deleteOwned(item);
                end(cacheKey, operation);
                return;
            }
            ignoredTaskIds.add(item.getFluxdownTaskId());
            pendingTasks.remove(item.getFluxdownTaskId());
            try {
                startEngine();
                FluxDownEngine.delete(item.getFluxdownTaskId(), true, operation(
                        value -> serial.execute(() -> finishDelete(cacheKey, operation)),
                        error -> serial.execute(() -> finishDelete(cacheKey, operation))));
            } catch (Exception ignored) {
                // FluxDown history cleanup is best-effort and never blocks deleting FongMi-owned metadata/files.
                finishDelete(cacheKey, operation);
            }
        });
    }

    @Override
    public void onSnapshot(List<FluxTaskInfo> tasks) {
        serial.execute(() -> reconcile(tasks));
    }

    @Override
    public void onTaskChanged(FluxTaskInfo task) {
        serial.execute(() -> sync(task));
    }

    @Override
    public void onTaskDeleted(String taskId) {
        serial.execute(() -> {
            pendingTasks.remove(taskId);
            if (ignoredTaskIds.remove(taskId)) return;
            CacheMetadata item = dao().findByTaskId(taskId);
            if (item != null && !item.isCompleted()) {
                dao().markFailed(item.getCacheKey(), "FluxDown task was removed", System.currentTimeMillis());
                CacheEvent.refresh();
                stopIfIdle();
            }
        });
    }

    @Override
    public void onEngineError(String message) {
        serial.execute(() -> markActiveFailed(message));
    }

    private void reconcile(List<FluxTaskInfo> tasks) {
        Set<String> seen = new HashSet<>();
        List<CacheMetadata> active = new ArrayList<>(dao().findActive());
        for (FluxTaskInfo task : tasks) {
            if (ignoredTaskIds.contains(task.getTaskId())) continue;
            CacheMetadata item = dao().findByTaskId(task.getTaskId());
            if (item == null) item = recover(active, task);
            if (item == null) continue;
            seen.add(task.getTaskId());
            sync(task);
        }
        long now = System.currentTimeMillis();
        for (CacheMetadata item : active) {
            if (operations.containsKey(item.getCacheKey())) continue;
            String taskId = item.getFluxdownTaskId();
            if (!TextUtils.isEmpty(taskId) && !seen.contains(taskId)) {
                dao().markFailed(item.getCacheKey(), "FluxDown task not found after restart", now);
            } else if (TextUtils.isEmpty(taskId)) {
                // A live in-process create is protected by operations above.  With no operation,
                // the only remaining case is a crash between the Room insert and task binding.
                dao().markFailed(item.getCacheKey(), "FluxDown task id was not persisted", now);
            }
        }
        CacheEvent.refresh();
        stopIfIdle();
    }

    private CacheMetadata recover(List<CacheMetadata> candidates, FluxTaskInfo task) {
        List<CacheMetadata> matches = new ArrayList<>();
        for (CacheMetadata item : candidates) {
            if (isTerminating(item.getCacheKey())) continue;
            if (!TextUtils.isEmpty(item.getFluxdownTaskId())) continue;
            String url = TextUtils.isEmpty(task.getOriginUrl()) ? task.getUrl() : task.getOriginUrl();
            boolean engineNamedFile = TextUtils.isEmpty(item.getOutputFileName());
            boolean keyedFile = task.getFileName().contains(item.getCacheKey().substring(0, 12));
            if (item.getOriginalUrl().equals(url) && (engineNamedFile || keyedFile)) {
                matches.add(item);
            }
        }
        if (matches.size() != 1) return null;
        CacheMetadata item = matches.get(0);
        dao().bindTask(item.getCacheKey(), task.getTaskId(), System.currentTimeMillis());
        item.setFluxdownTaskId(task.getTaskId());
        return item;
    }

    private void sync(FluxTaskInfo task) {
        if (ignoredTaskIds.contains(task.getTaskId())) return;
        CacheMetadata item = dao().findByTaskId(task.getTaskId());
        if (item == null) {
            pendingTasks.put(task.getTaskId(), task);
            return;
        }
        // Failed/canceled rows are terminal until an explicit restart replaces the row and task id.
        if (CacheMetadata.FAILED.equals(item.getStatus())) return;
        if (isTerminating(item.getCacheKey())) return;
        pendingTasks.remove(task.getTaskId());
        long now = System.currentTimeMillis();
        if (!TextUtils.isEmpty(task.getFileName()) && !task.getFileName().equals(item.getOutputFileName())) {
            dao().updateFileName(item.getCacheKey(), task.getFileName(), now);
            item.setOutputFileName(task.getFileName());
        }
        if (task.getStatus() == 3) {
            try {
                if (task.getFileMissing()) throw new IllegalStateException("FluxDown reports the completed file missing");
                File file = CachePaths.requireReadableFile(new File(task.getSaveDir(), task.getFileName()));
                long size = file.length();
                dao().markCompleted(item.getCacheKey(), file.getAbsolutePath(), mime(file, item.getMimeType()),
                        size, task.getTotalBytes() > 0 ? task.getTotalBytes() : size,
                        task.getCompletedAt() > 0 ? task.getCompletedAt() * 1000 : now);
            } catch (Exception e) {
                dao().markFailed(item.getCacheKey(), e.getMessage(), now);
            }
        } else {
            dao().updateProgress(item.getCacheKey(), status(task.getStatus()), task.getDownloadedBytes(),
                    task.getTotalBytes(), task.getSpeedBytesPerSecond(), now, emptyToNull(task.getErrorMessage()));
        }
        CacheEvent.refresh();
        stopIfIdle();
    }

    private Operation begin(String cacheKey, Action action) {
        Operation operation = new Operation(++nextOperationId, action);
        operations.put(cacheKey, operation);
        return operation;
    }

    private boolean isCurrent(String cacheKey, Operation operation) {
        return operation.equals(operations.get(cacheKey));
    }

    private boolean isTerminating(String cacheKey) {
        Operation operation = operations.get(cacheKey);
        return operation != null && (operation.action() == Action.CANCEL || operation.action() == Action.DELETE);
    }

    private void end(String cacheKey, Operation operation) {
        if (isCurrent(cacheKey, operation)) operations.remove(cacheKey);
    }

    private void create(CacheMetadata metadata, CacheRequest request, EnqueueCallback callback, Operation operation) {
        try {
            CacheDownloadService.start(App.get());
            startEngine();
            FluxDownEngine.create(request.mediaUrl(), request.outputFileName(), CachePaths.root().getAbsolutePath(),
                    request.headers(), operation(
                            taskId -> serial.execute(() -> completeCreate(metadata.getCacheKey(), operation, taskId, callback)),
                            message -> serial.execute(() -> failCreate(metadata.getCacheKey(), operation, message, callback))));
        } catch (RuntimeException e) {
            failCreate(metadata.getCacheKey(), operation, e.getMessage(), callback);
        }
    }

    private void completeCreate(String cacheKey, Operation operation, String taskId, EnqueueCallback callback) {
        if (TextUtils.isEmpty(taskId)) {
            failCreate(cacheKey, operation, "FluxDown returned an empty task id", callback);
            return;
        }
        if (!isCurrent(cacheKey, operation)) {
            cleanupStaleTask(taskId);
            return;
        }
        CacheMetadata item = dao().find(cacheKey);
        if (item == null) {
            end(cacheKey, operation);
            cleanupStaleTask(taskId);
            return;
        }
        String currentTaskId = item.getFluxdownTaskId();
        if (TextUtils.isEmpty(currentTaskId)) {
            dao().bindTask(cacheKey, taskId, System.currentTimeMillis());
        } else if (!taskId.equals(currentTaskId)) {
            end(cacheKey, operation);
            cleanupStaleTask(taskId);
            error(callback, "FluxDown returned a duplicate task");
            return;
        }
        end(cacheKey, operation);
        FluxTaskInfo pending = pendingTasks.remove(taskId);
        if (pending != null) sync(pending);
        CacheMetadata latest = dao().find(cacheKey);
        CacheEvent.refresh();
        if (latest != null) success(callback, latest, false);
        else error(callback, "Cache metadata disappeared while creating the task");
    }

    private void failCreate(String cacheKey, Operation operation, String message, EnqueueCallback callback) {
        if (!isCurrent(cacheKey, operation)) return;
        end(cacheKey, operation);
        markFailedIfIncomplete(cacheKey, TextUtils.isEmpty(message) ? "Unable to create FluxDown task" : message);
        error(callback, TextUtils.isEmpty(message) ? "Unable to create FluxDown task" : message);
    }

    private void restart(CacheMetadata previous, CacheRequest request, EnqueueCallback callback) {
        String cacheKey = previous.getCacheKey();
        Operation operation = begin(cacheKey, Action.RESTART);
        String oldTaskId = previous.getFluxdownTaskId();
        if (!TextUtils.isEmpty(oldTaskId)) {
            ignoredTaskIds.add(oldTaskId);
            pendingTasks.remove(oldTaskId);
        }
        deleteOwnedFile(previous);
        long now = System.currentTimeMillis();
        CacheMetadata replacement = metadata(request, now);
        dao().delete(cacheKey);
        if (dao().insert(replacement) == -1) {
            failCreate(cacheKey, operation, "Unable to replace failed cache metadata", callback);
            return;
        }
        CacheEvent.refresh();
        if (TextUtils.isEmpty(oldTaskId)) {
            create(replacement, request, callback, operation);
            return;
        }
        try {
            CacheDownloadService.start(App.get());
            startEngine();
            FluxDownEngine.delete(oldTaskId, true, operation(
                    value -> serial.execute(() -> continueRestart(replacement, request, callback, operation)),
                    error -> serial.execute(() -> continueRestart(replacement, request, callback, operation))));
        } catch (RuntimeException ignored) {
            continueRestart(replacement, request, callback, operation);
        }
    }

    private void continueRestart(CacheMetadata metadata, CacheRequest request, EnqueueCallback callback, Operation operation) {
        if (!isCurrent(metadata.getCacheKey(), operation)) return;
        create(metadata, request, callback, operation);
    }

    private void cleanupStaleTask(String taskId) {
        if (TextUtils.isEmpty(taskId)) return;
        ignoredTaskIds.add(taskId);
        pendingTasks.remove(taskId);
        try {
            startEngine();
            FluxDownEngine.delete(taskId, true, operation(value -> {
            }, error -> {
            }));
        } catch (RuntimeException ignored) {
        }
    }

    private void finishCancel(String cacheKey, Operation operation, String cleanupError) {
        if (!isCurrent(cacheKey, operation)) return;
        markCanceled(cacheKey, cleanupError);
        end(cacheKey, operation);
    }

    private void finishDelete(String cacheKey, Operation operation) {
        if (!isCurrent(cacheKey, operation)) return;
        CacheMetadata latest = dao().find(cacheKey);
        if (latest != null) deleteOwned(latest);
        end(cacheKey, operation);
    }

    private void failControl(String cacheKey, Operation operation, String message) {
        if (!isCurrent(cacheKey, operation)) return;
        end(cacheKey, operation);
        markFailedIfIncomplete(cacheKey, message);
    }

    private void markFailedIfIncomplete(String cacheKey, String message) {
        CacheMetadata item = dao().find(cacheKey);
        if (item == null || item.isCompleted()) return;
        dao().markFailed(cacheKey, TextUtils.isEmpty(message) ? "FluxDown operation failed" : message,
                System.currentTimeMillis());
        CacheEvent.refresh();
        stopIfIdle();
    }

    private void markActiveFailed(String message) {
        long now = System.currentTimeMillis();
        String error = TextUtils.isEmpty(message) ? "FluxDown engine failed" : message;
        // DELETE/CANCEL own user-visible cleanup and must finish even when FluxDown dies;
        // otherwise a late command callback would be invalidated and local data could remain.
        for (Map.Entry<String, Operation> entry : new ArrayList<>(operations.entrySet())) {
            if (entry.getValue().action() == Action.DELETE) {
                CacheMetadata item = dao().find(entry.getKey());
                if (item != null) deleteOwned(item);
            } else if (entry.getValue().action() == Action.CANCEL) {
                markCanceled(entry.getKey(), error);
            }
        }
        // A fatal session cannot be allowed to keep foreground operations alive.
        // Late command callbacks are intentionally ignored after these tokens are cleared.
        operations.clear();
        pendingTasks.clear();
        for (CacheMetadata item : dao().findActive()) {
            if (!CacheMetadata.PAUSED.equals(item.getStatus())) dao().markFailed(item.getCacheKey(), error, now);
        }
        CacheEvent.refresh();
        stopIfIdle();
    }

    public void runIfIdle(Runnable callback) {
        serial.execute(() -> {
            if (dao().countRunning() == 0 && !hasForegroundOperation()) App.post(callback);
        });
    }

    private boolean hasForegroundOperation() {
        for (Operation operation : operations.values()) {
            if (operation.action() == Action.CREATE || operation.action() == Action.RESTART || operation.action() == Action.RESUME) {
                return true;
            }
        }
        return false;
    }

    private static CacheRequest request(CacheMetadata item) {
        return new CacheRequest(item.getCacheKey(), item.getTitle(), item.getPoster(), item.getSourceName(),
                item.getEpisodeName(), item.getOriginalUrl(), headers(item), item.getMimeType(), item.getOutputFileName());
    }

    private static void deleteOwnedFile(CacheMetadata item) {
        if (item.getLocalPath() == null) return;
        File file = new File(item.getLocalPath());
        if (file.exists() && CachePaths.isOwned(file)) file.delete();
    }

    private static boolean completedFileMissing(CacheMetadata item) {
        if (!item.isCompleted()) return false;
        try {
            CachePaths.requireReadableFile(new File(item.getLocalPath()));
            return false;
        } catch (Exception ignored) {
            return true;
        }
    }

    private void deleteOwned(CacheMetadata item) {
        File file = item.getLocalPath() == null ? null : new File(item.getLocalPath());
        if (file != null && file.exists() && (!CachePaths.isOwned(file) || !file.delete())) {
            dao().markFailed(item.getCacheKey(), "Unable to delete cached file", System.currentTimeMillis());
        } else {
            dao().delete(item.getCacheKey());
        }
        CacheEvent.refresh();
        stopIfIdle();
    }

    private void markCanceled(String cacheKey, String cleanupError) {
        String message = TextUtils.isEmpty(cleanupError) ? "Canceled" : "Canceled; FluxDown cleanup failed: " + cleanupError;
        dao().markFailed(cacheKey, message, System.currentTimeMillis());
        CacheEvent.refresh();
        stopIfIdle();
    }

    private static CacheMetadata metadata(CacheRequest request, long now) {
        CacheMetadata item = new CacheMetadata();
        item.setCacheKey(request.cacheKey());
        item.setTitle(request.title());
        item.setPoster(request.poster());
        item.setSourceName(request.sourceName());
        item.setEpisodeName(request.episodeName());
        item.setOriginalUrl(request.mediaUrl());
        item.setHeadersJson(App.gson().toJson(request.headers()));
        item.setOutputFileName(request.outputFileName());
        item.setMimeType(request.mimeType());
        item.setStatus(CacheMetadata.WAITING);
        item.setCreateTime(now);
        item.setUpdateTime(now);
        return item;
    }

    public static Map<String, String> headers(CacheMetadata item) {
        if (item == null || TextUtils.isEmpty(item.getHeadersJson())) return Collections.emptyMap();
        try {
            Map<String, String> value = App.gson().fromJson(item.getHeadersJson(), HEADERS_TYPE);
            return value == null ? Collections.emptyMap() : value;
        } catch (Exception ignored) {
            return Collections.emptyMap();
        }
    }

    private static String status(int value) {
        return switch (value) {
            case 1 -> CacheMetadata.DOWNLOADING;
            case 2 -> CacheMetadata.PAUSED;
            case 3 -> CacheMetadata.COMPLETED;
            case 4 -> CacheMetadata.FAILED;
            default -> CacheMetadata.WAITING;
        };
    }

    private static String mime(File file, String fallback) {
        String lower = file.getName().toLowerCase(Locale.ROOT);
        if (lower.endsWith(".ts")) return "video/mp2t";
        if (lower.endsWith(".mp4")) return "video/mp4";
        String detected = URLConnection.guessContentTypeFromName(file.getName());
        return detected == null ? fallback : detected;
    }

    private static String emptyToNull(String value) {
        return TextUtils.isEmpty(value) ? null : value;
    }

    private static FluxDownEngine.Callback operation(Consumer<String> success, Consumer<String> error) {
        return new FluxDownEngine.Callback() {
            @Override
            public void onSuccess(String value) {
                success.accept(value);
            }

            @Override
            public void onError(String message) {
                error.accept(message);
            }
        };
    }

    private void stopIfIdle() {
        if (dao().countRunning() == 0) App.post(() -> CacheDownloadService.stop(App.get()));
    }

    private static void success(EnqueueCallback callback, CacheMetadata metadata, boolean existed) {
        if (callback != null) App.post(() -> callback.onSuccess(metadata, existed));
    }

    private static void error(EnqueueCallback callback, String message) {
        if (callback != null) App.post(() -> callback.onError(message));
    }

    private static CacheMetadataDao dao() {
        return AppDatabase.get().getCacheMetadataDao();
    }
}

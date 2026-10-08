package com.fongmi.android.tv.cache;

import android.os.Build;
import android.text.TextUtils;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.BuildConfig;
import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.bean.Site;
import com.fongmi.android.tv.db.AppDatabase;
import com.fongmi.android.tv.server.Server;
import com.fongmi.android.tv.service.CacheDownloadService;
import com.fongmi.fluxdown.FFmpegProbe;
import com.fongmi.fluxdown.FFmpegTsNormalizer;
import com.fongmi.fluxdown.FluxDownEngine;
import com.fongmi.fluxdown.FluxTaskInfo;
import com.fongmi.fluxdown.PngTsCleaner;
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
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/** Owns FongMi metadata and joins it to FluxDown's persistent task stream by task UUID. */
public final class CacheRepository implements FluxDownEngine.Listener {

    public interface EnqueueCallback {
        void onSuccess(CacheMetadata metadata, boolean existed);

        void onError(String message);
    }

    private static final Type HEADERS_TYPE = new TypeToken<Map<String, String>>() {}.getType();

    private final ExecutorService serial = Executors.newSingleThreadExecutor();
    private final ExecutorService mediaExecutor = Executors.newSingleThreadExecutor();
    private final Map<String, FluxTaskInfo> pendingTasks = new HashMap<>();
    private final Map<String, Operation> operations = new HashMap<>();
    private final Set<String> ignoredTaskIds = new HashSet<>();
    private final Set<String> completionChecks = new HashSet<>();
    private final Map<String, CompletionJob> completionJobs = new HashMap<>();
    private final AtomicLong idleActionVersion = new AtomicLong();
    private PendingCleanup pendingCleanup;
    private long nextOperationId;

    private enum Action { CREATE, RESTART, PAUSE, RESUME, CANCEL, DELETE, COMPLETE }

    private record Operation(long id, Action action) {
    }

    private static final class CompletionJob {
        final String cacheKey;
        final String taskId;
        final Operation operation;
        Future<?> future;

        CompletionJob(String cacheKey, String taskId, Operation operation) {
            this.cacheKey = cacheKey;
            this.taskId = taskId;
            this.operation = operation;
        }
    }

    private record PendingCleanup(long version, Runnable callback) {
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
                cancelPendingCleanup();
                List<CacheMetadata> active = dao().findActive();
                prepareProxySource(active);
                String serverBaseUrl = Server.get().getAddress(true);
                for (CacheMetadata item : active) {
                    if (CacheMetadata.PAUSED.equals(item.getStatus()) || !proxyEndpointChanged(item, serverBaseUrl)) continue;
                    restart(item, request(item), null);
                }
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
        FluxDownEngine.start(data.getAbsolutePath(), CachePaths.root().getAbsolutePath(),
                App.get().getApplicationInfo().nativeLibraryDir, this);
    }

    public void load(Consumer<List<CacheMetadata>> callback) {
        serial.execute(() -> {
            List<CacheMetadata> items = dao().findAll();
            boolean refreshed = false;
            for (CacheMetadata item : items) {
                if (!item.isCompleted()) continue;
                try {
                    File file = CachePaths.requireReadableFile(new File(item.getLocalPath()));
                    String streamMimeType = CacheFileValidator.streamingMimeType(file);
                    if (streamMimeType != null) {
                        dao().markIncomplete(item.getCacheKey(), streamMimeType,
                                CacheFileValidator.INCOMPLETE_MESSAGE, System.currentTimeMillis());
                        refreshed = true;
                    } else {
                        CacheFileValidator.requireOfflineMedia(file);
                        CacheTrackValidator.requireCompleteTracks(file, item.getOriginalUrl(), item.getMimeType());
                    }
                } catch (Exception e) {
                    dao().markFailed(item.getCacheKey(), completionError(e), System.currentTimeMillis());
                    refreshed = true;
                }
            }
            if (refreshed) {
                items = dao().findAll();
                CacheEvent.refresh();
            }
            List<CacheMetadata> result = items;
            App.post(() -> callback.accept(result));
        });
    }

    public void invalidateCompleted(String cacheKey, String message) {
        serial.execute(() -> {
            CacheMetadata item = dao().findCompleted(cacheKey);
            if (item == null) return;
            String error = TextUtils.isEmpty(message) ? "Cached file is missing" : message;
            try {
                String streamMimeType = CacheFileValidator.streamingMimeType(new File(item.getLocalPath()));
                if (streamMimeType != null) dao().markIncomplete(cacheKey, streamMimeType, error, System.currentTimeMillis());
                else dao().markFailed(cacheKey, error, System.currentTimeMillis());
            } catch (Exception ignored) {
                dao().markFailed(cacheKey, error, System.currentTimeMillis());
            }
            CacheEvent.refresh();
            stopIfIdle();
        });
    }

    public void enqueue(CacheRequest request, EnqueueCallback callback) {
        cancelPendingCleanup();
        serial.execute(() -> {
            CacheMetadata existing = dao().find(request.cacheKey());
            if (existing != null) {
                if (CacheMetadata.FAILED.equals(existing.getStatus()) || completedFileInvalid(existing)) {
                    restart(existing, withRecoveredMimeType(existing, request), callback);
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
        cancelPendingCleanup();
        serial.execute(() -> {
            Operation current = operations.get(cacheKey);
            if (current != null && current.action() != Action.PAUSE && current.action() != Action.RESUME) return;
            CacheMetadata item = dao().find(cacheKey);
            if (item == null || item.isCompleted()) return;
            try {
                boolean legacyProxy = CacheMediaUrl.isLocalProxy(item.getOriginalUrl()) && !CacheMediaUrl.hasProxyContext(item.getOriginalUrl());
                boolean movedProxy = false;
                if (CacheMediaUrl.isLocalProxy(item.getOriginalUrl())) {
                    prepareProxySource(item.getOriginalUrl());
                    movedProxy = proxyEndpointChanged(item, Server.get().getAddress(true));
                }
                if (CacheMetadata.FAILED.equals(item.getStatus()) || TextUtils.isEmpty(item.getFluxdownTaskId()) || legacyProxy || movedProxy) {
                    restart(item, request(item), null);
                } else {
                    control(cacheKey, false);
                }
            } catch (RuntimeException e) {
                markFailedIfIncomplete(cacheKey, e.getMessage());
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
            if (!pause) prepareProxySource(item.getOriginalUrl());
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
            completionChecks.remove(taskId);
            if (ignoredTaskIds.remove(taskId)) return;
            CacheMetadata item = dao().findByTaskId(taskId);
            if (item != null) cancelCompletion(item.getCacheKey());
            if (item != null && !item.isCompleted() && !CacheMetadata.FAILED.equals(item.getStatus())) {
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
            String mediaUrl = CacheMediaUrl.onLocalServer(item.getOriginalUrl(), Server.get().getAddress(true));
            String downloadUrl = CacheMediaUrl.forDownload(mediaUrl, item.getMimeType());
            if (downloadUrl.equals(url) && (engineNamedFile || keyedFile)) {
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
        // Failed/canceled/completed rows are terminal until an explicit restart replaces the row and task id.
        if (CacheMetadata.FAILED.equals(item.getStatus()) || item.isCompleted()) return;
        if (isTerminating(item.getCacheKey())) return;
        Operation current = operations.get(item.getCacheKey());
        // Native progress must not undo application-side completion validation/remuxing.
        if (current != null && current.action() == Action.COMPLETE) return;
        pendingTasks.remove(task.getTaskId());
        long now = System.currentTimeMillis();
        if (!TextUtils.isEmpty(task.getFileName()) && !task.getFileName().equals(item.getOutputFileName())) {
            dao().updateFileName(item.getCacheKey(), task.getFileName(), now);
            item.setOutputFileName(task.getFileName());
        }
        if (task.getStatus() == 3) {
            if (current != null && (current.action() == Action.CREATE || current.action() == Action.RESTART)) {
                pendingTasks.put(task.getTaskId(), task);
                return;
            }
            if (!completionChecks.add(task.getTaskId())) return;
            String cacheKey = item.getCacheKey();
            Operation completion = begin(cacheKey, Action.COMPLETE);
            dao().updateProgress(cacheKey, CacheMetadata.DOWNLOADING, task.getDownloadedBytes(),
                    task.getTotalBytes(), 0, now, null);
            CacheEvent.refresh();
            try {
                FluxDownEngine.offlineMediaWarning(task.getTaskId(), operation(
                        warning -> serial.execute(() -> complete(cacheKey, completion, task, warning)),
                        error -> serial.execute(() -> complete(cacheKey, completion, task,
                                "Unable to verify audio completeness: " + error))));
            } catch (RuntimeException error) {
                complete(cacheKey, completion, task, "Unable to verify audio completeness: " + completionError(error));
            }
            return;
        }
        dao().updateProgress(item.getCacheKey(), status(task.getStatus()), task.getDownloadedBytes(),
                task.getTotalBytes(), task.getSpeedBytesPerSecond(), now, emptyToNull(task.getErrorMessage()));
        CacheEvent.refresh();
        stopIfIdle();
    }

    private void complete(String cacheKey, Operation operation, FluxTaskInfo task, String offlineWarning) {
        if (!isCurrent(cacheKey, operation)) return;
        CacheMetadata item = completionItem(cacheKey, task.getTaskId());
        if (item == null) {
            completionChecks.remove(task.getTaskId());
            end(cacheKey, operation);
            stopIfIdle();
            return;
        }
        if (completionJobs.containsKey(cacheKey)) return;
        CompletionJob job = new CompletionJob(cacheKey, task.getTaskId(), operation);
        completionJobs.put(cacheKey, job);
        try {
            job.future = mediaExecutor.submit(() -> prepareCompletion(job, item, task, offlineWarning));
        } catch (RuntimeException error) {
            finishCompletion(job, task, null, null, null, completionError(error));
        }
    }

    // This worker owns only temporary files. It must never replace the source or mutate Room.
    private void prepareCompletion(CompletionJob job, CacheMetadata item, FluxTaskInfo task, String offlineWarning) {
        File candidate = null;
        FFmpegTsNormalizer.Prepared prepared = null;
        String streamMimeType = null;
        String error = null;
        try {
            if (task.getFileName() != null) candidate = new File(task.getSaveDir(), task.getFileName());
            if (task.getFileMissing()) throw new IllegalStateException("FluxDown reports the completed file missing");
            candidate = CachePaths.requireReadableFile(candidate);
            streamMimeType = CacheFileValidator.streamingMimeType(candidate);
            if (streamMimeType == null) {
                CacheFileValidator.requireOfflineMedia(candidate);
                if (!TextUtils.isEmpty(offlineWarning)) {
                    throw new IllegalStateException("Offline cache is incomplete: " + offlineWarning);
                }
                if (PngTsCleaner.hasPngPrefix(candidate)) {
                    prepared = FFmpegTsNormalizer.prepare(
                            new File(App.get().getApplicationInfo().nativeLibraryDir, "libffmpeg.so"), candidate,
                            output -> {
                                CacheFileValidator.requireOfflineMedia(output);
                                CacheTrackValidator.requireCompleteTracks(output, item.getOriginalUrl(), item.getMimeType());
                            });
                } else {
                    CacheTrackValidator.requireCompleteTracks(candidate, item.getOriginalUrl(), item.getMimeType());
                }
            }
        } catch (Exception failure) {
            // Cancellation must not launch a second diagnostic process while stopping FFmpeg.
            error = Thread.currentThread().isInterrupted() ? completionError(failure)
                    : completionDiagnostics(failure, candidate, item, offlineWarning);
        }
        File resultFile = candidate;
        FFmpegTsNormalizer.Prepared resultPrepared = prepared;
        String resultMime = streamMimeType;
        String resultError = error;
        serial.execute(() -> finishCompletion(job, task, resultFile, resultPrepared, resultMime, resultError));
    }

    private CacheMetadata completionItem(String cacheKey, String taskId) {
        if (ignoredTaskIds.contains(taskId)) return null;
        CacheMetadata item = dao().find(cacheKey);
        if (item == null || !taskId.equals(item.getFluxdownTaskId()) || item.isCompleted()
                || CacheMetadata.FAILED.equals(item.getStatus())) return null;
        return item;
    }

    private void finishCompletion(CompletionJob job, FluxTaskInfo task, File candidate,
                                  FFmpegTsNormalizer.Prepared prepared, String streamMimeType, String error) {
        try {
            if (!isCurrent(job.cacheKey, job.operation) || completionJobs.get(job.cacheKey) != job) return;
            CacheMetadata item = completionItem(job.cacheKey, job.taskId);
            if (item == null) return;
            long now = System.currentTimeMillis();
            if (error != null) {
                dao().markFailed(job.cacheKey, error, now);
            } else if (streamMimeType != null) {
                String previousUrl = CacheMediaUrl.onLocalServer(item.getOriginalUrl(), Server.get().getAddress(true));
                boolean retryWithDetectedType = !CacheMediaUrl.forDownload(previousUrl, item.getMimeType())
                        .equals(CacheMediaUrl.forDownload(previousUrl, streamMimeType));
                dao().markIncomplete(job.cacheKey, streamMimeType,
                        CacheFileValidator.INCOMPLETE_MESSAGE, now);
                if (retryWithDetectedType) {
                    CacheMetadata retry = dao().find(job.cacheKey);
                    if (retry != null) {
                        endCompletion(job);
                        restart(retry, request(retry), null);
                    }
                }
            } else {
                // Cancellation/deletion and this commit share the serial executor. Recheck above
                // before installing the independently validated file at the original path.
                if (prepared != null) prepared.commit();
                File file = CachePaths.requireReadableFile(candidate);
                long size = file.length();
                long total = prepared != null || task.getTotalBytes() <= 0 ? size : task.getTotalBytes();
                dao().markCompleted(job.cacheKey, file.getAbsolutePath(), mime(file, item.getMimeType()),
                        size, total,
                        task.getCompletedAt() > 0 ? task.getCompletedAt() * 1000 : now);
            }
        } catch (Exception failure) {
            if (isCurrent(job.cacheKey, job.operation) && completionItem(job.cacheKey, job.taskId) != null) {
                dao().markFailed(job.cacheKey, completionError(failure), System.currentTimeMillis());
            }
        } finally {
            if (prepared != null) {
                try {
                    prepared.close();
                } catch (Exception cleanupError) {
                    android.util.Log.w("CacheRepository", "Unable to remove normalization temporary", cleanupError);
                }
            }
            endCompletion(job);
            CacheEvent.refresh();
            stopIfIdle();
        }
    }

    private void endCompletion(CompletionJob job) {
        if (completionJobs.get(job.cacheKey) == job) completionJobs.remove(job.cacheKey);
        if (isCurrent(job.cacheKey, job.operation)) {
            completionChecks.remove(job.taskId);
            end(job.cacheKey, job.operation);
        }
    }

    private void cancelCompletion(String cacheKey) {
        CompletionJob job = completionJobs.remove(cacheKey);
        if (job != null && job.future != null) job.future.cancel(true);
        Operation current = operations.get(cacheKey);
        if (current == null || current.action() != Action.COMPLETE) return;
        CacheMetadata item = dao().find(cacheKey);
        if (item != null) completionChecks.remove(item.getFluxdownTaskId());
        end(cacheKey, current);
    }

    private Operation begin(String cacheKey, Action action) {
        cancelCompletion(cacheKey);
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
            prepareProxySource(request.mediaUrl());
            String mediaUrl = CacheMediaUrl.onLocalServer(request.mediaUrl(), Server.get().getAddress(true));
            String downloadUrl = CacheMediaUrl.requireSupportedDownloadUrl(mediaUrl, request.mimeType());
            if (!downloadUrl.equals(metadata.getOriginalUrl())) {
                dao().updateOriginalUrl(metadata.getCacheKey(), downloadUrl, System.currentTimeMillis());
                metadata.setOriginalUrl(downloadUrl);
            }
            CacheDownloadService.start(App.get());
            startEngine();
            FluxDownEngine.create(downloadUrl, request.outputFileName(), CachePaths.root().getAbsolutePath(),
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
        for (String cacheKey : new ArrayList<>(completionJobs.keySet())) cancelCompletion(cacheKey);
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
        completionChecks.clear();
        for (CacheMetadata item : dao().findActive()) {
            if (!CacheMetadata.PAUSED.equals(item.getStatus())) dao().markFailed(item.getCacheKey(), error, now);
        }
        CacheEvent.refresh();
        stopIfIdle();
    }

    public void runIfIdle(Runnable callback) {
        serial.execute(() -> {
            if (isEngineIdle()) App.post(callback);
        });
    }

    public void runCleanupIfCacheInactive(Runnable callback) {
        if (callback == null) return;
        long version = idleActionVersion.incrementAndGet();
        serial.execute(() -> {
            if (version != idleActionVersion.get()) return;
            pendingCleanup = new PendingCleanup(version, callback);
            runPendingCleanupIfCacheInactive();
        });
    }

    public void cancelPendingCleanup() {
        idleActionVersion.incrementAndGet();
        serial.execute(() -> pendingCleanup = null);
        Server.get().retain();
    }

    private boolean hasForegroundOperation() {
        for (Operation operation : operations.values()) {
            if (operation.action() == Action.CREATE || operation.action() == Action.RESTART
                    || operation.action() == Action.RESUME || operation.action() == Action.COMPLETE) {
                return true;
            }
        }
        return false;
    }

    private boolean isEngineIdle() {
        return dao().countRunning() == 0 && !hasForegroundOperation();
    }

    private boolean isCacheInactive() {
        return dao().findActive().isEmpty() && !hasForegroundOperation();
    }

    private void runPendingCleanupIfCacheInactive() {
        PendingCleanup action = pendingCleanup;
        if (action == null || action.version() != idleActionVersion.get() || !isCacheInactive()) return;
        pendingCleanup = null;
        App.post(() -> {
            if (action.version() == idleActionVersion.get()) action.callback().run();
        });
    }

    private static CacheRequest request(CacheMetadata item) {
        String mediaUrl = recoverProxyUrl(item);
        return new CacheRequest(item.getCacheKey(), item.getTitle(), item.getPoster(), item.getSourceName(),
                item.getEpisodeName(), mediaUrl, headers(item), item.getMimeType(), item.getOutputFileName());
    }

    private static String recoverProxyUrl(CacheMetadata item) {
        String url = item.getOriginalUrl();
        if (!CacheMediaUrl.isLocalProxy(url) || CacheMediaUrl.hasProxyContext(url)) return url;
        VodConfig.get().ensureLoaded();
        Site site = findSite(item.getSourceName());
        if (site == null) throw new IllegalStateException("Cache source is unavailable; cache the video again from the playback page");
        return CacheMediaUrl.withProxySite(url, site.getKey());
    }

    private static Site findSite(String sourceName) {
        if (TextUtils.isEmpty(sourceName)) return null;
        for (Site site : VodConfig.get().getSites()) if (sourceName.equals(site.getKey())) return site;
        Site match = null;
        for (Site site : VodConfig.get().getSites()) {
            if (!sourceName.equals(site.getName())) continue;
            if (match != null) return null;
            match = site;
        }
        return match;
    }

    private static boolean proxyEndpointChanged(CacheMetadata item, String serverBaseUrl) {
        return CacheMediaUrl.isLocalProxy(item.getOriginalUrl())
                && (!CacheMediaUrl.hasProxyContext(item.getOriginalUrl())
                || !CacheMediaUrl.isOnLocalServer(item.getOriginalUrl(), serverBaseUrl));
    }

    private static CacheRequest withRecoveredMimeType(CacheMetadata previous, CacheRequest request) {
        if (!TextUtils.isEmpty(request.mimeType())) return request;
        String mimeType = previous.getMimeType();
        if (TextUtils.isEmpty(mimeType) && !TextUtils.isEmpty(previous.getLocalPath())) {
            try {
                File file = CachePaths.requireReadableFile(new File(previous.getLocalPath()));
                mimeType = CacheFileValidator.streamingMimeType(file);
            } catch (Exception ignored) {
            }
        }
        if (TextUtils.isEmpty(mimeType)) return request;
        return new CacheRequest(request.cacheKey(), request.title(), request.poster(), request.sourceName(),
                request.episodeName(), request.mediaUrl(), request.headers(), mimeType, "");
    }

    private static void prepareProxySource(List<CacheMetadata> items) {
        for (CacheMetadata item : items) {
            if (!CacheMediaUrl.isLocalProxy(item.getOriginalUrl())) continue;
            prepareProxySource(item.getOriginalUrl());
        }
    }

    private static void prepareProxySource(String url) {
        if (!CacheMediaUrl.isLocalProxy(url)) return;
        VodConfig.get().ensureLoaded();
        Server.get().start();
        if (!Server.get().isRunning()) throw new IllegalStateException("Local media proxy is unavailable");
    }

    private static void deleteOwnedFile(CacheMetadata item) {
        if (item.getLocalPath() == null) return;
        File file = new File(item.getLocalPath());
        if (file.exists() && CachePaths.isOwned(file)) file.delete();
    }

    private static boolean completedFileInvalid(CacheMetadata item) {
        if (!item.isCompleted()) return false;
        try {
            File file = CachePaths.requireReadableFile(new File(item.getLocalPath()));
            CacheFileValidator.requireOfflineMedia(file);
            CacheTrackValidator.requireCompleteTracks(file, item.getOriginalUrl(), item.getMimeType());
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

    private static String mime(File file, String fallback) throws java.io.IOException {
        String container = CacheFileValidator.mediaMimeType(file);
        if (container != null) return container;
        String lower = file.getName().toLowerCase(Locale.ROOT);
        if (lower.endsWith(".ts")) return "video/mp2t";
        if (lower.endsWith(".mp4")) return "video/mp4";
        String detected = URLConnection.guessContentTypeFromName(file.getName());
        if (detected != null) return detected;
        // A source manifest MIME describes the download protocol, not the completed media file.
        return CacheMediaUrl.isAdaptiveStream(null, fallback) ? "application/octet-stream" : fallback;
    }

    private static String emptyToNull(String value) {
        return TextUtils.isEmpty(value) ? null : value;
    }

    private static String completionError(Exception error) {
        String message = error == null ? null : error.getMessage();
        return TextUtils.isEmpty(message) ? "Cached file is missing, unreadable, or incomplete" : message;
    }

    private static String completionDiagnostics(Exception error, File candidate, CacheMetadata item, String warning) {
        try {
            File owned = null;
            if (candidate != null) {
                try {
                    owned = CachePaths.requireOwned(candidate);
                } catch (Exception ignored) {
                    // Display the attempted path, but never read or probe a file outside our cache.
                }
            }
            String device = Build.MANUFACTURER + " " + Build.MODEL + " / Android " + Build.VERSION.RELEASE
                    + " / API " + Build.VERSION.SDK_INT + " / app " + BuildConfig.VERSION_NAME
                    + " " + BuildConfig.FLAVOR + " " + BuildConfig.BUILD_TYPE;
            return CacheCompletionDiagnostics.describe(error, owned,
                    candidate == null ? null : candidate.getAbsolutePath(), item.getOriginalUrl(), item.getMimeType(),
                    warning, device, file -> FFmpegProbe.inspect(
                            new File(App.get().getApplicationInfo().nativeLibraryDir, "libffmpeg.so"), file));
        } catch (RuntimeException diagnosticError) {
            // Diagnostic collection must never hide the actual completion failure.
            return completionError(error) + "\n" + CacheCompletionDiagnostics.TAG
                    + "\nDiagnostics unavailable: " + diagnosticError;
        }
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
        if (isEngineIdle()) App.post(() -> CacheDownloadService.stop(App.get()));
        runPendingCleanupIfCacheInactive();
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

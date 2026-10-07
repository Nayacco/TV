package com.fongmi.android.tv.cache;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

@Entity(tableName = "CacheMetadata", indices = {@Index(value = "fluxdownTaskId", unique = true), @Index("status")})
public class CacheMetadata {

    public static final String WAITING = "waiting";
    public static final String DOWNLOADING = "downloading";
    public static final String PAUSED = "paused";
    public static final String COMPLETED = "completed";
    public static final String FAILED = "failed";

    @NonNull
    @PrimaryKey
    private String cacheKey = "";
    @NonNull
    private String title = "";
    @Nullable
    private String poster;
    @Nullable
    private String sourceName;
    @Nullable
    private String episodeName;
    @NonNull
    private String originalUrl = "";
    @NonNull
    private String headersJson = "{}";
    @NonNull
    private String outputFileName = "";
    @Nullable
    private String localPath;
    @Nullable
    private String mimeType;
    @Nullable
    private String fluxdownTaskId;
    @NonNull
    private String status = WAITING;
    private long downloadedBytes;
    private long totalBytes;
    private long speedBytesPerSecond;
    private long createTime;
    private long updateTime;
    @Nullable
    private Long completeTime;
    @Nullable
    private String errorMessage;

    @NonNull
    public String getCacheKey() {
        return cacheKey;
    }

    public void setCacheKey(@NonNull String cacheKey) {
        this.cacheKey = cacheKey;
    }

    @NonNull
    public String getTitle() {
        return title;
    }

    public void setTitle(@NonNull String title) {
        this.title = title;
    }

    @Nullable
    public String getPoster() {
        return poster;
    }

    public void setPoster(@Nullable String poster) {
        this.poster = poster;
    }

    @Nullable
    public String getSourceName() {
        return sourceName;
    }

    public void setSourceName(@Nullable String sourceName) {
        this.sourceName = sourceName;
    }

    @Nullable
    public String getEpisodeName() {
        return episodeName;
    }

    public void setEpisodeName(@Nullable String episodeName) {
        this.episodeName = episodeName;
    }

    @NonNull
    public String getOriginalUrl() {
        return originalUrl;
    }

    public void setOriginalUrl(@NonNull String originalUrl) {
        this.originalUrl = originalUrl;
    }

    @NonNull
    public String getHeadersJson() {
        return headersJson;
    }

    public void setHeadersJson(@NonNull String headersJson) {
        this.headersJson = headersJson;
    }

    @NonNull
    public String getOutputFileName() {
        return outputFileName;
    }

    public void setOutputFileName(@NonNull String outputFileName) {
        this.outputFileName = outputFileName;
    }

    @Nullable
    public String getLocalPath() {
        return localPath;
    }

    public void setLocalPath(@Nullable String localPath) {
        this.localPath = localPath;
    }

    @Nullable
    public String getMimeType() {
        return mimeType;
    }

    public void setMimeType(@Nullable String mimeType) {
        this.mimeType = mimeType;
    }

    @Nullable
    public String getFluxdownTaskId() {
        return fluxdownTaskId;
    }

    public void setFluxdownTaskId(@Nullable String fluxdownTaskId) {
        this.fluxdownTaskId = fluxdownTaskId;
    }

    @NonNull
    public String getStatus() {
        return status;
    }

    public void setStatus(@NonNull String status) {
        this.status = status;
    }

    public long getDownloadedBytes() {
        return downloadedBytes;
    }

    public void setDownloadedBytes(long downloadedBytes) {
        this.downloadedBytes = downloadedBytes;
    }

    public long getTotalBytes() {
        return totalBytes;
    }

    public void setTotalBytes(long totalBytes) {
        this.totalBytes = totalBytes;
    }

    public long getSpeedBytesPerSecond() {
        return speedBytesPerSecond;
    }

    public void setSpeedBytesPerSecond(long speedBytesPerSecond) {
        this.speedBytesPerSecond = speedBytesPerSecond;
    }

    public long getCreateTime() {
        return createTime;
    }

    public void setCreateTime(long createTime) {
        this.createTime = createTime;
    }

    public long getUpdateTime() {
        return updateTime;
    }

    public void setUpdateTime(long updateTime) {
        this.updateTime = updateTime;
    }

    @Nullable
    public Long getCompleteTime() {
        return completeTime;
    }

    public void setCompleteTime(@Nullable Long completeTime) {
        this.completeTime = completeTime;
    }

    @Nullable
    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(@Nullable String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public boolean isCompleted() {
        return COMPLETED.equals(status);
    }
}

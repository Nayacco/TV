package com.fongmi.android.tv.cache;

import androidx.room.Dao;
import androidx.room.Query;

import com.fongmi.android.tv.db.dao.BaseDao;

import java.util.List;

@Dao
public abstract class CacheMetadataDao extends BaseDao<CacheMetadata> {

    @Query("SELECT * FROM CacheMetadata ORDER BY createTime DESC")
    public abstract List<CacheMetadata> findAll();

    @Query("SELECT * FROM CacheMetadata WHERE status IN ('waiting', 'downloading', 'paused') ORDER BY createTime DESC")
    public abstract List<CacheMetadata> findActive();

    @Query("SELECT COUNT(*) FROM CacheMetadata WHERE status IN ('waiting', 'downloading')")
    public abstract int countRunning();

    @Query("SELECT * FROM CacheMetadata WHERE cacheKey = :cacheKey LIMIT 1")
    public abstract CacheMetadata find(String cacheKey);

    @Query("SELECT * FROM CacheMetadata WHERE cacheKey = :cacheKey AND status = 'completed' AND localPath IS NOT NULL LIMIT 1")
    public abstract CacheMetadata findCompleted(String cacheKey);

    @Query("SELECT * FROM CacheMetadata WHERE fluxdownTaskId = :taskId LIMIT 1")
    public abstract CacheMetadata findByTaskId(String taskId);

    @Query("UPDATE CacheMetadata SET fluxdownTaskId = :taskId, status = 'waiting', updateTime = :updateTime, errorMessage = NULL WHERE cacheKey = :cacheKey")
    public abstract int bindTask(String cacheKey, String taskId, long updateTime);

    @Query("UPDATE CacheMetadata SET outputFileName = :fileName, updateTime = :updateTime WHERE cacheKey = :cacheKey")
    public abstract int updateFileName(String cacheKey, String fileName, long updateTime);

    @Query("UPDATE CacheMetadata SET status = :status, downloadedBytes = :downloadedBytes, totalBytes = :totalBytes, speedBytesPerSecond = :speedBytesPerSecond, updateTime = :updateTime, errorMessage = :errorMessage WHERE cacheKey = :cacheKey AND status != 'completed'")
    public abstract int updateProgress(String cacheKey, String status, long downloadedBytes, long totalBytes, long speedBytesPerSecond, long updateTime, String errorMessage);

    @Query("UPDATE CacheMetadata SET status = 'completed', localPath = :localPath, mimeType = :mimeType, downloadedBytes = :downloadedBytes, totalBytes = :totalBytes, speedBytesPerSecond = 0, updateTime = :completeTime, completeTime = :completeTime, errorMessage = NULL WHERE cacheKey = :cacheKey")
    public abstract int markCompleted(String cacheKey, String localPath, String mimeType, long downloadedBytes, long totalBytes, long completeTime);

    @Query("UPDATE CacheMetadata SET status = 'failed', speedBytesPerSecond = 0, updateTime = :updateTime, errorMessage = :errorMessage WHERE cacheKey = :cacheKey")
    public abstract int markFailed(String cacheKey, String errorMessage, long updateTime);

    @Query("DELETE FROM CacheMetadata WHERE cacheKey = :cacheKey")
    public abstract int delete(String cacheKey);
}

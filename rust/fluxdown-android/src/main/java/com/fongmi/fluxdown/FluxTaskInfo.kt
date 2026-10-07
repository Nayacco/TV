package com.fongmi.fluxdown

/**
 * Stable Java-facing task projection; upstream UniFFI/Core types do not escape this module.
 * Status is the FluxDown wire value: 0 pending, 1 downloading, 2 paused, 3 completed,
 * 4 failed, 5 preparing. [createdAt] and [completedAt] are Unix seconds (`0` means absent).
 */
data class FluxTaskInfo(
    val taskId: String,
    val url: String,
    val originUrl: String,
    val fileName: String,
    val saveDir: String,
    val status: Int,
    val downloadedBytes: Long,
    val totalBytes: Long,
    val speedBytesPerSecond: Long,
    val errorMessage: String,
    val createdAt: Long,
    val completedAt: Long,
    val fileMissing: Boolean,
)

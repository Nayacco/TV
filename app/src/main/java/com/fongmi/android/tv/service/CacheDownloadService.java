package com.fongmi.android.tv.service;

import android.app.Notification;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.IBinder;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.cache.CacheRepository;
import com.fongmi.android.tv.utils.Notify;

public class CacheDownloadService extends Service {

    private static final int NOTIFICATION_ID = Notify.ID + 3;
    private static volatile CacheDownloadService instance;

    private volatile int lastStartId;

    public static void start(Context context) {
        ContextCompat.startForegroundService(context, new Intent(context, CacheDownloadService.class));
    }

    public static void stop(Context context) {
        CacheDownloadService service = instance;
        if (service != null) service.stopIfIdle();
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        Notification notification = new NotificationCompat.Builder(this, Notify.DEFAULT)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(getString(R.string.cache_title))
                .setContentText(getString(R.string.cache_status_downloading))
                .setOngoing(true)
                .setSilent(true)
                .build();
        startForeground(NOTIFICATION_ID, notification);
        try {
            CacheRepository.get().startEngine();
        } catch (RuntimeException e) {
            CacheRepository.get().onEngineError(e.getMessage());
            stopSelf();
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        lastStartId = startId;
        // Covers a stop request racing ahead of onCreate/onStartCommand and avoids an idle sticky restart.
        CacheRepository.get().runIfIdle(() -> stopSelfResult(startId));
        return START_NOT_STICKY;
    }

    private void stopIfIdle() {
        int startId = lastStartId;
        CacheRepository.get().runIfIdle(() -> stopSelfResult(startId));
    }

    @Override
    public void onDestroy() {
        if (instance == this) instance = null;
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}

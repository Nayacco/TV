package com.fongmi.android.tv.server;

import com.fongmi.android.tv.service.PlaybackService;
import com.fongmi.android.tv.utils.Task;
import com.github.catvod.Proxy;
import com.github.catvod.utils.Util;

public class Server {

    private volatile PlaybackService service;
    private volatile Nano nano;
    private long lifecycleVersion;

    private static class Loader {
        static volatile Server INSTANCE = new Server();
    }

    public static Server get() {
        return Loader.INSTANCE;
    }

    public PlaybackService getService() {
        return service;
    }

    public synchronized void setService(PlaybackService service) {
        if (service != null) lifecycleVersion++;
        this.service = service;
    }

    public String getAddress() {
        return getAddress(false);
    }

    public String getAddress(int tab) {
        return getAddress(false) + "?tab=" + tab;
    }

    public String getAddress(String path) {
        return getAddress(true) + path;
    }

    public String getAddress(boolean local) {
        return "http://" + (local ? "127.0.0.1" : Util.getIp()) + ":" + Proxy.getPort();
    }

    public synchronized void start() {
        lifecycleVersion++;
        if (nano != null) return;
        for (int i = 9978; i < 9999; i++) {
            try {
                nano = new Nano(i);
                nano.start(500);
                Proxy.set(i);
                break;
            } catch (Throwable e) {
                nano = null;
            }
        }
    }

    public boolean isRunning() {
        Nano server = nano;
        return server != null && server.isAlive();
    }

    public synchronized void retain() {
        lifecycleVersion++;
    }

    public void stop() {
        final long version;
        final Nano target;
        synchronized (this) {
            version = ++lifecycleVersion;
            target = nano;
        }
        Task.execute(() -> {
            synchronized (this) {
                if (version != lifecycleVersion || nano != target) return;
                if (target != null) target.stop();
                service = null;
                nano = null;
            }
        });
    }
}

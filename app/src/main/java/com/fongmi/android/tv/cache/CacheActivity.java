package com.fongmi.android.tv.cache;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.databinding.ActivityCacheBinding;
import com.fongmi.android.tv.ui.activity.VideoActivity;
import com.fongmi.android.tv.ui.base.BaseActivity;
import com.fongmi.android.tv.utils.Notify;

import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

public final class CacheActivity extends BaseActivity implements CacheAdapter.Listener {

    private ActivityCacheBinding mBinding;
    private CacheAdapter mAdapter;
    private boolean mLoading;
    private boolean mRefreshPending;

    public static void start(Activity activity) {
        activity.startActivity(new Intent(activity, CacheActivity.class));
    }

    @Override
    protected ViewBinding getBinding() {
        return mBinding = ActivityCacheBinding.inflate(getLayoutInflater());
    }

    @Override
    protected void initView(Bundle savedInstanceState) {
        mBinding.toolbar.setNavigationOnClickListener(view -> onBackInvoked());
        mBinding.recycler.setHasFixedSize(true);
        mBinding.recycler.setItemAnimator(null);
        mBinding.recycler.setLayoutManager(new LinearLayoutManager(this));
        mBinding.recycler.setAdapter(mAdapter = new CacheAdapter(this));
        mBinding.progressLayout.showProgress();
        load();
    }

    private void load() {
        if (mLoading) {
            mRefreshPending = true;
            return;
        }
        mLoading = true;
        CacheRepository.get().load(items -> {
            mLoading = false;
            if (isFinishing() || isDestroyed()) {
                mRefreshPending = false;
                return;
            }
            mAdapter.setItems(items);
            mBinding.progressLayout.showContent(true, mAdapter.getItemCount());
            if (mRefreshPending) {
                mRefreshPending = false;
                load();
            }
        });
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onCacheEvent(CacheEvent event) {
        load();
    }

    @Override
    public void onPlay(CacheMetadata item) {
        ResolvedVideoSource source = VideoSourceResolver.cached(item.getCacheKey());
        if (!source.isLocal()) {
            Notify.show(R.string.cache_unavailable);
            return;
        }
        VideoActivity.startCache(this, source.localPath(), item.getTitle(), item.getPoster());
    }

    @Override
    public void onPause(CacheMetadata item) {
        CacheRepository.get().pause(item.getCacheKey());
    }

    @Override
    public void onResume(CacheMetadata item) {
        CacheRepository.get().resume(item.getCacheKey());
    }

    @Override
    public void onCancel(CacheMetadata item) {
        CacheRepository.get().cancel(item.getCacheKey());
    }

    @Override
    public void onDelete(CacheMetadata item) {
        CacheRepository.get().delete(item.getCacheKey());
    }
}

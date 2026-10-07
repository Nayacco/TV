package com.fongmi.android.tv.ui.fragment;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.databinding.FragmentSettingDecodeBinding;
import com.fongmi.android.tv.setting.DecodeSetting;
import com.fongmi.android.tv.setting.PlayerSetting;
import com.fongmi.android.tv.setting.Setting;
import com.fongmi.android.tv.ui.base.BaseFragment;

public class SettingDecodeFragment extends BaseFragment {

    private FragmentSettingDecodeBinding mBinding;

    public static SettingDecodeFragment newInstance() {
        return new SettingDecodeFragment();
    }

    @Override
    protected ViewBinding getBinding(@NonNull LayoutInflater inflater, @Nullable ViewGroup container) {
        return mBinding = FragmentSettingDecodeBinding.inflate(inflater, container, false);
    }

    @Override
    protected void initView() {
        setVisible();
        refresh();
    }

    @Override
    protected void initEvent() {
        mBinding.aac.setOnClickListener(this::setAAC);
        mBinding.tunnel.setOnClickListener(this::setTunnel);
        mBinding.audioPrefer.setOnClickListener(this::setAudioPrefer);
        mBinding.videoPrefer.setOnClickListener(this::setVideoPrefer);
        mBinding.audioPassThrough.setOnClickListener(this::setAudioPassThrough);
    }

    private void setVisible() {
        mBinding.dolbyVisionOutput.setVisibility(View.GONE);
    }

    private void refresh() {
        mBinding.aacText.setText(Setting.getSwitch(DecodeSetting.isPreferAAC()));
        mBinding.tunnelText.setText(Setting.getSwitch(DecodeSetting.isTunnel()));
        mBinding.audioPreferText.setText(Setting.getSwitch(DecodeSetting.isAudioPrefer()));
        mBinding.videoPreferText.setText(Setting.getSwitch(DecodeSetting.isVideoPrefer()));
        mBinding.audioPassThroughText.setText(Setting.getSwitch(DecodeSetting.isAudioPassThrough()));
    }

    private void setTunnel(View view) {
        DecodeSetting.putTunnel(!DecodeSetting.isTunnel());
        mBinding.tunnelText.setText(Setting.getSwitch(DecodeSetting.isTunnel()));
    }

    private void setAudioPassThrough(View view) {
        DecodeSetting.putAudioPassThrough(!DecodeSetting.isAudioPassThrough());
        mBinding.audioPassThroughText.setText(Setting.getSwitch(DecodeSetting.isAudioPassThrough()));
    }

    private void setAudioPrefer(View view) {
        DecodeSetting.putAudioPrefer(!DecodeSetting.isAudioPrefer());
        mBinding.audioPreferText.setText(Setting.getSwitch(DecodeSetting.isAudioPrefer()));
    }

    private void setVideoPrefer(View view) {
        DecodeSetting.putVideoPrefer(!DecodeSetting.isVideoPrefer());
        mBinding.videoPreferText.setText(Setting.getSwitch(DecodeSetting.isVideoPrefer()));
    }

    private void setAAC(View view) {
        DecodeSetting.putPreferAAC(!DecodeSetting.isPreferAAC());
        mBinding.aacText.setText(Setting.getSwitch(DecodeSetting.isPreferAAC()));
    }

    @Override
    public void onHiddenChanged(boolean hidden) {
        if (!hidden) initView();
    }
}

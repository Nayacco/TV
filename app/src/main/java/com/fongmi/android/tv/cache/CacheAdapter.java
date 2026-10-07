package com.fongmi.android.tv.cache;

import android.content.Context;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.RecyclerView;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.databinding.AdapterCacheBinding;
import com.fongmi.android.tv.utils.Formatters;
import com.fongmi.android.tv.utils.ImgUtil;

import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

public final class CacheAdapter extends RecyclerView.Adapter<CacheAdapter.ViewHolder> {

    public interface Listener {
        void onPlay(CacheMetadata item);

        void onPause(CacheMetadata item);

        void onResume(CacheMetadata item);

        void onCancel(CacheMetadata item);

        void onDelete(CacheMetadata item);
    }

    private final List<CacheMetadata> items = new ArrayList<>();
    private final Listener listener;

    public CacheAdapter(Listener listener) {
        this.listener = listener;
    }

    public void setItems(List<CacheMetadata> values) {
        List<CacheMetadata> next = values == null ? new ArrayList<>() : new ArrayList<>(values);
        DiffUtil.DiffResult result = DiffUtil.calculateDiff(new CacheDiff(items, next));
        items.clear();
        items.addAll(next);
        result.dispatchUpdatesTo(this);
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new ViewHolder(AdapterCacheBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        holder.bind(items.get(position));
    }

    final class ViewHolder extends RecyclerView.ViewHolder {

        private final AdapterCacheBinding binding;

        ViewHolder(AdapterCacheBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }

        void bind(CacheMetadata item) {
            Context context = binding.getRoot().getContext();
            boolean completed = item.isCompleted();
            boolean resume = CacheMetadata.PAUSED.equals(item.getStatus()) || CacheMetadata.FAILED.equals(item.getStatus());

            binding.title.setText(item.getTitle());
            binding.subtitle.setText(join(item.getSourceName(), item.getEpisodeName()));
            binding.subtitle.setVisibility(TextUtils.isEmpty(binding.subtitle.getText()) ? View.GONE : View.VISIBLE);
            binding.status.setText(status(context, item.getStatus()));
            binding.size.setText(context.getString(R.string.cache_size, bytes(item.getDownloadedBytes()), item.getTotalBytes() > 0 ? bytes(item.getTotalBytes()) : context.getString(R.string.cache_unknown)));
            binding.speed.setText(context.getString(R.string.cache_speed, bytes(item.getSpeedBytesPerSecond())));
            binding.location.setText(location(context, item));
            binding.created.setText(context.getString(R.string.cache_created, time(context, item.getCreateTime())));
            binding.completed.setVisibility(item.getCompleteTime() == null ? View.GONE : View.VISIBLE);
            if (item.getCompleteTime() != null) binding.completed.setText(context.getString(R.string.cache_completed, time(context, item.getCompleteTime())));

            bindProgress(context, item);
            bindActions(context, item, completed, resume);
            ImgUtil.load(item.getTitle(), item.getPoster(), binding.image);
            binding.getRoot().setOnClickListener(view -> {
                if (item.isCompleted()) listener.onPlay(item);
            });
        }

        private void bindProgress(Context context, CacheMetadata item) {
            long total = item.getTotalBytes();
            long downloaded = Math.max(0, item.getDownloadedBytes());
            if (item.isCompleted()) {
                binding.progress.setIndeterminate(false);
                binding.progress.setProgress(100);
                binding.progressText.setText(context.getString(R.string.cache_progress_percent, 100));
            } else if (total <= 0) {
                binding.progress.setIndeterminate(true);
                binding.progressText.setText(R.string.cache_progress_unknown);
            } else {
                int percent = (int) Math.min(100, Math.round(downloaded * 100.0d / total));
                binding.progress.setIndeterminate(false);
                binding.progress.setProgress(percent);
                binding.progressText.setText(context.getString(R.string.cache_progress_percent, percent));
            }
        }

        private void bindActions(Context context, CacheMetadata item, boolean completed, boolean resume) {
            binding.primary.setText(completed ? R.string.play : resume ? R.string.cache_resume : R.string.pause);
            binding.cancel.setVisibility(completed ? View.GONE : View.VISIBLE);
            binding.primary.setOnClickListener(view -> {
                if (completed) listener.onPlay(item);
                else if (resume) listener.onResume(item);
                else listener.onPause(item);
            });
            binding.cancel.setOnClickListener(view -> listener.onCancel(item));
            binding.delete.setOnClickListener(view -> listener.onDelete(item));
            binding.getRoot().setContentDescription(context.getString(R.string.cache_item_description, item.getTitle(), status(context, item.getStatus())));
        }
    }

    private static String join(String first, String second) {
        if (TextUtils.isEmpty(first)) return TextUtils.isEmpty(second) ? "" : second;
        if (TextUtils.isEmpty(second)) return first;
        return first + " · " + second;
    }

    private static String status(Context context, String status) {
        if (CacheMetadata.DOWNLOADING.equals(status)) return context.getString(R.string.cache_status_downloading);
        if (CacheMetadata.PAUSED.equals(status)) return context.getString(R.string.cache_status_paused);
        if (CacheMetadata.COMPLETED.equals(status)) return context.getString(R.string.cache_status_completed);
        if (CacheMetadata.FAILED.equals(status)) return context.getString(R.string.cache_status_failed);
        return context.getString(R.string.cache_status_waiting);
    }

    private static String location(Context context, CacheMetadata item) {
        if (!TextUtils.isEmpty(item.getLocalPath()) && !TextUtils.isEmpty(item.getErrorMessage())) {
            return context.getString(R.string.cache_path, item.getLocalPath()) + "\n" + context.getString(R.string.cache_error, item.getErrorMessage());
        }
        if (!TextUtils.isEmpty(item.getLocalPath())) return context.getString(R.string.cache_path, item.getLocalPath());
        if (!TextUtils.isEmpty(item.getErrorMessage())) return context.getString(R.string.cache_error, item.getErrorMessage());
        return context.getString(R.string.cache_file_pending);
    }

    private static String time(Context context, long millis) {
        if (millis <= 0) return context.getString(R.string.cache_unknown);
        return Formatters.LOCAL_DATETIME.format(Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()));
    }

    private static String bytes(long value) {
        double size = Math.max(0, value);
        String[] units = {"B", "KB", "MB", "GB", "TB"};
        int unit = 0;
        while (size >= 1024 && unit < units.length - 1) {
            size /= 1024;
            unit++;
        }
        if (unit == 0) return String.format(Locale.ROOT, "%.0f %s", size, units[unit]);
        return String.format(Locale.ROOT, "%.1f %s", size, units[unit]);
    }

    private static final class CacheDiff extends DiffUtil.Callback {

        private final List<CacheMetadata> oldItems;
        private final List<CacheMetadata> newItems;

        CacheDiff(List<CacheMetadata> oldItems, List<CacheMetadata> newItems) {
            this.oldItems = oldItems;
            this.newItems = newItems;
        }

        @Override
        public int getOldListSize() {
            return oldItems.size();
        }

        @Override
        public int getNewListSize() {
            return newItems.size();
        }

        @Override
        public boolean areItemsTheSame(int oldItemPosition, int newItemPosition) {
            return oldItems.get(oldItemPosition).getCacheKey().equals(newItems.get(newItemPosition).getCacheKey());
        }

        @Override
        public boolean areContentsTheSame(int oldItemPosition, int newItemPosition) {
            CacheMetadata oldItem = oldItems.get(oldItemPosition);
            CacheMetadata newItem = newItems.get(newItemPosition);
            return oldItem.getDownloadedBytes() == newItem.getDownloadedBytes()
                    && oldItem.getTotalBytes() == newItem.getTotalBytes()
                    && oldItem.getSpeedBytesPerSecond() == newItem.getSpeedBytesPerSecond()
                    && oldItem.getCreateTime() == newItem.getCreateTime()
                    && Objects.equals(oldItem.getTitle(), newItem.getTitle())
                    && Objects.equals(oldItem.getPoster(), newItem.getPoster())
                    && Objects.equals(oldItem.getSourceName(), newItem.getSourceName())
                    && Objects.equals(oldItem.getEpisodeName(), newItem.getEpisodeName())
                    && Objects.equals(oldItem.getStatus(), newItem.getStatus())
                    && Objects.equals(oldItem.getLocalPath(), newItem.getLocalPath())
                    && Objects.equals(oldItem.getCompleteTime(), newItem.getCompleteTime())
                    && Objects.equals(oldItem.getErrorMessage(), newItem.getErrorMessage());
        }
    }
}

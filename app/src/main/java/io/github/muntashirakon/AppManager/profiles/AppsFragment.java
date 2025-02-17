/*
 * Copyright (C) 2020 Muntashir Al-Islam
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package io.github.muntashirakon.AppManager.profiles;

import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.google.android.material.textview.MaterialTextView;

import java.util.ArrayList;
import java.util.List;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.PopupMenu;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;
import io.github.muntashirakon.AppManager.R;
import io.github.muntashirakon.AppManager.types.IconLoaderThread;
import io.github.muntashirakon.AppManager.types.RecyclerViewWithEmptyView;
import io.github.muntashirakon.AppManager.utils.UIUtils;
import me.zhanghai.android.fastscroll.FastScrollerBuilder;

public class AppsFragment extends Fragment implements SwipeRefreshLayout.OnRefreshListener {
    AppsProfileActivity activity;
    SwipeRefreshLayout swipeRefresh;
    LinearProgressIndicator progressIndicator;
    ProfileViewModel model;
    MaterialTextView alertText;

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        activity = (AppsProfileActivity) requireActivity();
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.pager_app_details, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        // Swipe refresh
        swipeRefresh = view.findViewById(R.id.swipe_refresh);
        swipeRefresh.setColorSchemeColors(UIUtils.getAccentColor(activity));
        swipeRefresh.setProgressBackgroundColorSchemeColor(UIUtils.getPrimaryColor(activity));
        swipeRefresh.setOnRefreshListener(this);
        RecyclerViewWithEmptyView recyclerView = view.findViewById(R.id.recycler_view);
        recyclerView.setHasFixedSize(true);
        recyclerView.setLayoutManager(new LinearLayoutManager(activity, LinearLayoutManager.VERTICAL, false));
        new FastScrollerBuilder(recyclerView).useMd2Style().build();
        final TextView emptyView = view.findViewById(android.R.id.empty);
        emptyView.setText(R.string.no_apps);
        recyclerView.setEmptyView(emptyView);
        progressIndicator = view.findViewById(R.id.progress_linear);
        progressIndicator.setVisibilityAfterHide(View.GONE);
        progressIndicator.show();
        alertText = view.findViewById(R.id.alert_text);
        alertText.setVisibility(View.GONE);
        alertText.setText(R.string.changes_not_saved);
        swipeRefresh.setOnChildScrollUpCallback((parent, child) -> recyclerView.canScrollVertically(-1));
        model = activity.model;
        AppsRecyclerAdapter adapter = new AppsRecyclerAdapter();
        recyclerView.setAdapter(adapter);
        model.getPackages().observe(getViewLifecycleOwner(), packages -> {
            progressIndicator.hide();
            adapter.setList(packages);
        });
    }

    @Override
    public void onResume() {
        super.onResume();
        if (activity.getSupportActionBar() != null) {
            activity.getSupportActionBar().setSubtitle(R.string.apps);
        }
        activity.fab.show();
    }

    @Override
    public void onRefresh() {
        swipeRefresh.setRefreshing(false);
        new Thread(() -> model.loadPackages()).start();
    }

    public class AppsRecyclerAdapter extends RecyclerView.Adapter<AppsRecyclerAdapter.ViewHolder> {
        PackageManager pm;
        final ArrayList<String> packages = new ArrayList<>();

        private AppsRecyclerAdapter() {
            pm = activity.getPackageManager();
        }

        void setList(List<String> list) {
            packages.clear();
            packages.addAll(list);
            notifyDataSetChanged();
        }

        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_icon_title_subtitle, parent, false);
            return new ViewHolder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            if (holder.iconLoader != null) holder.iconLoader.interrupt();
            String packageName = packages.get(position);
            ApplicationInfo info = null;
            try {
                info = pm.getApplicationInfo(packageName, 0);
            } catch (PackageManager.NameNotFoundException ignore) {
            }
            holder.iconLoader = new IconLoaderThread(holder.icon, info);
            holder.iconLoader.start();
            String label;
            if (info != null) {
                label = info.loadLabel(pm).toString();
            } else label = packageName;
            holder.title.setText(label);
            if (packageName.equals(label)) {
                holder.subtitle.setVisibility(View.GONE);
            } else {
                holder.subtitle.setVisibility(View.VISIBLE);
                holder.subtitle.setText(packageName);
            }
            holder.itemView.setOnLongClickListener(v -> {
                PopupMenu popupMenu = new PopupMenu(activity, holder.itemView);
                popupMenu.getMenu().add(R.string.delete).setOnMenuItemClickListener(item -> {
                    model.deletePackage(packageName);
                    return true;
                });
                popupMenu.show();
                return true;
            });
        }

        @Override
        public int getItemCount() {
            return packages.size();
        }

        class ViewHolder extends RecyclerView.ViewHolder {
            ImageView icon;
            TextView title;
            TextView subtitle;
            IconLoaderThread iconLoader;

            public ViewHolder(@NonNull View itemView) {
                super(itemView);
                icon = itemView.findViewById(R.id.item_icon);
                title = itemView.findViewById(R.id.item_title);
                subtitle = itemView.findViewById(R.id.item_subtitle);
                // Hide action button
                itemView.findViewById(R.id.item_open).setVisibility(View.GONE);
            }
        }
    }
}

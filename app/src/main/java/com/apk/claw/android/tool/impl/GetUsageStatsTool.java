package com.apk.claw.android.tool.impl;

import android.app.AppOpsManager;
import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Process;

import com.apk.claw.android.ClawApplication;
import com.apk.claw.android.R;
import com.apk.claw.android.tool.BaseTool;
import com.apk.claw.android.tool.ToolParameter;
import com.apk.claw.android.tool.ToolResult;
import com.apk.claw.android.utils.XLog;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 应用使用统计工具 —— 通过 UsageStatsManager 读取 App 使用数据。
 *
 * 需要特殊权限：PACKAGE_USAGE_STATS（用户需在设置 → 应用 → 特殊应用权限 → 使用情况访问 中授权）
 * 返回最近使用的应用列表，包含前台使用时长和最后使用时间。
 */
public class GetUsageStatsTool extends BaseTool {

    private static final String TAG = "GetUsageStatsTool";
    private static final int DEFAULT_LIMIT = 15;
    private static final int MAX_LIMIT = 50;

    @Override
    public String getName() {
        return "get_usage_stats";
    }

    @Override
    public String getDisplayName() {
        return ClawApplication.Companion.getInstance().getString(R.string.tool_name_get_usage_stats);
    }

    @Override
    public String getDescriptionEN() {
        return "Get app usage statistics: which apps were used, how long they were in foreground, "
                + "and when they were last used. Requires 'Usage Access' permission "
                + "(Settings → Apps → Special access → Usage access). "
                + "Useful for understanding user habits and app usage patterns.";
    }

    @Override
    public String getDescriptionCN() {
        return "获取应用使用统计：哪些应用被使用过、前台使用时长、最后使用时间。"
                + "需要「使用情况访问」权限（设置 → 应用 → 特殊应用权限 → 使用情况访问）。"
                + "用于了解用户使用习惯和应用使用模式。";
    }

    @Override
    public List<ToolParameter> getParameters() {
        List<ToolParameter> params = new ArrayList<>();
        params.add(new ToolParameter("limit", "integer",
                "Maximum number of apps to return, sorted by usage time descending (default 15, max 50).",
                false));
        params.add(new ToolParameter("days", "integer",
                "Number of days of history to include (default 1, max 7).",
                false));
        return params;
    }

    @Override
    public ToolResult execute(Map<String, Object> params) {
        Context context = ClawApplication.Companion.getInstance();

        // 权限检查（PACKAGE_USAGE_STATS 通过 AppOpsManager 检查）
        if (!hasUsageStatsPermission(context)) {
            return ToolResult.error("'Usage Access' permission not granted. "
                    + "Please go to Settings → Apps → Special app access → Usage access, "
                    + "find 'Echo' and enable it.");
        }

        int limit = optionalInt(params, "limit", DEFAULT_LIMIT);
        if (limit < 1) limit = 1;
        if (limit > MAX_LIMIT) limit = MAX_LIMIT;

        int days = optionalInt(params, "days", 1);
        if (days < 1) days = 1;
        if (days > 7) days = 7;

        long endTime = System.currentTimeMillis();
        long startTime = endTime - (days * 24L * 60 * 60 * 1000);

        UsageStatsManager usageStatsManager =
                (UsageStatsManager) context.getSystemService(Context.USAGE_STATS_SERVICE);
        if (usageStatsManager == null) {
            return ToolResult.error("UsageStatsManager not available on this device.");
        }

        List<UsageStats> statsList = usageStatsManager.queryUsageStats(
                UsageStatsManager.INTERVAL_DAILY, startTime, endTime);

        if (statsList == null || statsList.isEmpty()) {
            return ToolResult.success("No usage stats available for the past " + days + " day(s). "
                    + "This may happen if Usage Access permission is not fully working on this device.");
        }

        // 按前台时长降序排序
        Collections.sort(statsList, new Comparator<UsageStats>() {
            @Override
            public int compare(UsageStats a, UsageStats b) {
                return Long.compare(b.getTotalTimeInForeground(), a.getTotalTimeInForeground());
            }
        });

        PackageManager pm = context.getPackageManager();
        StringBuilder result = new StringBuilder();
        int count = 0;

        for (UsageStats stats : statsList) {
            if (count >= limit) break;
            if (stats.getTotalTimeInForeground() <= 0) continue; // 跳过没用过的

            String packageName = stats.getPackageName();
            String appName = getAppName(pm, packageName);
            long foregroundMs = stats.getTotalTimeInForeground();
            long lastUsedMs = stats.getLastTimeUsed();

            String foregroundStr = formatDuration(foregroundMs);
            String lastUsedStr = lastUsedMs > 0
                    ? new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US)
                            .format(new java.util.Date(lastUsedMs))
                    : "unknown";

            result.append("[").append(count + 1).append("] ")
                    .append(appName).append(" (").append(packageName).append(")")
                    .append("\n  foreground=").append(foregroundStr)
                    .append(", last_used=").append(lastUsedStr)
                    .append("\n\n");
            count++;
        }

        if (count == 0) {
            return ToolResult.success("No meaningful usage stats found for the past " + days + " day(s).");
        }

        return ToolResult.success("Top " + count + " apps by usage (past " + days + " day(s)):\n\n"
                + result.toString().trim());
    }

    private boolean hasUsageStatsPermission(Context context) {
        AppOpsManager appOps = (AppOpsManager) context.getSystemService(Context.APP_OPS_SERVICE);
        if (appOps == null) return false;
        int mode = appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.getPackageName());
        return mode == AppOpsManager.MODE_ALLOWED;
    }

    private String getAppName(PackageManager pm, String packageName) {
        try {
            ApplicationInfo info = pm.getApplicationInfo(packageName, 0);
            CharSequence label = pm.getApplicationLabel(info);
            return label != null ? label.toString() : packageName;
        } catch (PackageManager.NameNotFoundException e) {
            return packageName;
        }
    }

    private String formatDuration(long ms) {
        if (ms <= 0) return "0s";
        long hours = TimeUnit.MILLISECONDS.toHours(ms);
        long minutes = TimeUnit.MILLISECONDS.toMinutes(ms) % 60;
        if (hours > 0) {
            return hours + "h " + minutes + "m";
        }
        if (minutes > 0) {
            return minutes + "m";
        }
        return TimeUnit.MILLISECONDS.toSeconds(ms) + "s";
    }
}

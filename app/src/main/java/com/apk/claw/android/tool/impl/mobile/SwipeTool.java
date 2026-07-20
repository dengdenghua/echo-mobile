package com.apk.claw.android.tool.impl.mobile;

import com.apk.claw.android.ClawApplication;
import com.apk.claw.android.R;
import com.apk.claw.android.octopus_mobile.uitree.StableIdResolver;
import com.apk.claw.android.octopus_mobile.uitree.UiActionRouter;
import com.apk.claw.android.tool.BaseTool;
import com.apk.claw.android.tool.ToolParameter;
import com.apk.claw.android.tool.ToolResult;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

public class SwipeTool extends BaseTool {

    @Override
    public String getName() {
        return "swipe";
    }

    @Override
    public String getDisplayName() {
        return ClawApplication.Companion.getInstance().getString(R.string.tool_name_swipe);
    }

    @Override
    public String getDescriptionEN() {
        return "Swipe from one point to another on the screen. Useful for scrolling, pulling down notifications, etc. "
            + "Optionally pass start_stableId / end_stableId (from get_screen_info json) to swipe between node centers.";
    }

    @Override
    public String getDescriptionCN() {
        return "在屏幕上从一个点滑动到另一个点。适用于滚动、下拉通知等操作。"
            + "可选 start_stableId / end_stableId（来自 get_screen_info 的 json 输出）按节点引用滑动。"
            + "stableId 与坐标可混用：例如 start_stableId + end_x/end_y。";
    }

    @Override
    public List<ToolParameter> getParameters() {
        return Arrays.asList(
                new ToolParameter("start_x", "integer", "Start X coordinate (required if start_stableId not given)", false),
                new ToolParameter("start_y", "integer", "Start Y coordinate (required if start_stableId not given)", false),
                new ToolParameter("end_x", "integer", "End X coordinate (required if end_stableId not given)", false),
                new ToolParameter("end_y", "integer", "End Y coordinate (required if end_stableId not given)", false),
                new ToolParameter("start_stableId", "string",
                    "Start node stableId from get_screen_info(json). If present, overrides start_x/start_y.",
                    false),
                new ToolParameter("end_stableId", "string",
                    "End node stableId from get_screen_info(json). If present, overrides end_x/end_y.",
                    false),
                new ToolParameter("duration_ms", "integer", "Swipe duration in milliseconds (default 500)", false)
        );
    }

    @Override
    public ToolResult execute(Map<String, Object> params) {
        long duration = optionalLong(params, "duration_ms", 500);

        // 起点：优先 start_stableId
        String startStableId = params.containsKey("start_stableId")
            ? String.valueOf(params.get("start_stableId")) : "";
        int startX;
        int startY;
        if (startStableId != null && !startStableId.isEmpty() && !"null".equals(startStableId)) {
            StableIdResolver.Result r = StableIdResolver.INSTANCE.resolveCenter(startStableId);
            if (r == null) {
                return ToolResult.error("start_stableId not found: " + startStableId
                    + " (界面可能已变化，请重新调 get_screen_info)");
            }
            startX = r.getX();
            startY = r.getY();
        } else {
            try {
                startX = requireInt(params, "start_x");
                startY = requireInt(params, "start_y");
            } catch (IllegalArgumentException e) {
                return ToolResult.error(e.getMessage()
                    + " (or pass start_stableId to swipe by node reference)");
            }
        }

        // 终点：优先 end_stableId
        String endStableId = params.containsKey("end_stableId")
            ? String.valueOf(params.get("end_stableId")) : "";
        int endX;
        int endY;
        if (endStableId != null && !endStableId.isEmpty() && !"null".equals(endStableId)) {
            StableIdResolver.Result r = StableIdResolver.INSTANCE.resolveCenter(endStableId);
            if (r == null) {
                return ToolResult.error("end_stableId not found: " + endStableId
                    + " (界面可能已变化，请重新调 get_screen_info)");
            }
            endX = r.getX();
            endY = r.getY();
        } else {
            try {
                endX = requireInt(params, "end_x");
                endY = requireInt(params, "end_y");
            } catch (IllegalArgumentException e) {
                return ToolResult.error(e.getMessage()
                    + " (or pass end_stableId to swipe by node reference)");
            }
        }

        return swipe(startX, startY, endX, endY, duration);
    }

    private ToolResult swipe(int startX, int startY, int endX, int endY, long duration) {
        String boundsError = validateCoordinates(startX, startY);
        if (boundsError != null) return ToolResult.error(boundsError);
        boundsError = validateCoordinates(endX, endY);
        if (boundsError != null) return ToolResult.error(boundsError);
        // 统一走 UiActionRouter：远程→Shizuku→A11y→Root 三级降级
        boolean success = UiActionRouter.INSTANCE.swipe(startX, startY, endX, endY, duration);
        return success ? ToolResult.success("Swiped from (" + startX + ", " + startY + ") to (" + endX + ", " + endY + ")")
                : ToolResult.error("Failed to swipe from (" + startX + ", " + startY + ") to (" + endX + ", " + endY + ") "
                    + "(所有通道都失败：Shizuku/A11y/Root 均不可用或失败)");
    }
}

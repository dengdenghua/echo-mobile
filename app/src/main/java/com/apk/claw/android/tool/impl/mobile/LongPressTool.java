package com.apk.claw.android.tool.impl.mobile;

import com.apk.claw.android.ClawApplication;
import com.apk.claw.android.R;
import com.apk.claw.android.octopus_mobile.ControlTarget;
import com.apk.claw.android.octopus_mobile.DeviceInfo;
import com.apk.claw.android.octopus_mobile.RemoteActions;
import com.apk.claw.android.octopus_mobile.uitree.StableIdResolver;
import com.apk.claw.android.service.ClawAccessibilityService;
import com.apk.claw.android.tool.BaseTool;
import com.apk.claw.android.tool.ToolParameter;
import com.apk.claw.android.tool.ToolResult;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

public class LongPressTool extends BaseTool {

    @Override
    public String getName() {
        return "long_press";
    }

    @Override
    public String getDisplayName() {
        return ClawApplication.Companion.getInstance().getString(R.string.tool_name_long_press);
    }

    @Override
    public String getDescriptionEN() {
        return "Perform a long press at the specified screen coordinates (x, y) for a given duration. "
            + "Alternatively pass stableId (from get_screen_info json) to long-press by node reference.";
    }

    @Override
    public String getDescriptionCN() {
        return "在指定的屏幕坐标 (x, y) 处执行长按操作，持续指定时长。"
            + "也可传 stableId（来自 get_screen_info 的 json 输出）按节点引用长按。";
    }

    @Override
    public List<ToolParameter> getParameters() {
        return Arrays.asList(
                new ToolParameter("x", "integer", "X coordinate on screen (required if stableId not given)", false),
                new ToolParameter("y", "integer", "Y coordinate on screen (required if stableId not given)", false),
                new ToolParameter("stableId", "string",
                    "Node stableId from get_screen_info(json). If present, takes precedence over x/y.",
                    false),
                new ToolParameter("duration_ms", "integer", "Duration of long press in milliseconds (default 1000)", false)
        );
    }

    @Override
    public ToolResult execute(Map<String, Object> params) {
        long duration = optionalLong(params, "duration_ms", 1000);

        // 优先 stableId
        String stableId = params.containsKey("stableId")
            ? String.valueOf(params.get("stableId")) : "";
        if (stableId != null && !stableId.isEmpty() && !"null".equals(stableId)) {
            StableIdResolver.Result r = StableIdResolver.INSTANCE.resolveCenter(stableId);
            if (r == null) {
                return ToolResult.error("stableId not found in current UI tree: " + stableId
                    + " (界面可能已变化，请重新调 get_screen_info)");
            }
            return longPressAt(r.getX(), r.getY(), duration);
        }

        final int x;
        final int y;
        try {
            x = requireInt(params, "x");
            y = requireInt(params, "y");
        } catch (IllegalArgumentException e) {
            return ToolResult.error(e.getMessage()
                + " (or pass stableId to long-press by node reference)");
        }
        return longPressAt(x, y, duration);
    }

    private ToolResult longPressAt(int x, int y, long duration) {
        DeviceInfo remote = ControlTarget.remoteTarget();
        if (remote != null) {
            boolean ok = RemoteActions.longPress(remote, x, y, duration);
            return ok ? ToolResult.success("Long pressed at (" + x + ", " + y + ") on " + remote.getDeviceName())
                    : ToolResult.error("Remote long press failed on " + remote.getDeviceName());
        }
        ClawAccessibilityService service = ClawAccessibilityService.getInstance();
        if (service == null) {
            return ToolResult.error("Accessibility service is not running");
        }
        String boundsError = validateCoordinates(x, y);
        if (boundsError != null) return ToolResult.error(boundsError);
        boolean success = service.performLongPress(x, y, duration);
        return success ? ToolResult.success("Long pressed at (" + x + ", " + y + ") for " + duration + "ms")
                : ToolResult.error("Failed to long press at (" + x + ", " + y + ")");
    }
}

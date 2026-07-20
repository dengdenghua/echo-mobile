package com.apk.claw.android.tool.impl.mobile;

import com.apk.claw.android.ClawApplication;
import com.apk.claw.android.R;
import com.apk.claw.android.octopus_mobile.ControlTarget;
import com.apk.claw.android.octopus_mobile.DeviceInfo;
import com.apk.claw.android.octopus_mobile.RemoteActions;
import com.apk.claw.android.octopus_mobile.uitree.UiNode;
import com.apk.claw.android.octopus_mobile.uitree.UiTree;
import com.apk.claw.android.octopus_mobile.uitree.UiTreeCoordinator;
import com.apk.claw.android.service.ClawAccessibilityService;
import com.apk.claw.android.tool.BaseTool;
import com.apk.claw.android.tool.ToolParameter;
import com.apk.claw.android.tool.ToolResult;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

public class TapTool extends BaseTool {

    @Override
    public String getName() {
        return "tap";
    }

    @Override
    public String getDisplayName() {
        return ClawApplication.Companion.getInstance().getString(R.string.tool_name_tap);
    }

    @Override
    public String getDescriptionEN() {
        return "Tap at the specified screen coordinates (x, y). "
            + "Alternatively pass stableId (from get_screen_info json) to tap by node reference — "
            + "more robust to layout changes than raw coordinates.";
    }

    @Override
    public String getDescriptionCN() {
        return "在指定的屏幕坐标 (x, y) 处点击。"
            + "也可传 stableId（来自 get_screen_info 的 json 输出）按节点引用点击，"
            + "比坐标更抗界面变化。stableId 与 x/y 二选一，传了 stableId 优先。";
    }

    @Override
    public List<ToolParameter> getParameters() {
        return Arrays.asList(
                new ToolParameter("x", "integer", "X coordinate on screen (required if stableId not given)", false),
                new ToolParameter("y", "integer", "Y coordinate on screen (required if stableId not given)", false),
                new ToolParameter("stableId", "string",
                    "Node stableId from get_screen_info(json). If present, takes precedence over x/y — "
                        + "tool fetches the latest UI tree and taps the node's center.",
                    false)
        );
    }

    @Override
    public ToolResult execute(Map<String, Object> params) {
        // 优先走 stableId 路径：从最新 UiTree 找节点，用其 bounds center 点击。
        // 比 x/y 抗界面变化（界面重排后坐标变，但 stableId 由 viewId+text+bounds hash 复合而成，
        // 只要元素本身没大改，stableId 仍能命中）。
        String stableId = params.containsKey("stableId")
            ? String.valueOf(params.get("stableId")) : "";
        if (stableId != null && !stableId.isEmpty() && !"null".equals(stableId)) {
            return tapByStableId(stableId);
        }

        final int x;
        final int y;
        try {
            x = requireInt(params, "x");
            y = requireInt(params, "y");
        } catch (IllegalArgumentException e) {
            return ToolResult.error(e.getMessage()
                + " (or pass stableId to tap by node reference)");
        }
        return tapAt(x, y);
    }

    private ToolResult tapByStableId(String stableId) {
        // 即时拉取最新 UiTree（可能来源 a11y 或 shizuku uiautomator）
        UiTree tree;
        try {
            tree = UiTreeCoordinator.INSTANCE.getTree(false);
        } catch (Throwable t) {
            return ToolResult.error("Failed to fetch UI tree for stableId lookup: " + t.getMessage());
        }
        if (tree == null || tree.getRoot() == null) {
            return ToolResult.error("UI tree unavailable, cannot resolve stableId: " + stableId);
        }
        // 深度优先扁平化找节点（stableId 唯一性由 viewId+textHash+boundsHash 保证）
        UiNode found = null;
        for (UiNode n : tree.flatten()) {
            if (stableId.equals(n.getStableId())) { found = n; break; }
        }
        if (found == null) {
            return ToolResult.error("stableId not found in current UI tree: " + stableId
                + " (界面可能已变化，请重新调 get_screen_info)");
        }
        // 用节点 bounds 中心点点击
        android.graphics.Rect b = found.getBounds();
        int cx = b.centerX();
        int cy = b.centerY();
        return tapAt(cx, cy);
    }

    private ToolResult tapAt(int x, int y) {
        DeviceInfo remote = ControlTarget.remoteTarget();
        if (remote != null) {
            boolean ok = RemoteActions.tap(remote, x, y);
            return ok ? ToolResult.success("Tapped at (" + x + ", " + y + ") on " + remote.getDeviceName())
                    : ToolResult.error("Remote tap failed on " + remote.getDeviceName());
        }
        ClawAccessibilityService service = ClawAccessibilityService.getInstance();
        if (service == null) {
            return ToolResult.error("Accessibility service is not running");
        }
        String boundsError = validateCoordinates(x, y);
        if (boundsError != null) return ToolResult.error(boundsError);
        boolean success = service.performTap(x, y);
        return success ? ToolResult.success("Tapped at (" + x + ", " + y + ")")
                : ToolResult.error("Failed to tap at (" + x + ", " + y + ")");
    }
}

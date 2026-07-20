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
            StableIdResolver.Result r = StableIdResolver.INSTANCE.resolveCenter(stableId);
            if (r == null) {
                return ToolResult.error("stableId not found in current UI tree: " + stableId
                    + " (界面可能已变化，请重新调 get_screen_info)");
            }
            return tapAt(r.getX(), r.getY());
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

    private ToolResult tapAt(int x, int y) {
        String boundsError = validateCoordinates(x, y);
        if (boundsError != null) return ToolResult.error(boundsError);
        // 统一走 UiActionRouter：远程→Shizuku→A11y→Root 三级降级，零关心通道选择
        boolean success = UiActionRouter.INSTANCE.tap(x, y);
        return success ? ToolResult.success("Tapped at (" + x + ", " + y + ")")
                : ToolResult.error("Failed to tap at (" + x + ", " + y + ") "
                    + "(所有通道都失败：Shizuku/A11y/Root 均不可用或失败)");
    }
}

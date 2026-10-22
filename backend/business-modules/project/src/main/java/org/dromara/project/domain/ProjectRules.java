package org.dromara.project.domain;

import java.util.Objects;
import java.util.Set;
import org.dromara.common.core.exception.ServiceException;

/** Pure business transition policy. Persistence and workflow callbacks use the same rules. */
public final class ProjectRules {
    private ProjectRules() {}

    public static final Set<String> STAGES =
            Set.of(
                    "DRAFT",
                    "REGISTRATION_REVIEW",
                    "REGISTERED",
                    "BID_REVIEW",
                    "BID_PREPARATION",
                    "BID_WON",
                    "BID_LOST",
                    "INITIATION_REVIEW",
                    "ESTABLISHED",
                    "TERMINATED");

    public static String submit(String stage, String mode, String type) {
        boolean eligible =
                switch (type) {
                    case "REGISTRATION" -> "DRAFT".equals(stage);
                    case "BID" -> "TENDER".equals(mode) && "REGISTERED".equals(stage);
                    case "INITIATION" ->
                            "DIRECT".equals(mode) && "REGISTERED".equals(stage)
                                    || "TENDER".equals(mode) && "BID_WON".equals(stage);
                    default -> false;
                };
        require(eligible, "当前阶段不能提交该申请，请刷新项目后重试");
        return type + "_REVIEW";
    }

    public static String decide(String stage, String mode, String type, boolean approved) {
        require((type + "_REVIEW").equals(stage), "申请已处理或项目阶段已变化");
        return switch (type) {
            case "REGISTRATION" -> approved ? "REGISTERED" : "DRAFT";
            case "BID" -> approved ? "BID_PREPARATION" : "REGISTERED";
            case "INITIATION" ->
                    approved ? "ESTABLISHED" : "TENDER".equals(mode) ? "BID_WON" : "REGISTERED";
            default -> throw new ServiceException("未知申请类型");
        };
    }

    public static void requireTerminable(String stage) {
        require(
                !stage.endsWith("_REVIEW") && !Set.of("ESTABLISHED", "TERMINATED").contains(stage),
                "审批中、已立项或已终止项目不能终止");
    }

    public static void requireOwner(String ownerId, String actorId) {
        require(Objects.equals(ownerId, actorId), "只能办理本人项目");
    }

    public static void requireReadable(String ownerId, String actorId, boolean reviewer) {
        require(reviewer || Objects.equals(ownerId, actorId), "无权查看该项目");
    }

    public static void require(boolean condition, String message) {
        if (!condition) throw new ServiceException(message);
    }
}

package org.dromara.project.infrastructure;

import static org.dromara.project.domain.ProjectRules.require;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.dromara.project.domain.Project;
import org.dromara.project.domain.ProjectApplication;
import org.dromara.project.domain.ProjectApplication.CurrentTask;
import org.dromara.project.mapper.ProjectWorkflowMapper;
import org.dromara.workflow.api.WorkflowService;
import org.dromara.workflow.api.domain.CompleteTaskDTO;
import org.dromara.workflow.api.domain.StartProcessDTO;
import org.dromara.workflow.domain.bo.FlowTerminationBo;
import org.dromara.workflow.service.IFlwTaskService;
import org.springframework.stereotype.Component;

/** The only project-to-engine adapter; no caller-controlled flow, handler, task ID or variables. */
@Component
public class ProjectWorkflow {
    private final WorkflowService workflow;
    private final IFlwTaskService taskService;
    private final ProjectWorkflowMapper mapper;

    public ProjectWorkflow(
            WorkflowService workflow, IFlwTaskService taskService, ProjectWorkflowMapper mapper) {
        this.workflow = workflow;
        this.taskService = taskService;
        this.mapper = mapper;
    }

    public static String flowCode(String type) {
        return switch (type) {
            case "REGISTRATION" -> "pm_registration";
            case "BID" -> "pm_bid";
            case "INITIATION" -> "pm_initiation";
            default -> throw new IllegalArgumentException("Unknown application type");
        };
    }

    public record Binding(String instanceId, String taskId) {}

    public Binding start(ProjectApplication application) {
        StartProcessDTO dto = new StartProcessDTO();
        dto.setBusinessId("pm:" + application.getId());
        dto.setFlowCode(flowCode(application.getType()));
        dto.setVariables(conditionVariables(application));
        require(workflow.startCompleteTask(dto), "申请流程启动失败");
        List<Binding> bindings = mapper.bindings("pm:" + application.getId(), dto.getFlowCode());
        require(!bindings.isEmpty(), "审批流程必须生成审核节点，请联系管理员检查流程配置");
        String instanceId = bindings.getFirst().instanceId();
        require(
                bindings.stream().allMatch(binding -> instanceId.equals(binding.instanceId())),
                "申请存在多个流程实例，请联系管理员检查");
        return bindings.getFirst();
    }

    public String decide(ProjectApplication app, String userId, boolean approve, String comment) {
        // The persisted task ID is only a compatibility cache. A process may have advanced or
        // created parallel tasks; choose one currently assigned task, never a client-supplied ID.
        CurrentTask current =
                currentTasks(app, userId).stream()
                        .filter(CurrentTask::canHandle)
                        .findFirst()
                        .orElseThrow(
                                () ->
                                        new org.dromara.common.core.exception.ServiceException(
                                                "您不是当前审批节点的实际办理人"));
        String taskId = current.taskId();
        // Re-read and lock the live task, check persisted assignment. Admin is deliberately not
        // exempt.
        List<String> tasks =
                mapper.lockTask(
                        taskId, app.getInstanceId(), "pm:" + app.getId(), flowCode(app.getType()));
        require(tasks.size() == 1, "当前审批任务已结束或不属于此申请");
        require(mapper.assigneeCount(taskId, userId) > 0, "您不是当前审批节点的实际办理人");
        if (approve) {
            CompleteTaskDTO dto = new CompleteTaskDTO();
            dto.setTaskId(Long.valueOf(taskId));
            dto.setMessage(comment == null || comment.isBlank() ? "同意" : comment);
            require(workflow.completeTask(dto), "审批办理失败");
        } else {
            // Terminate the old application normally. A resubmission creates a new engine instance.
            require(
                    taskService.terminationTask(
                            new FlowTerminationBo(Long.valueOf(taskId), "退回修改：" + comment)),
                    "退回办理失败");
        }
        String status = workflow.getBusinessStatus("pm:" + app.getId());
        require(
                approve
                        ? Set.of("waiting", "finish").contains(status == null ? "" : status)
                        : "termination".equals(status),
                "流程未到达预期状态，请检查审批流程配置");
        return status;
    }

    public List<CurrentTask> currentTasks(ProjectApplication app, String userId) {
        if (app.getInstanceId() == null) return List.of();
        return mapper.currentTasks(
                app.getInstanceId(), "pm:" + app.getId(), flowCode(app.getType()), userId);
    }

    /** Stable, documented facts from the persisted submission snapshot, never arbitrary input. */
    private Map<String, Object> conditionVariables(ProjectApplication app) {
        Map<String, Object> variables = new HashMap<>();
        variables.put("applicationType", app.getType());
        Project snapshot = app.getSnapshot();
        if (snapshot == null) return variables;
        variables.put("acquisitionMode", snapshot.getAcquisitionMode());
        variables.put(
                "projectType", snapshot.getProjectType() == null ? "" : snapshot.getProjectType());
        variables.put(
                "estimatedAmount",
                snapshot.getEstimatedAmount() == null
                        ? java.math.BigDecimal.ZERO
                        : snapshot.getEstimatedAmount());
        variables.put("bidType", snapshot.getBidType() == null ? "" : snapshot.getBidType());
        variables.put(
                "bidAmount",
                snapshot.getBidAmount() == null
                        ? java.math.BigDecimal.ZERO
                        : snapshot.getBidAmount());
        variables.put(
                "projectAmount",
                snapshot.getProjectAmount() == null
                        ? java.math.BigDecimal.ZERO
                        : snapshot.getProjectAmount());
        return variables;
    }

    public boolean authenticEvent(ProjectApplication app, Long instanceId, String status) {
        return app.getInstanceId() != null
                && app.getInstanceId().equals(String.valueOf(instanceId))
                && status.equals(workflow.getBusinessStatus("pm:" + app.getId()));
    }
}

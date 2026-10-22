package org.dromara.workflow.controller;

import lombok.RequiredArgsConstructor;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.core.utils.StringUtils;
import org.dromara.warm.flow.orm.entity.FlowInstance;
import org.dromara.workflow.service.IFlwInstanceService;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Map;
import java.util.Set;

/**
 * Keeps application-owned approvals off the generic workflow HTTP surface.
 * Domain services still call the workflow services directly after checking business permissions.
 */
@Component
@RequiredArgsConstructor
public class WorkflowHttpGuard {

    private static final String PROJECT_BUSINESS_PREFIX = "pm:";
    private static final Set<String> PROJECT_FLOWS = Set.of("pm_registration", "pm_bid", "pm_initiation");
    private static final Set<String> RESERVED_VARIABLES = Set.of(
        "ignore", "ignoreDepute", "ignoreCooperate", "handler",
        "initiator", "initiatorDeptId", "businessId", "autoPass", "submit"
    );

    private final IFlwInstanceService instanceService;

    public void checkStart(String flowCode, String businessId, String handler, Map<String, Object> variables) {
        checkBusinessId(businessId);
        if (PROJECT_FLOWS.contains(flowCode)) {
            denyProjectFlow();
        }
        checkHandler(handler);
        checkVariables(variables);
    }

    public void checkTask(Long taskId) {
        if (taskId != null) {
            checkInstance(instanceService.selectByTaskId(taskId));
        }
    }

    public void checkTasks(Collection<Long> taskIds) {
        if (taskIds != null) {
            taskIds.forEach(this::checkTask);
        }
    }

    public void checkInstanceId(Long instanceId) {
        if (instanceId != null) {
            checkInstance(instanceService.selectInstById(instanceId));
        }
    }

    public void checkInstanceIds(Collection<Long> instanceIds) {
        if (instanceIds != null) {
            instanceIds.forEach(this::checkInstanceId);
        }
    }

    public void checkBusinessId(String businessId) {
        if (StringUtils.isNotBlank(businessId) && businessId.startsWith(PROJECT_BUSINESS_PREFIX)) {
            denyProjectFlow();
        }
    }

    public void checkHandler(String handler) {
        if (StringUtils.isNotBlank(handler)) {
            throw new ServiceException("办理人由登录用户决定");
        }
    }

    public void checkVariables(Map<String, Object> variables) {
        if (variables == null) {
            return;
        }
        for (String key : variables.keySet()) {
            if (key != null && (RESERVED_VARIABLES.contains(key)
                || key.startsWith("pass:") || key.startsWith("back:"))) {
                throw new ServiceException("流程控制变量不能由客户端提交: " + key);
            }
        }
    }

    private void checkInstance(FlowInstance instance) {
        if (instance != null) {
            checkBusinessId(instance.getBusinessId());
        }
    }

    private static void denyProjectFlow() {
        throw new ServiceException("项目审批请通过项目业务接口办理");
    }
}

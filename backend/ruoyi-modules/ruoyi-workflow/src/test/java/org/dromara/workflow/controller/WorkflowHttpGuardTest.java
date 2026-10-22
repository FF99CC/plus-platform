package org.dromara.workflow.controller;

import org.dromara.common.core.exception.ServiceException;
import org.dromara.warm.flow.orm.entity.FlowInstance;
import org.dromara.workflow.domain.bo.BackProcessBo;
import org.dromara.workflow.domain.bo.CompleteTaskBo;
import org.dromara.workflow.domain.bo.FlowCancelBo;
import org.dromara.workflow.domain.bo.FlowTerminationBo;
import org.dromara.workflow.domain.bo.StartProcessBo;
import org.dromara.workflow.domain.bo.TestLeaveBo;
import org.dromara.workflow.service.IFlwInstanceService;
import org.dromara.workflow.service.IFlwTaskService;
import org.dromara.workflow.service.ITestLeaveService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.HashMap;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

@Tag("dev")
class WorkflowHttpGuardTest {

    private IFlwInstanceService instanceService;
    private IFlwTaskService taskService;
    private WorkflowHttpGuard guard;
    private FlwTaskController taskController;
    private FlwInstanceController instanceController;
    private ITestLeaveService leaveService;
    private TestLeaveController leaveController;

    @BeforeEach
    void setUp() {
        instanceService = mock(IFlwInstanceService.class);
        taskService = mock(IFlwTaskService.class);
        guard = new WorkflowHttpGuard(instanceService);
        taskController = new FlwTaskController(taskService, guard);
        instanceController = new FlwInstanceController(null, instanceService, guard);
        leaveService = mock(ITestLeaveService.class);
        leaveController = new TestLeaveController(leaveService, guard);
    }

    @Test
    void genericStartRejectsProtectedFlowAndBusinessId() {
        StartProcessBo protectedFlow = new StartProcessBo();
        protectedFlow.setFlowCode("pm_registration");
        protectedFlow.setBusinessId("ordinary:1");
        assertThrows(ServiceException.class, () -> taskController.startWorkFlow(protectedFlow));

        StartProcessBo protectedBusiness = new StartProcessBo();
        protectedBusiness.setFlowCode("leave1");
        protectedBusiness.setBusinessId("pm:123");
        assertThrows(ServiceException.class, () -> taskController.startWorkFlow(protectedBusiness));
        verifyNoInteractions(taskService);
    }

    @Test
    void leaveSubmissionCannotStartProjectFlowOrPassBypassVariables() {
        TestLeaveBo projectFlow = new TestLeaveBo();
        projectFlow.setFlowCode("pm_bid");
        assertThrows(ServiceException.class, () -> leaveController.submitAndFlowStart(projectFlow));

        TestLeaveBo bypass = new TestLeaveBo();
        bypass.setFlowCode("local_baseline");
        bypass.setParams(new HashMap<>(Map.of("ignore", true)));
        assertThrows(ServiceException.class, () -> leaveController.submitAndFlowStart(bypass));
        verifyNoInteractions(leaveService);
        assertDoesNotThrow(() -> guard.checkStart("local_baseline", null, null, Map.of("entity", "leave")));
    }

    @Test
    void genericCompleteRejectsProjectTaskBeforeEngineCall() {
        mockProjectTask(42L);
        CompleteTaskBo request = new CompleteTaskBo();
        request.setTaskId(42L);
        assertThrows(ServiceException.class, () -> taskController.completeTask(request));
        verifyNoInteractions(taskService);
    }

    @Test
    void genericMutationsRejectProjectTaskAndInstance() {
        mockProjectTask(42L);
        BackProcessBo back = new BackProcessBo();
        back.setTaskId(42L);
        assertThrows(ServiceException.class, () -> taskController.backProcess(back));
        assertThrows(ServiceException.class, () -> taskController.terminationTask(new FlowTerminationBo(42L, "stop")));
        assertThrows(ServiceException.class, () -> taskController.updateAssignee(List.of(42L), "7"));

        assertThrows(ServiceException.class, () -> instanceController.cancelProcessApply(new FlowCancelBo("pm:123", "cancel")));
        when(instanceService.selectInstById(91L)).thenReturn(projectInstance());
        assertThrows(ServiceException.class, () -> instanceController.deleteByInstanceIds(List.of(91L)));
        verifyNoInteractions(taskService);
        verify(instanceService, never()).cancelProcessApply(any());
        verify(instanceService, never()).deleteByInstanceIds(any());
    }

    @Test
    void genericRequestsRejectReservedControlVariablesAndHandler() {
        assertThrows(ServiceException.class, () -> guard.checkStart("leave1", "leave:1", null, Map.of("ignore", true)));
        assertThrows(ServiceException.class, () -> guard.checkVariables(Map.of("pass:reviewer", "7")));
        assertThrows(ServiceException.class, () -> guard.checkVariables(Map.of("initiator", "7")));
        assertThrows(ServiceException.class, () -> guard.checkHandler("7"));

        CompleteTaskBo request = new CompleteTaskBo();
        request.setTaskId(1L);
        request.setVariables(new HashMap<>(Map.of("ignore", true)));
        assertThrows(ServiceException.class, () -> taskController.completeTask(request));
        verifyNoInteractions(taskService);
    }

    @Test
    void ordinaryFlowWithoutControlVariablesRemainsAvailable() {
        assertDoesNotThrow(() -> guard.checkStart("leave1", "leave:1", null, Map.of("entity", "sample")));
        assertDoesNotThrow(() -> guard.checkTask(1L));
        assertDoesNotThrow(() -> guard.checkInstanceId(2L));
    }

    private void mockProjectTask(Long taskId) {
        when(instanceService.selectByTaskId(taskId)).thenReturn(projectInstance());
    }

    private FlowInstance projectInstance() {
        FlowInstance instance = new FlowInstance();
        instance.setBusinessId("pm:123");
        return instance;
    }
}

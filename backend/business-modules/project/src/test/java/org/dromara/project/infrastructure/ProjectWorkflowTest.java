package org.dromara.project.infrastructure;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.math.BigDecimal;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.project.domain.Project;
import org.dromara.project.domain.ProjectApplication;
import org.dromara.project.mapper.ProjectWorkflowMapper;
import org.dromara.project.support.ProjectMybatisTestSupport;
import org.dromara.workflow.api.WorkflowService;
import org.dromara.workflow.api.domain.CompleteTaskDTO;
import org.dromara.workflow.api.domain.StartProcessDTO;
import org.dromara.workflow.domain.bo.FlowTerminationBo;
import org.dromara.workflow.service.IFlwTaskService;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

@Tag("dev")
class ProjectWorkflowTest {
    JdbcTemplate jdbc;
    WorkflowService engine;
    IFlwTaskService tasks;
    ProjectWorkflow adapter;
    ProjectApplication app;

    @BeforeEach
    void setup() throws Exception {
        var source =
                new DriverManagerDataSource(
                        "jdbc:h2:mem:workflow"
                                + System.nanoTime()
                                + ";MODE=MySQL;DB_CLOSE_DELAY=-1",
                        "sa",
                        "");
        jdbc = new JdbcTemplate(source);
        jdbc.execute("create table flow_definition(id bigint primary key,flow_code varchar(50))");
        jdbc.execute(
                "create table flow_instance(id bigint primary key,definition_id bigint,business_id"
                        + " varchar(50),del_flag varchar(1))");
        jdbc.execute(
                "create table flow_task(id bigint primary key,instance_id bigint,node_type"
                        + " int,del_flag varchar(1),node_name varchar(100))");
        jdbc.execute(
                "create table flow_user(id bigint primary key,associated bigint,processed_by"
                        + " varchar(50),type varchar(1),del_flag varchar(1))");
        jdbc.update("insert into flow_definition values(10,'pm_registration')");
        jdbc.update("insert into flow_instance values(20,10,'pm:40','0')");
        jdbc.update("insert into flow_task(id,instance_id,node_type,del_flag) values(30,20,1,'0')");
        jdbc.update("insert into flow_user values(50,30,'2','1','0')");
        engine = mock(WorkflowService.class);
        tasks = mock(IFlwTaskService.class);
        adapter =
                new ProjectWorkflow(
                        engine,
                        tasks,
                        ProjectMybatisTestSupport.session(source)
                                .getMapper(ProjectWorkflowMapper.class));
        app = new ProjectApplication();
        app.setId("40");
        app.setType("REGISTRATION");
        app.setTaskId("30");
        app.setInstanceId("20");
    }

    @Test
    void unassignedReviewerCannotApproveEvenWithValidApplicationAndTask() {
        assertThrows(ServiceException.class, () -> adapter.decide(app, "1", true, "同意"));
        verifyNoInteractions(engine, tasks);
    }

    @Test
    void deletedAssignmentDoesNotGrantApproval() {
        jdbc.update("update flow_user set del_flag='1'");
        assertThrows(ServiceException.class, () -> adapter.decide(app, "2", true, "同意"));
        verifyNoInteractions(engine, tasks);
    }

    @Test
    void assignedReviewerUsesTypedDtoWithoutIgnoreOrHandlerOverride() {
        when(engine.completeTask(any(CompleteTaskDTO.class))).thenReturn(true);
        when(engine.getBusinessStatus("pm:40")).thenReturn("finish");
        adapter.decide(app, "2", true, "审核同意");
        var capture = ArgumentCaptor.forClass(CompleteTaskDTO.class);
        verify(engine).completeTask(capture.capture());
        assertEquals(30L, capture.getValue().getTaskId());
        assertNull(capture.getValue().getHandler());
        assertTrue(capture.getValue().getVariables().isEmpty());
        assertEquals("审核同意", capture.getValue().getMessage());
    }

    @Test
    void applicationCannotBorrowAnotherInstancesTask() {
        app.setInstanceId("21");
        assertThrows(ServiceException.class, () -> adapter.decide(app, "2", true, null));
        verifyNoInteractions(engine, tasks);
    }

    @Test
    void returnNormallyTerminatesEngineHistoryAndRequiresConfirmedTerminalStatus() {
        when(tasks.terminationTask(any())).thenReturn(true);
        when(engine.getBusinessStatus("pm:40")).thenReturn("termination");
        adapter.decide(app, "2", false, "请修订");
        var capture = ArgumentCaptor.forClass(FlowTerminationBo.class);
        verify(tasks).terminationTask(capture.capture());
        assertEquals("退回修改：请修订", capture.getValue().comment());
        verify(engine, never()).completeTask(any(CompleteTaskDTO.class));
        when(engine.getBusinessStatus("pm:40")).thenReturn("waiting");
        assertThrows(ServiceException.class, () -> adapter.decide(app, "2", false, "请修订"));
    }

    @Test
    void approvingIntermediateNodeDoesNotRequireProcessToFinish() {
        when(engine.completeTask(any(CompleteTaskDTO.class))).thenReturn(true);
        when(engine.getBusinessStatus("pm:40")).thenReturn("waiting");
        assertDoesNotThrow(() -> adapter.decide(app, "2", true, "一级通过"));
    }

    @Test
    void secondLevelApproverUsesCurrentEngineTaskInsteadOfStaleApplicationCache() {
        jdbc.update("delete from flow_task where id=30");
        jdbc.update("insert into flow_task(id,instance_id,node_type,del_flag) values(31,20,1,'0')");
        jdbc.update("update flow_user set associated=31,processed_by='3'");
        when(engine.completeTask(any(CompleteTaskDTO.class))).thenReturn(true);
        when(engine.getBusinessStatus("pm:40")).thenReturn("finish");
        assertDoesNotThrow(() -> adapter.decide(app, "3", true, "二级通过"));
        var capture = ArgumentCaptor.forClass(CompleteTaskDTO.class);
        verify(engine).completeTask(capture.capture());
        assertEquals(31L, capture.getValue().getTaskId());
    }

    @Test
    void parallelApprovalTasksCanStartWithoutLosingTheInstanceBinding() {
        jdbc.update("insert into flow_task(id,instance_id,node_type,del_flag) values(31,20,1,'0')");
        when(engine.startCompleteTask(any())).thenReturn(true);
        assertEquals("20", adapter.start(app).instanceId());
    }

    @Test
    void parallelBranchesUseTheActorsOwnTaskAndNeverAutoCompleteAnotherBranch() {
        jdbc.update("insert into flow_task(id,instance_id,node_type,del_flag) values(31,20,1,'0')");
        jdbc.update("insert into flow_user values(51,31,'3','1','0')");
        when(engine.completeTask(any(CompleteTaskDTO.class))).thenReturn(true);
        when(engine.getBusinessStatus("pm:40")).thenReturn("waiting");
        assertEquals("waiting", adapter.decide(app, "3", true, "通过本分支"));
        var capture = ArgumentCaptor.forClass(CompleteTaskDTO.class);
        verify(engine, times(1)).completeTask(capture.capture());
        assertEquals(31L, capture.getValue().getTaskId());
        assertEquals(2, adapter.currentTasks(app, "3").size());
        assertFalse(adapter.currentTasks(app, "3").getFirst().canHandle());
        assertTrue(adapter.currentTasks(app, "3").get(1).canHandle());
    }

    @Test
    void countersignatureRemainsPendingUntilRemainingActualCandidateApproves() {
        jdbc.update("insert into flow_user values(51,30,'3','1','0')");
        when(engine.completeTask(any(CompleteTaskDTO.class)))
                .thenAnswer(
                        invocation -> {
                            jdbc.update(
                                    "delete from flow_user where associated=30 and"
                                            + " processed_by='2'");
                            return true;
                        });
        when(engine.getBusinessStatus("pm:40")).thenReturn("waiting");
        assertEquals("waiting", adapter.decide(app, "2", true, "会签通过"));
        assertFalse(adapter.currentTasks(app, "2").getFirst().canHandle());
        assertTrue(adapter.currentTasks(app, "3").getFirst().canHandle());
        assertThrows(ServiceException.class, () -> adapter.decide(app, "2", true, "重复会签"));
    }

    @Test
    void copyRecipientsCannotHandleButActualDelegatesRemainEligible() {
        jdbc.update("update flow_user set type='4'");
        assertThrows(ServiceException.class, () -> adapter.decide(app, "2", false, "非法退回"));
        verifyNoInteractions(engine, tasks);
        jdbc.update("update flow_user set type='3'");
        when(engine.completeTask(any(CompleteTaskDTO.class))).thenReturn(true);
        when(engine.getBusinessStatus("pm:40")).thenReturn("waiting");
        assertEquals("waiting", adapter.decide(app, "2", true, "委托审批"));
    }

    @Test
    void terminalEventNeedsTheBoundInstanceAndActualEngineTerminalStatus() {
        when(engine.getBusinessStatus("pm:40")).thenReturn("waiting");
        assertFalse(adapter.authenticEvent(app, 20L, "finish"));
        when(engine.getBusinessStatus("pm:40")).thenReturn("finish");
        assertFalse(adapter.authenticEvent(app, 21L, "finish"));
        assertTrue(adapter.authenticEvent(app, 20L, "finish"));
    }

    @Test
    void conditionsReceiveSubmittedBusinessFactsFromImmutableSnapshot() {
        Project snapshot = new Project();
        snapshot.setId("60");
        snapshot.setOwnerId("7");
        snapshot.setAcquisitionMode("TENDER");
        snapshot.setEstimatedAmount(new BigDecimal("120000.00"));
        snapshot.setProjectAmount(new BigDecimal("90000.00"));
        app.setSnapshot(snapshot);
        app.setSubmittedBy("7");
        when(engine.startCompleteTask(any())).thenReturn(true);
        adapter.start(app);
        var capture = ArgumentCaptor.forClass(StartProcessDTO.class);
        verify(engine).startCompleteTask(capture.capture());
        var variables = capture.getValue().getVariables();
        assertEquals("REGISTRATION", variables.get("applicationType"));
        assertEquals("TENDER", variables.get("acquisitionMode"));
        assertEquals(new BigDecimal("120000.00"), variables.get("estimatedAmount"));
        assertEquals(new BigDecimal("90000.00"), variables.get("projectAmount"));
        assertFalse(variables.containsKey("ignore"));
        assertFalse(variables.containsKey("handler"));
    }

    @Test
    void startsOnlyServerSelectedDefinitionAndNamespacedBusinessIdentity() {
        when(engine.startCompleteTask(any())).thenReturn(true);
        assertEquals(new ProjectWorkflow.Binding("20", "30"), adapter.start(app));
        var capture = ArgumentCaptor.forClass(StartProcessDTO.class);
        verify(engine).startCompleteTask(capture.capture());
        assertEquals("pm:40", capture.getValue().getBusinessId());
        assertEquals("pm_registration", capture.getValue().getFlowCode());
        assertNull(capture.getValue().getHandler());
        assertEquals("REGISTRATION", capture.getValue().getVariables().get("applicationType"));
    }
}

package org.dromara.project.application;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLTimeoutException;
import java.time.LocalDate;
import java.util.concurrent.*;
import java.util.function.Supplier;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.core.utils.SpringUtils;
import org.dromara.project.domain.*;
import org.dromara.project.infrastructure.*;
import org.dromara.project.mapper.*;
import org.dromara.project.support.ProjectMybatisTestSupport;
import org.dromara.project.web.ProjectRequests.*;
import org.dromara.workflow.api.event.ProcessEvent;
import org.junit.jupiter.api.*;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * Exercises the real service, MyBatis-Plus repository and state policy; only the external engine is
 * substituted.
 */
@Tag("dev")
class ProjectApplicationServiceTest {
    static AnnotationConfigApplicationContext jsonContext;
    ProjectRepository repository;
    ProjectWorkflow workflow;
    TestActor actor;
    ProjectApplicationService service;
    TransactionTemplate tx;
    JdbcTemplate jdbc;

    @BeforeAll
    static void json() {
        jsonContext = new AnnotationConfigApplicationContext();
        jsonContext.registerBean(
                JsonMapper.class, () -> JsonMapper.builder().findAndAddModules().build());
        jsonContext.registerBean(SpringUtils.class);
        jsonContext.refresh();
    }

    @AfterAll
    static void close() {
        jsonContext.close();
    }

    @BeforeEach
    void setup() throws Exception {
        DriverManagerDataSource source =
                new DriverManagerDataSource(
                        "jdbc:h2:mem:"
                                + System.nanoTime()
                                + ";MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=150",
                        "sa",
                        "");
        jdbc = new JdbcTemplate(source);
        Path schema = Path.of("../../../ops/migrations/V001__project.sql");
        String sql = Files.readString(schema).replace("snapshot JSON", "snapshot LONGTEXT");
        for (String statement : sql.split(";")) if (!statement.isBlank()) jdbc.execute(statement);
        var session = ProjectMybatisTestSupport.session(source);
        repository =
                new ProjectRepository(
                        session.getMapper(ProjectMapper.class),
                        session.getMapper(ProjectApplicationMapper.class),
                        session.getMapper(ProjectEventMapper.class),
                        session.getMapper(PreparationTaskMapper.class));
        workflow = mock(ProjectWorkflow.class);
        when(workflow.start(any())).thenReturn(new ProjectWorkflow.Binding("101", "102"));
        when(workflow.decide(any(), anyString(), anyBoolean(), nullable(String.class)))
                .thenAnswer(
                        invocation ->
                                invocation.<Boolean>getArgument(2) ? "finish" : "termination");
        actor = new TestActor();
        service = new ProjectApplicationService(repository, workflow, actor);
        tx = new TransactionTemplate(new DataSourceTransactionManager(source));
    }

    String create(String mode) {
        return service.create(basic("原始项目", mode)).getId();
    }

    Basic basic(String name, String mode) {
        return new Basic(name, "建设单位", null, null, "广州", null, mode, new BigDecimal("100.00"));
    }

    String submit(String id) {
        service.registration(id);
        return repository.applications(id).getFirst().getId();
    }

    void approve(String app) {
        actor.user = "2";
        actor.review = true;
        service.decision(app, new Decision("APPROVE", "同意"));
        actor.user = "1";
        actor.review = false;
    }

    @Test
    void intermediateApprovalRetainsSubmittedApplicationAndReviewStage() {
        String id = create("DIRECT");
        String appId = submit(id);
        actor.user = "2";
        actor.review = true;
        when(workflow.decide(any(), anyString(), eq(true), nullable(String.class)))
                .thenReturn("waiting");
        when(workflow.currentTasks(any(), eq("2")))
                .thenReturn(
                        java.util.List.of(
                                new ProjectApplication.CurrentTask("103", "二级审批", false)));
        service.decision(appId, new Decision("APPROVE", "一级审核通过"));
        assertEquals("SUBMITTED", repository.application(appId, false).getStatus());
        assertEquals("REGISTRATION_REVIEW", repository.project(id, false).getStage());
        assertNull(repository.application(appId, false).getFinishedAt());
        assertEquals("103", repository.application(appId, false).getTaskId());
        assertFalse(service.detail(id).getApplications().getFirst().isCanHandle());
        assertEquals(
                "二级审批",
                service.detail(id)
                        .getApplications()
                        .getFirst()
                        .getCurrentTasks()
                        .getFirst()
                        .nodeName());
    }

    @Test
    void taskCandidacyDoesNotReplaceBusinessApprovalPermission() {
        String id = create("DIRECT");
        submit(id);
        when(workflow.currentTasks(any(), eq("1")))
                .thenReturn(
                        java.util.List.of(new ProjectApplication.CurrentTask("102", "部门审核", true)));
        assertFalse(service.detail(id).getApplications().getFirst().isCanHandle());
        assertFalse(
                service.detail(id)
                        .getApplications()
                        .getFirst()
                        .getCurrentTasks()
                        .getFirst()
                        .canHandle());
        actor.review = true;
        assertTrue(service.detail(id).getApplications().getFirst().isCanHandle());
    }

    @Test
    void pendingApprovalPageCountsOnlyCurrentAssignedTasksAndDeduplicatesParallelTasks() {
        jdbc.execute(
                "create table flow_task(id bigint primary key,instance_id bigint,node_type"
                        + " int,del_flag varchar(1))");
        jdbc.execute(
                "create table flow_user(id bigint primary key,associated bigint,processed_by"
                        + " varchar(50),type varchar(1),del_flag varchar(1))");
        String id = create("DIRECT");
        String appId = submit(id);
        jdbc.update("insert into flow_task values(102,101,1,'0'),(103,101,1,'0')");
        jdbc.update(
                "insert into flow_user"
                        + " values(1,102,'2','1','0'),(2,103,'2','1','0'),(3,102,'3','4','0')");
        actor.user = "2";
        actor.review = true;
        assertEquals(1, service.approvals("SUBMITTED", 1, 20).total());
        assertEquals(appId, service.approvals("SUBMITTED", 1, 20).rows().getFirst().getId());
        actor.user = "3";
        assertEquals(0, service.approvals("SUBMITTED", 1, 20).total());
        jdbc.update("delete from flow_user where processed_by='2'");
        actor.user = "2";
        assertEquals(0, service.approvals("SUBMITTED", 1, 20).total());
    }

    @Test
    void returnAndResubmitRetainOriginalSnapshotAndIndependentApplication() {
        String id = create("DIRECT");
        String first = submit(id);
        actor.user = "2";
        actor.review = true;
        service.decision(first, new Decision("RETURN", "补充客户信息"));
        assertEquals("DRAFT", repository.project(id, false).getStage());
        actor.user = "1";
        actor.review = false;
        service.edit(id, basic("修订项目", "DIRECT"));
        String second = submit(id);
        assertNotEquals(first, second);
        assertEquals("原始项目", repository.application(first, false).getSnapshot().getName());
        assertEquals("RETURNED", repository.application(first, false).getStatus());
        assertEquals("修订项目", repository.application(second, false).getSnapshot().getName());
        assertEquals(2, repository.applications(id).size());
    }

    @Test
    void ownerBoundaryAppliesToReadAndEveryMutationEvenForReviewer() {
        String id = create("DIRECT");
        actor.user = "3";
        assertThrows(ServiceException.class, () -> service.detail(id));
        assertEquals(0, service.list(null, null, 1, 20).total());
        actor.review = true;
        assertEquals(id, service.detail(id).getId());
        assertFalse(service.detail(id).isCanEdit());
        assertThrows(ServiceException.class, () -> service.edit(id, basic("越权修改", "DIRECT")));
        assertThrows(ServiceException.class, () -> service.registration(id));
        assertThrows(ServiceException.class, () -> service.terminate(id, new Termination("越权终止")));
    }

    @Test
    void duplicateDecisionAndDuplicateInitiationCannotProduceExtraEventsOrApplications() {
        String id = create("DIRECT");
        String first = submit(id);
        approve(first);
        actor.user = "2";
        actor.review = true;
        int count = repository.events(id).size();
        assertThrows(
                ServiceException.class,
                () -> service.decision(first, new Decision("APPROVE", null)));
        assertEquals(count, repository.events(id).size());
        actor.user = "1";
        actor.review = false;
        Initiation body =
                new Initiation(
                        "负责人",
                        new BigDecimal("100.00"),
                        LocalDate.now(),
                        LocalDate.now().plusDays(2));
        service.initiation(id, body);
        approve(repository.applications(id).getFirst().getId());
        assertEquals("ESTABLISHED", repository.project(id, false).getStage());
        assertThrows(ServiceException.class, () -> service.initiation(id, body));
        assertEquals(2, repository.applications(id).size());
    }

    @Test
    void failedEngineStartRollsBackStateApplicationAndTimelineTogether() {
        String id = create("DIRECT");
        int count = repository.events(id).size();
        when(workflow.start(any())).thenThrow(new ServiceException("引擎不可用"));
        assertThrows(
                ServiceException.class,
                () -> tx.executeWithoutResult(status -> service.registration(id)));
        assertEquals("DRAFT", repository.project(id, false).getStage());
        assertTrue(repository.applications(id).isEmpty());
        assertEquals(count, repository.events(id).size());
    }

    @Test
    void partialAndInitialWorkflowCallbacksAreNoOps() {
        String id = create("DIRECT");
        String appId = submit(id);
        int eventCount = repository.events(id).size();
        ProcessEvent event = new ProcessEvent();
        event.setBusinessId("pm:" + appId);
        event.setInstanceId(101L);
        event.setFlowCode("pm_registration");
        assertDoesNotThrow(() -> service.workflowChanged(event));
        event.setStatus("finish");
        event.setFlowCode(null);
        assertDoesNotThrow(() -> service.workflowChanged(event));
        event.setFlowCode("pm_registration");
        event.setInstanceId(null);
        assertDoesNotThrow(() -> service.workflowChanged(event));
        event.setBusinessId(null);
        assertDoesNotThrow(() -> service.workflowChanged(event));
        assertEquals("REGISTRATION_REVIEW", repository.project(id, false).getStage());
        assertEquals("SUBMITTED", repository.application(appId, false).getStatus());
        assertEquals(eventCount, repository.events(id).size());
    }

    @Test
    void terminalCallbackIsIdempotentAndUnrelatedFlowCannotMutateProject() {
        String id = create("DIRECT");
        String appId = submit(id);
        ProcessEvent event = new ProcessEvent();
        event.setBusinessId("pm:" + appId);
        event.setInstanceId(101L);
        event.setStatus("finish");
        event.setFlowCode("other_flow");
        when(workflow.authenticEvent(any(), eq(101L), eq("finish"))).thenReturn(true);
        service.workflowChanged(event);
        assertEquals("REGISTRATION_REVIEW", repository.project(id, false).getStage());
        event.setFlowCode("pm_registration");
        service.workflowChanged(event);
        int count = repository.events(id).size();
        service.workflowChanged(event);
        assertEquals("REGISTERED", repository.project(id, false).getStage());
        assertEquals(count, repository.events(id).size());
        assertEquals("APPROVED", repository.application(appId, false).getStatus());
    }

    @Test
    void lostTenderCannotInitiateAndTasksCannotBeEditedAfterOpening() {
        String id = create("TENDER");
        approve(submit(id));
        service.bid(id, new Bid("投标项目", "公开招标", LocalDate.now()));
        approve(repository.applications(id).getFirst().getId());
        service.createTask(id, new TaskCreate("准备标书", "BID_DOCUMENT", "张三", null, null));
        String taskId = repository.tasks(id).getFirst().getId();
        service.opening(id, new Opening("LOST", LocalDate.now(), null, "未中标"));
        assertThrows(
                ServiceException.class,
                () ->
                        service.initiation(
                                id,
                                new Initiation(
                                        "负责人", BigDecimal.ONE, LocalDate.now(), LocalDate.now())));
        assertThrows(
                ServiceException.class,
                () -> service.updateTask(id, taskId, new TaskUpdate("DONE", null)));
        assertEquals("BID_LOST", repository.project(id, false).getStage());
    }

    @Test
    void returnedDecisionRequiresCommentAndClerkCannotApprove() {
        String id = create("DIRECT");
        String app = submit(id);
        assertThrows(
                ServiceException.class, () -> service.decision(app, new Decision("APPROVE", null)));
        actor.user = "2";
        actor.review = true;
        assertThrows(
                ServiceException.class, () -> service.decision(app, new Decision("RETURN", "  ")));
        assertEquals("SUBMITTED", repository.application(app, false).getStatus());
    }

    @Test
    void paginationKeepsEmptyOverflowOwnerAndLiteralSearchBoundaries() {
        String first = service.create(basic("100%_工程", "DIRECT")).getId();
        service.create(basic("100ab工程", "DIRECT"));
        actor.user = "3";
        service.create(basic("100%_外部工程", "DIRECT"));
        actor.user = "1";
        var literal = service.list("%_", null, 1, 1);
        assertEquals(1, literal.total());
        assertEquals(first, literal.rows().getFirst().getId());
        var overflow = service.list(null, null, 99, 1);
        assertEquals(2, overflow.total());
        assertTrue(overflow.rows().isEmpty());
    }

    @Test
    void projectSaveClearsOptionalValuesButCannotChangeOwnershipOrCreationTime() {
        String id = create("DIRECT");
        Project original = repository.project(id, false);
        Project update = repository.project(id, false);
        update.setContactName("联系人");
        repository.saveProject(update);
        update.setContactName(null);
        update.setEstimatedAmount(null);
        update.setOwnerId("999");
        update.setOwnerName("other");
        update.setCreatedAt(original.getCreatedAt().plusDays(1));
        repository.saveProject(update);
        Project saved = repository.project(id, false);
        assertNull(saved.getContactName());
        assertNull(saved.getEstimatedAmount());
        assertEquals(original.getOwnerId(), saved.getOwnerId());
        assertEquals(original.getOwnerName(), saved.getOwnerName());
        assertEquals(original.getCreatedAt(), saved.getCreatedAt());
    }

    @Test
    void finishingApplicationCannotOverwriteSnapshotAndClearsTheLiveTaskOnce() {
        String id = create("DIRECT");
        String applicationId = submit(id);
        ProjectApplication update = repository.application(applicationId, false);
        update.getSnapshot().setName("篡改快照");
        update.setStatus("APPROVED");
        update.setComment(null);
        assertTrue(repository.finishApplication(update));
        assertFalse(repository.finishApplication(update));
        ProjectApplication saved = repository.application(applicationId, false);
        assertEquals("原始项目", saved.getSnapshot().getName());
        assertNull(saved.getTaskId());
        assertNull(saved.getComment());
    }

    @Test
    void lockingReadsHoldProjectAndApplicationUntilTransactionCompletes() {
        String id = create("DIRECT");
        String applicationId = submit(id);
        try (var executor = Executors.newSingleThreadExecutor()) {
            tx.executeWithoutResult(
                    status -> {
                        repository.project(id, true);
                        repository.application(applicationId, true);
                        for (Supplier<?> competingRead :
                                java.util.List.<Supplier<?>>of(
                                        () -> repository.project(id, true),
                                        () -> repository.application(applicationId, true))) {
                            var competing =
                                    executor.submit(() -> tx.execute(s -> competingRead.get()));
                            var failure =
                                    assertThrows(
                                            ExecutionException.class,
                                            () -> competing.get(5, TimeUnit.SECONDS));
                            // MP selectOne uses a cursor; assert the database lock timeout, not its
                            // wrapper.
                            assertInstanceOf(
                                    SQLTimeoutException.class, failure.getCause().getCause());
                        }
                    });
            assertDoesNotThrow(() -> tx.execute(s -> repository.project(id, true)));
            assertDoesNotThrow(() -> tx.execute(s -> repository.application(applicationId, true)));
        }
    }

    static class TestActor extends ProjectActor {
        String user = "1";
        boolean review;

        @Override
        public String id() {
            return user;
        }

        @Override
        public String name() {
            return "user" + user;
        }

        @Override
        public boolean reviewer() {
            return review;
        }

        @Override
        public boolean canEdit() {
            return true;
        }

        @Override
        public boolean canSubmit() {
            return true;
        }

        @Override
        public void requireReviewer() {
            ProjectRules.require(review, "仅审核负责人可办理");
        }
    }
}

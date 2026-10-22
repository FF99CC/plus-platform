package org.dromara.project.application;

import static org.dromara.project.domain.ProjectRules.*;

import cn.hutool.core.util.IdUtil;
import java.time.LocalDateTime;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.dromara.common.json.utils.JsonUtils;
import org.dromara.project.domain.*;
import org.dromara.project.infrastructure.ProjectRepository;
import org.dromara.project.infrastructure.ProjectWorkflow;
import org.dromara.project.web.ProjectDetail;
import org.dromara.project.web.ProjectRequests.*;
import org.dromara.workflow.api.event.ProcessEvent;
import org.springframework.beans.BeanUtils;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(rollbackFor = Exception.class)
public class ProjectApplicationService {
    private final ProjectRepository repository;
    private final ProjectWorkflow workflow;
    private final ProjectActor actor;

    @Transactional(readOnly = true)
    public ProjectRepository.Page<Project> list(String search, String stage, int page, int size) {
        require(stage == null || stage.isBlank() || STAGES.contains(stage), "未知项目阶段");
        return repository.projects(actor.reviewer() ? null : actor.id(), search, stage, page, size);
    }

    @Transactional(readOnly = true)
    public ProjectDetail detail(String id) {
        Project project = repository.project(id, false);
        requireReadable(project.getOwnerId(), actor.id(), actor.reviewer());
        ProjectDetail detail = new ProjectDetail();
        BeanUtils.copyProperties(project, detail);
        var applications = repository.applications(id);
        applications.forEach(this::decorateApplication);
        detail.setApplications(applications);
        detail.setEvents(repository.events(id));
        detail.setTasks(repository.tasks(id));
        boolean owner = project.getOwnerId().equals(actor.id());
        detail.setCanEdit(owner && actor.canEdit() && "DRAFT".equals(project.getStage()));
        detail.setCanSubmit(
                owner
                        && actor.canSubmit()
                        && Set.of("DRAFT", "REGISTERED", "BID_WON").contains(project.getStage()));
        return detail;
    }

    public ProjectDetail create(Basic body) {
        Project project = new Project();
        project.setId(IdUtil.getSnowflakeNextIdStr());
        copyBasic(body, project);
        project.setOwnerId(actor.id());
        project.setOwnerName(actor.name());
        project.setStage("DRAFT");
        project.setCreatedAt(LocalDateTime.now());
        project.setUpdatedAt(project.getCreatedAt());
        repository.insertProject(project);
        event(project.getId(), "CREATED", "创建项目备案草稿");
        return detail(project.getId());
    }

    private void copyBasic(Basic body, Project project) {
        project.setName(body.name().trim());
        project.setCustomerName(body.customerName().trim());
        project.setContactName(body.contactName());
        project.setContactPhone(body.contactPhone());
        project.setLocation(body.location().trim());
        project.setProjectType(body.projectType());
        project.setAcquisitionMode(body.acquisitionMode());
        project.setEstimatedAmount(body.estimatedAmount());
    }

    private Project owned(String id) {
        Project project = repository.project(id, true);
        requireOwner(project.getOwnerId(), actor.id());
        return project;
    }

    private void save(Project project) {
        project.setUpdatedAt(LocalDateTime.now());
        repository.saveProject(project);
    }

    public ProjectDetail edit(String id, Basic body) {
        Project project = owned(id);
        require("DRAFT".equals(project.getStage()), "仅备案草稿或备案退回后可修改基础信息");
        copyBasic(body, project);
        save(project);
        event(id, "UPDATED", "修改备案草稿");
        return detail(id);
    }

    public ProjectDetail registration(String id) {
        Project project = owned(id);
        submit(project, "REGISTRATION");
        return detail(id);
    }

    public ProjectDetail bid(String id, Bid body) {
        Project project = owned(id);
        ProjectRules.submit(project.getStage(), project.getAcquisitionMode(), "BID");
        project.setBidName(body.bidName().trim());
        project.setBidType(body.bidType().trim());
        project.setBidDeadline(body.bidDeadline());
        submit(project, "BID");
        return detail(id);
    }

    public ProjectDetail initiation(String id, Initiation body) {
        Project project = owned(id);
        ProjectRules.submit(project.getStage(), project.getAcquisitionMode(), "INITIATION");
        require(!body.plannedEnd().isBefore(body.plannedStart()), "计划结束日期不能早于开始日期");
        project.setManagerName(body.managerName().trim());
        project.setProjectAmount(body.projectAmount());
        project.setPlannedStart(body.plannedStart());
        project.setPlannedEnd(body.plannedEnd());
        submit(project, "INITIATION");
        return detail(id);
    }

    private void submit(Project project, String type) {
        project.setStage(
                ProjectRules.submit(project.getStage(), project.getAcquisitionMode(), type));
        save(project);
        ProjectApplication application = new ProjectApplication();
        application.setId(IdUtil.getSnowflakeNextIdStr());
        application.setProjectId(project.getId());
        application.setProjectName(project.getName());
        application.setType(type);
        application.setStatus("SUBMITTED");
        application.setSubmittedBy(actor.id());
        application.setSubmittedAt(LocalDateTime.now());
        application.setSnapshot(
                JsonUtils.parseObject(JsonUtils.toJsonString(project), Project.class));
        repository.insertApplication(application);
        ProjectWorkflow.Binding binding = workflow.start(application);
        repository.bindWorkflow(application.getId(), binding.instanceId(), binding.taskId());
        event(project.getId(), type + "_SUBMITTED", typeName(type) + "已提交审批");
    }

    public ProjectDetail followUp(String id, FollowUp body) {
        Project project = owned(id);
        require(
                !Set.of("DRAFT", "REGISTRATION_REVIEW", "TERMINATED", "ESTABLISHED")
                        .contains(project.getStage()),
                "仅备案通过且未终止、未立项项目可跟踪");
        event(id, "FOLLOW_UP", body.message().trim());
        save(project);
        return detail(id);
    }

    public ProjectDetail terminate(String id, Termination body) {
        Project project = owned(id);
        requireTerminable(project.getStage());
        project.setStage("TERMINATED");
        project.setTerminationReason(body.reason().trim());
        save(project);
        event(id, "TERMINATED", "项目终止：" + body.reason().trim());
        return detail(id);
    }

    public ProjectDetail createTask(String id, TaskCreate body) {
        Project project = owned(id);
        require("BID_PREPARATION".equals(project.getStage()), "仅投标准备阶段可登记办理事项");
        PreparationTask task = new PreparationTask();
        task.setId(IdUtil.getSnowflakeNextIdStr());
        task.setProjectId(id);
        task.setTitle(body.title().trim());
        task.setKind(body.kind());
        task.setAssigneeName(body.assigneeName());
        task.setDueDate(body.dueDate());
        task.setNote(body.note());
        task.setStatus("TODO");
        repository.insertTask(task);
        save(project);
        event(id, "TASK_CREATED", "登记准备事项：" + task.getTitle());
        return detail(id);
    }

    public ProjectDetail updateTask(String id, String taskId, TaskUpdate body) {
        Project project = owned(id);
        require("BID_PREPARATION".equals(project.getStage()), "仅投标准备阶段可更新办理事项");
        require(repository.updateTask(id, taskId, body.status(), body.note()), "办理事项不存在或不属于此项目");
        save(project);
        event(id, "TASK_UPDATED", "准备事项状态更新为" + ("DONE".equals(body.status()) ? "已完成" : "待办"));
        return detail(id);
    }

    public ProjectDetail opening(String id, Opening body) {
        Project project = owned(id);
        require("BID_PREPARATION".equals(project.getStage()), "仅投标准备阶段可登记开标结果");
        require(
                !"WON".equals(body.bidResult())
                        || body.bidAmount() != null && body.bidAmount().signum() > 0,
                "中标金额必须大于零");
        project.setBidResult(body.bidResult());
        project.setOpeningDate(body.openingDate());
        project.setBidAmount(body.bidAmount());
        project.setBidRemark(body.bidRemark());
        project.setStage("WON".equals(body.bidResult()) ? "BID_WON" : "BID_LOST");
        save(project);
        event(id, "BID_RESULT", "登记开标结果：" + ("WON".equals(body.bidResult()) ? "中标" : "未中标"));
        return detail(id);
    }

    @Transactional(readOnly = true)
    public ProjectRepository.Page<ProjectApplication> approvals(String status, int page, int size) {
        actor.requireReviewer();
        require(Set.of("SUBMITTED", "APPROVED", "RETURNED").contains(status), "未知申请状态");
        var result = repository.approvals(status, actor.id(), page, size);
        result.rows().forEach(this::decorateApplication);
        return result;
    }

    private void decorateApplication(ProjectApplication app) {
        if (!"SUBMITTED".equals(app.getStatus())) return;
        var tasks = workflow.currentTasks(app, actor.id());
        // Engine candidacy never replaces the business permission required by the endpoint.
        if (!actor.reviewer()) {
            tasks =
                    tasks.stream()
                            .map(
                                    task ->
                                            new ProjectApplication.CurrentTask(
                                                    task.taskId(), task.nodeName(), false))
                            .toList();
        }
        app.setCurrentTasks(tasks);
        app.setCanHandle(tasks.stream().anyMatch(ProjectApplication.CurrentTask::canHandle));
    }

    public void decision(String id, Decision body) {
        actor.requireReviewer();
        ProjectApplication initial = repository.application(id, false);
        repository.project(initial.getProjectId(), true);
        ProjectApplication app = repository.application(id, true);
        require("SUBMITTED".equals(app.getStatus()), "该申请已处理，请勿重复审批");
        boolean approve = "APPROVE".equals(body.decision());
        require(approve || body.comment() != null && !body.comment().isBlank(), "退回必须填写修改意见");
        repository.decisionComment(id, body.comment());
        String status = workflow.decide(app, actor.id(), approve, body.comment());
        // A successful task completion may only advance to another node or record one signature.
        // Only the actual engine terminal status can finish the business application.
        if ("finish".equals(status) || "termination".equals(status)) {
            finish(id, "finish".equals(status));
        } else {
            var currentTasks = workflow.currentTasks(app, actor.id());
            repository.bindWorkflow(
                    id,
                    app.getInstanceId(),
                    currentTasks.isEmpty() ? null : currentTasks.getFirst().taskId());
            event(
                    app.getProjectId(),
                    app.getType() + "_REVIEWED",
                    typeName(app.getType()) + "节点审批通过，流程继续流转");
        }
    }

    @EventListener
    public void workflowChanged(ProcessEvent event) {
        // WarmFlow also emits initialization events before status and instance binding are
        // complete.
        if (event.getBusinessId() == null
                || event.getFlowCode() == null
                || event.getStatus() == null
                || event.getInstanceId() == null
                || !event.getBusinessId().matches("pm:[0-9]+")) return;
        if (!Set.of("pm_registration", "pm_bid", "pm_initiation").contains(event.getFlowCode()))
            return;
        if (!Set.of("finish", "termination").contains(event.getStatus())) return;
        ProjectApplication app = repository.application(event.getBusinessId().substring(3), false);
        if (!ProjectWorkflow.flowCode(app.getType()).equals(event.getFlowCode())) return;
        if (!workflow.authenticEvent(app, event.getInstanceId(), event.getStatus())) return;
        finish(app.getId(), "finish".equals(event.getStatus()));
    }

    private void finish(String id, boolean approve) {
        ProjectApplication initial = repository.application(id, false);
        Project project = repository.project(initial.getProjectId(), true);
        ProjectApplication app = repository.application(id, true);
        if (!"SUBMITTED".equals(app.getStatus())) return;
        // repeated callback is a no-op
        String stage =
                ProjectRules.decide(
                        project.getStage(), project.getAcquisitionMode(), app.getType(), approve);
        app.setStatus(approve ? "APPROVED" : "RETURNED");
        app.setFinishedAt(LocalDateTime.now());
        if (!repository.finishApplication(app)) return;
        project.setStage(stage);
        save(project);
        event(
                project.getId(),
                app.getType() + "_" + app.getStatus(),
                typeName(app.getType())
                        + (approve ? "审批通过" : "退回修改")
                        + (app.getComment() == null || app.getComment().isBlank()
                                ? ""
                                : "：" + app.getComment()));
    }

    private String typeName(String type) {
        return switch (type) {
            case "REGISTRATION" -> "备案申请";
            case "BID" -> "投标申请";
            case "INITIATION" -> "施工立项申请";
            default -> type;
        };
    }

    private void event(String id, String type, String message) {
        ProjectEvent event = new ProjectEvent();
        event.setId(IdUtil.getSnowflakeNextIdStr());
        event.setProjectId(id);
        event.setType(type);
        // Prefixes are added to 2,000-char user messages; retain the complete business text within
        // the column limit.
        event.setMessage(message.length() > 2000 ? message.substring(0, 2000) : message);
        event.setActorId(actor.id());
        event.setActorName(actor.name());
        event.setCreatedAt(LocalDateTime.now());
        repository.event(event);
    }
}

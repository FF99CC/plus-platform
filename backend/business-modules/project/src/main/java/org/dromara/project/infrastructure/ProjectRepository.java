package org.dromara.project.infrastructure;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import java.util.List;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.project.domain.*;
import org.dromara.project.mapper.*;
import org.springframework.stereotype.Repository;

/** MyBatis-Plus persistence; row locks serialize mutations and their workflow transitions. */
@Repository
public class ProjectRepository {
    private final ProjectMapper projects;
    private final ProjectApplicationMapper applications;
    private final ProjectEventMapper events;
    private final PreparationTaskMapper tasks;

    public ProjectRepository(
            ProjectMapper projects,
            ProjectApplicationMapper applications,
            ProjectEventMapper events,
            PreparationTaskMapper tasks) {
        this.projects = projects;
        this.applications = applications;
        this.events = events;
        this.tasks = tasks;
    }

    public void insertProject(Project project) {
        projects.insert(project);
    }

    public void saveProject(Project project) {
        // Entity field strategies retain ownership/creation and explicitly clear nullable fields.
        projects.updateById(project);
    }

    public Project project(String id, boolean lock) {
        Project project =
                projects.selectOne(
                        new LambdaQueryWrapper<Project>()
                                .eq(Project::getId, id)
                                .last(lock, "FOR UPDATE"));
        if (project == null) throw new ServiceException("项目不存在");
        return project;
    }

    public record Page<T>(List<T> rows, long total) {}

    public Page<Project> projects(String ownerId, String search, String stage, int page, int size) {
        var query =
                new LambdaQueryWrapper<Project>()
                        .eq(ownerId != null, Project::getOwnerId, ownerId)
                        .eq(stage != null && !stage.isBlank(), Project::getStage, stage);
        if (search != null && !search.isBlank()) {
            String literal = search.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
            query.and(
                    q ->
                            q.like(Project::getName, literal)
                                    .or()
                                    .like(Project::getCustomerName, literal));
        }
        query.orderByDesc(Project::getUpdatedAt, Project::getId);
        return page(
                projects.selectPage(
                        new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>(
                                page, size),
                        query),
                page);
    }

    private static <T> Page<T> page(
            com.baomidou.mybatisplus.extension.plugins.pagination.Page<T> result,
            int requestedPage) {
        // The shared interceptor resets overflowing pages to page 1. Preserve this API's empty-page
        // contract.
        return new Page<>(
                requestedPage > result.getPages() ? List.of() : result.getRecords(),
                result.getTotal());
    }

    public void insertApplication(ProjectApplication app) {
        applications.insert(app);
    }

    public ProjectApplication application(String id, boolean lock) {
        ProjectApplication app =
                applications.selectOne(
                        new LambdaQueryWrapper<ProjectApplication>()
                                .eq(ProjectApplication::getId, id)
                                .last(lock, "FOR UPDATE"));
        if (app == null) throw new ServiceException("申请不存在");
        return app;
    }

    private LambdaUpdateWrapper<ProjectApplication> submitted(String id) {
        return new LambdaUpdateWrapper<ProjectApplication>()
                .eq(ProjectApplication::getId, id)
                .eq(ProjectApplication::getStatus, "SUBMITTED");
    }

    public void decisionComment(String id, String comment) {
        applications.update(submitted(id).set(ProjectApplication::getComment, comment));
    }

    public void bindWorkflow(String id, String instanceId, String taskId) {
        applications.update(
                submitted(id)
                        .set(ProjectApplication::getInstanceId, instanceId)
                        .set(ProjectApplication::getTaskId, taskId));
    }

    /** Snapshot is intentionally absent: an application's submitted facts are immutable. */
    public boolean finishApplication(ProjectApplication app) {
        return applications.update(
                        submitted(app.getId())
                                .set(ProjectApplication::getStatus, app.getStatus())
                                .set(ProjectApplication::getComment, app.getComment())
                                .set(ProjectApplication::getFinishedAt, app.getFinishedAt())
                                .set(ProjectApplication::getTaskId, null))
                == 1;
    }

    public List<ProjectApplication> applications(String projectId) {
        return applications.selectList(
                new LambdaQueryWrapper<ProjectApplication>()
                        .eq(ProjectApplication::getProjectId, projectId)
                        .orderByDesc(
                                ProjectApplication::getSubmittedAt, ProjectApplication::getId));
    }

    public Page<ProjectApplication> approvals(
            String status, String reviewerId, int page, int size) {
        var query =
                new LambdaQueryWrapper<ProjectApplication>()
                        .eq(ProjectApplication::getStatus, status)
                        .exists(
                                "SUBMITTED".equals(status),
                                "select 1 from flow_task t join flow_user u on u.associated=t.id "
                                        + "where t.instance_id=pm_application.instance_id "
                                        + "and t.node_type=1 and t.del_flag='0' and u.del_flag='0' "
                                        + "and u.type in ('1','2','3') and u.processed_by={0}",
                                reviewerId)
                        .orderByDesc(ProjectApplication::getSubmittedAt, ProjectApplication::getId);
        return page(
                applications.selectPage(
                        new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>(
                                page, size),
                        query),
                page);
    }

    public void event(ProjectEvent event) {
        events.insert(event);
    }

    public List<ProjectEvent> events(String projectId) {
        return events.selectList(
                new LambdaQueryWrapper<ProjectEvent>()
                        .eq(ProjectEvent::getProjectId, projectId)
                        .orderByDesc(ProjectEvent::getCreatedAt, ProjectEvent::getId));
    }

    public void insertTask(PreparationTask task) {
        tasks.insert(task);
    }

    public boolean updateTask(String projectId, String taskId, String status, String note) {
        return tasks.update(
                        new LambdaUpdateWrapper<PreparationTask>()
                                .eq(PreparationTask::getId, taskId)
                                .eq(PreparationTask::getProjectId, projectId)
                                .set(PreparationTask::getStatus, status)
                                .set(PreparationTask::getNote, note))
                == 1;
    }

    public List<PreparationTask> tasks(String projectId) {
        return tasks.selectList(
                new LambdaQueryWrapper<PreparationTask>()
                        .eq(PreparationTask::getProjectId, projectId)
                        .orderByAsc(PreparationTask::getId));
    }
}

package org.dromara.project.web;

import cn.dev33.satoken.annotation.SaCheckPermission;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.domain.R;
import org.dromara.project.application.ProjectApplicationService;
import org.dromara.project.domain.Project;
import org.dromara.project.domain.ProjectApplication;
import org.dromara.project.infrastructure.ProjectRepository.Page;
import org.dromara.project.web.ProjectRequests.*;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@Validated
@RestController
@RequestMapping("/project")
@RequiredArgsConstructor
public class ProjectController {
    private final ProjectApplicationService service;

    @GetMapping("/projects")
    @SaCheckPermission("project:project:list")
    public R<Page<Project>> list(
            @RequestParam(required = false) @Size(max = 200) String search,
            @RequestParam(required = false) String stage,
            @RequestParam(defaultValue = "1") @Min(1) int pageNum,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int pageSize) {
        return R.ok(service.list(search, stage, pageNum, pageSize));
    }

    @GetMapping("/projects/{id}")
    @SaCheckPermission("project:project:query")
    public R<ProjectDetail> detail(@PathVariable @Pattern(regexp = "[0-9]+") String id) {
        return R.ok(service.detail(id));
    }

    @PostMapping("/projects")
    @SaCheckPermission("project:project:add")
    public R<ProjectDetail> create(@Valid @RequestBody Basic body) {
        return R.ok(service.create(body));
    }

    @PutMapping("/projects/{id}")
    @SaCheckPermission("project:project:edit")
    public R<ProjectDetail> edit(@PathVariable String id, @Valid @RequestBody Basic body) {
        return R.ok(service.edit(id, body));
    }

    @PostMapping("/projects/{id}/registration")
    @SaCheckPermission("project:project:submit")
    public R<ProjectDetail> registration(@PathVariable String id) {
        return R.ok(service.registration(id));
    }

    @PostMapping("/projects/{id}/follow-ups")
    @SaCheckPermission("project:project:edit")
    public R<ProjectDetail> followUp(@PathVariable String id, @Valid @RequestBody FollowUp body) {
        return R.ok(service.followUp(id, body));
    }

    @PostMapping("/projects/{id}/terminate")
    @SaCheckPermission("project:project:edit")
    public R<ProjectDetail> terminate(
            @PathVariable String id, @Valid @RequestBody Termination body) {
        return R.ok(service.terminate(id, body));
    }

    @PostMapping("/projects/{id}/bid")
    @SaCheckPermission("project:project:submit")
    public R<ProjectDetail> bid(@PathVariable String id, @Valid @RequestBody Bid body) {
        return R.ok(service.bid(id, body));
    }

    @PostMapping("/projects/{id}/initiation")
    @SaCheckPermission("project:project:submit")
    public R<ProjectDetail> initiation(
            @PathVariable String id, @Valid @RequestBody Initiation body) {
        return R.ok(service.initiation(id, body));
    }

    @PostMapping("/projects/{id}/tasks")
    @SaCheckPermission("project:project:edit")
    public R<ProjectDetail> createTask(
            @PathVariable String id, @Valid @RequestBody TaskCreate body) {
        return R.ok(service.createTask(id, body));
    }

    @PutMapping("/projects/{id}/tasks/{taskId}")
    @SaCheckPermission("project:project:edit")
    public R<ProjectDetail> updateTask(
            @PathVariable String id,
            @PathVariable String taskId,
            @Valid @RequestBody TaskUpdate body) {
        return R.ok(service.updateTask(id, taskId, body));
    }

    @PostMapping("/projects/{id}/opening")
    @SaCheckPermission("project:project:edit")
    public R<ProjectDetail> opening(@PathVariable String id, @Valid @RequestBody Opening body) {
        return R.ok(service.opening(id, body));
    }

    @GetMapping("/approvals")
    @SaCheckPermission("project:approval:list")
    public R<Page<ProjectApplication>> approvals(
            @RequestParam(defaultValue = "SUBMITTED") String status,
            @RequestParam(defaultValue = "1") @Min(1) int pageNum,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int pageSize) {
        return R.ok(service.approvals(status, pageNum, pageSize));
    }

    @PostMapping("/approvals/{id}/decision")
    @SaCheckPermission("project:approval:handle")
    public R<Void> decision(@PathVariable String id, @Valid @RequestBody Decision body) {
        service.decision(id, body);
        return R.ok();
    }
}

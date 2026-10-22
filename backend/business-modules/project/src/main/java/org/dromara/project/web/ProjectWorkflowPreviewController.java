package org.dromara.project.web;

import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.dev33.satoken.annotation.SaMode;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.domain.R;
import org.dromara.project.infrastructure.ProjectWorkflowPreview;
import org.dromara.workflow.common.ConditionalOnEnable;
import org.springframework.web.bind.annotation.*;

@RestController
@ConditionalOnEnable
@RequiredArgsConstructor
@RequestMapping("/project/workflow")
public class ProjectWorkflowPreviewController {
    private final ProjectWorkflowPreview preview;

    @PostMapping("/preview")
    @SaCheckPermission(
            value = {"workflow:definition:query", "workflow:definition:edit"},
            mode = SaMode.AND)
    public R<ProjectWorkflowPreview.Result> preview(
            @Valid @RequestBody ProjectWorkflowPreview.Request request) {
        return R.ok(preview.preview(request));
    }
}

package org.dromara.project.domain;

import com.baomidou.mybatisplus.annotation.*;
import com.fasterxml.jackson.annotation.JsonIgnore;
import java.time.LocalDateTime;
import java.util.List;
import lombok.Data;

@Data
@TableName(value = "pm_application", autoResultMap = true)
public class ProjectApplication {
    @TableId(type = IdType.INPUT)
    private String id;

    private String projectId;
    private String projectName;
    private String type;
    private String status;
    private String submittedBy;
    private LocalDateTime submittedAt;
    private LocalDateTime finishedAt;
    private String comment;

    /** Compatibility cache only; live engine tasks below determine authorization. */
    private String taskId;

    @TableField(exist = false)
    private boolean canHandle;

    @TableField(exist = false)
    private List<CurrentTask> currentTasks = List.of();

    public record CurrentTask(String taskId, String nodeName, boolean canHandle) {}

    @JsonIgnore private String instanceId;

    @TableField(
            typeHandler = org.dromara.project.infrastructure.ProjectSnapshotTypeHandler.class,
            updateStrategy = FieldStrategy.NEVER)
    private Project snapshot;
}

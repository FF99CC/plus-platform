package org.dromara.project.domain;

import com.baomidou.mybatisplus.annotation.*;
import java.time.LocalDateTime;
import lombok.Data;

@Data
@TableName(value = "pm_event")
public class ProjectEvent {
    @TableId(type = IdType.INPUT)
    private String id;

    private String projectId;
    private String type;
    private String message;
    private String actorId;
    private String actorName;
    private LocalDateTime createdAt;
}

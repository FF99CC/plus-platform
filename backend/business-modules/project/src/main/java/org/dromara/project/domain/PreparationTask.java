package org.dromara.project.domain;

import com.baomidou.mybatisplus.annotation.*;
import java.time.LocalDate;
import lombok.Data;

@Data
@TableName(value = "pm_task")
public class PreparationTask {
    @TableId(type = IdType.INPUT)
    private String id;

    private String projectId;
    private String title;
    private String kind;
    private String assigneeName;
    private LocalDate dueDate;
    private String status;
    private String note;
}

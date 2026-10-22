package org.dromara.project.domain;

import com.baomidou.mybatisplus.annotation.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.Data;

@Data
@TableName(value = "pm_project")
public class Project {
    @TableId(type = IdType.INPUT)
    private String id;

    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String name;

    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String customerName;

    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String contactName;

    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String contactPhone;

    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String location;

    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String projectType;

    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String acquisitionMode;

    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private BigDecimal estimatedAmount;

    @TableField(updateStrategy = FieldStrategy.NEVER)
    private String ownerId;

    @TableField(updateStrategy = FieldStrategy.NEVER)
    private String ownerName;

    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String stage;

    @TableField(updateStrategy = FieldStrategy.NEVER)
    private LocalDateTime createdAt;

    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private LocalDateTime updatedAt;

    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String bidName;

    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String bidType;

    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private LocalDate bidDeadline;

    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private LocalDate openingDate;

    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private BigDecimal bidAmount;

    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String bidResult;

    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String bidRemark;

    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String managerName;

    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private BigDecimal projectAmount;

    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private LocalDate plannedStart;

    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private LocalDate plannedEnd;

    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String terminationReason;
}

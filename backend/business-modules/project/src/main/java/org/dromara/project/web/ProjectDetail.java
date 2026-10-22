package org.dromara.project.web;

import java.util.List;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.dromara.project.domain.*;

@Data
@EqualsAndHashCode(callSuper = true)
public class ProjectDetail extends Project {
    private List<ProjectApplication> applications;
    private List<ProjectEvent> events;
    private List<PreparationTask> tasks;
    private boolean canEdit;
    private boolean canSubmit;
}

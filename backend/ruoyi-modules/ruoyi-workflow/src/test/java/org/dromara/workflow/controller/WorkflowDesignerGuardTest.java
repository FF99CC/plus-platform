package org.dromara.workflow.controller;

import org.dromara.common.core.exception.ServiceException;
import org.dromara.warm.flow.core.dto.DefJson;
import org.dromara.warm.flow.core.enums.PublishStatus;
import org.dromara.warm.flow.core.service.DefService;
import org.dromara.warm.flow.orm.entity.FlowDefinition;
import org.dromara.warm.flow.orm.mapper.FlowInstanceMapper;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@Tag("dev")
class WorkflowDesignerGuardTest {
    private final DefService definitions = mock(DefService.class);
    private final FlowInstanceMapper instances = mock(FlowInstanceMapper.class);
    private final WorkflowDesignerGuard guard = new WorkflowDesignerGuard(definitions, instances);

    @Test
    void publishedAndHistoricalVersionsCannotBeOverwrittenWithForgedDraftStatus() {
        for (PublishStatus status : new PublishStatus[] {PublishStatus.PUBLISHED, PublishStatus.EXPIRED}) {
            FlowDefinition stored = new FlowDefinition();
            stored.setIsPublish(status.getKey());
            when(definitions.getById(42L)).thenReturn(stored);
            assertThrows(ServiceException.class, () -> guard.checkDraft(new DefJson().setId(42L).setIsPublish(0)));
        }
        verifyNoInteractions(instances);
    }

    @Test
    void aPreviouslyUsedDefinitionRemainsProtectedAfterUnpublishing() {
        FlowDefinition stored = new FlowDefinition();
        stored.setIsPublish(PublishStatus.UNPUBLISHED.getKey());
        when(definitions.getById(42L)).thenReturn(stored);
        when(instances.selectCount(any())).thenReturn(1L);
        assertThrows(ServiceException.class, () -> guard.checkDraft(new DefJson().setId(42L)));
    }

    @Test
    void unusedDraftCanBeSavedButMissingDefinitionsCannot() {
        FlowDefinition stored = new FlowDefinition();
        stored.setIsPublish(PublishStatus.UNPUBLISHED.getKey());
        when(definitions.getById(42L)).thenReturn(stored);
        when(instances.selectCount(any())).thenReturn(0L);
        assertDoesNotThrow(() -> guard.checkDraft(new DefJson().setId(42L)));
        assertThrows(ServiceException.class, () -> guard.checkDraft(new DefJson().setId(43L)));
        assertThrows(ServiceException.class, () -> guard.checkDraft(new DefJson()));
    }
}

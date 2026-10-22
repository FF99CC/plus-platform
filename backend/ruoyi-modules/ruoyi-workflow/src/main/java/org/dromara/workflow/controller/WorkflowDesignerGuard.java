package org.dromara.workflow.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import lombok.RequiredArgsConstructor;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.warm.flow.core.dto.DefJson;
import org.dromara.warm.flow.core.entity.Definition;
import org.dromara.warm.flow.core.enums.PublishStatus;
import org.dromara.warm.flow.core.service.DefService;
import org.dromara.warm.flow.orm.entity.FlowInstance;
import org.dromara.warm.flow.orm.mapper.FlowInstanceMapper;
import org.dromara.workflow.common.ConditionalOnEnable;
import org.springframework.stereotype.Component;

/** Protects the bundled designer endpoint used by both configuration screens. */
@Aspect
@Component
@ConditionalOnEnable
@RequiredArgsConstructor
public class WorkflowDesignerGuard {
    private final DefService definitions;
    private final FlowInstanceMapper instances;

    @Before("execution(* org.dromara.warm.flow.ui.controller.WarmFlowController.saveJson(..)) && args(request,..)")
    public void beforeSave(DefJson request) {
        StpUtil.checkPermission("workflow:definition:edit");
        checkDraft(request);
    }

    void checkDraft(DefJson request) {
        if (request == null || request.getId() == null) {
            throw new ServiceException("请先创建流程草稿，再保存配置");
        }
        Definition stored = definitions.getById(request.getId());
        if (stored == null) {
            throw new ServiceException("流程不存在，请刷新列表");
        }
        if (!PublishStatus.UNPUBLISHED.getKey().equals(stored.getIsPublish())) {
            throw new ServiceException("已发布和历史流程不能直接修改，请复制为新版本草稿");
        }
        if (instances.selectCount(new QueryWrapper<FlowInstance>().eq("definition_id", request.getId())) > 0) {
            throw new ServiceException("此版本已有审批记录，请复制为新版本草稿后修改");
        }
    }
}

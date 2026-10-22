package org.dromara.project.application;

import cn.dev33.satoken.stp.StpUtil;
import org.dromara.common.satoken.utils.LoginHelper;
import org.springframework.stereotype.Component;

@Component
public class ProjectActor {
    public String id() {
        return LoginHelper.getUserIdStr();
    }

    public String name() {
        var user = LoginHelper.getLoginUser();
        return user != null && user.getNickname() != null && !user.getNickname().isBlank()
                ? user.getNickname()
                : LoginHelper.getUsername();
    }

    public boolean reviewer() {
        return StpUtil.hasPermission("project:approval:handle");
    }

    public boolean canSubmit() {
        return StpUtil.hasPermission("project:project:submit");
    }

    public boolean canEdit() {
        return StpUtil.hasPermission("project:project:edit");
    }

    public void requireReviewer() {
        StpUtil.checkPermission("project:approval:handle");
    }
}

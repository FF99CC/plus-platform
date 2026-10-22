package org.dromara.project.mapper;

import java.util.List;
import org.apache.ibatis.annotations.*;
import org.dromara.project.domain.ProjectApplication.CurrentTask;
import org.dromara.project.infrastructure.ProjectWorkflow.Binding;

/** Engine ownership checks run through the platform's MyBatis session and transaction. */
public interface ProjectWorkflowMapper {
    @Select(
            """
            select i.id as instance_id,t.id as task_id from flow_instance i
            join flow_task t on t.instance_id=i.id
            join flow_definition d on d.id=i.definition_id
            where i.business_id=#{businessId} and d.flow_code=#{flowCode} and t.node_type=1
            and t.del_flag='0' and i.del_flag='0' order by t.id
            """)
    @ConstructorArgs({
        @Arg(column = "instance_id", javaType = String.class),
        @Arg(column = "task_id", javaType = String.class)
    })
    List<Binding> bindings(
            @Param("businessId") String businessId, @Param("flowCode") String flowCode);

    @Select(
            """
            select t.id as task_id,t.node_name,
            case when exists(select 1 from flow_user u where u.associated=t.id
                and u.processed_by=#{userId} and u.type in ('1','2','3') and u.del_flag='0')
                then true else false end as can_handle
            from flow_task t join flow_instance i on i.id=t.instance_id
            join flow_definition d on d.id=i.definition_id
            where i.id=#{instanceId} and i.business_id=#{businessId} and d.flow_code=#{flowCode}
            and t.node_type=1 and t.del_flag='0' and i.del_flag='0' order by t.id
            """)
    @ConstructorArgs({
        @Arg(column = "task_id", javaType = String.class),
        @Arg(column = "node_name", javaType = String.class),
        @Arg(column = "can_handle", javaType = boolean.class)
    })
    List<CurrentTask> currentTasks(
            @Param("instanceId") String instanceId,
            @Param("businessId") String businessId,
            @Param("flowCode") String flowCode,
            @Param("userId") String userId);

    @Select(
            """
            select t.id from flow_task t join flow_instance i on i.id=t.instance_id
            join flow_definition d on d.id=i.definition_id
            where t.id=#{taskId} and i.id=#{instanceId} and i.business_id=#{businessId}
            and d.flow_code=#{flowCode} and t.node_type=1
            and t.del_flag='0' and i.del_flag='0' for update
            """)
    List<String> lockTask(
            @Param("taskId") String taskId,
            @Param("instanceId") String instanceId,
            @Param("businessId") String businessId,
            @Param("flowCode") String flowCode);

    @Select(
            """
            select count(*) from flow_user where associated=#{taskId} and processed_by=#{userId}
            and type in ('1','2','3') and del_flag='0'
            """)
    long assigneeCount(@Param("taskId") String taskId, @Param("userId") String userId);
}

package org.dromara.project.infrastructure;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.util.*;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.json.utils.JsonUtils;
import org.dromara.project.domain.Project;
import org.dromara.system.api.UserService;
import org.dromara.system.api.domain.UserDTO;
import org.dromara.warm.flow.core.dto.FlowCombine;
import org.dromara.warm.flow.core.dto.PathWayData;
import org.dromara.warm.flow.core.entity.Definition;
import org.dromara.warm.flow.core.entity.Node;
import org.dromara.warm.flow.core.entity.Skip;
import org.dromara.warm.flow.core.enums.NodeType;
import org.dromara.warm.flow.core.enums.SkipType;
import org.dromara.warm.flow.core.exception.FlowException;
import org.dromara.warm.flow.core.service.DefService;
import org.dromara.warm.flow.core.service.NodeService;
import org.dromara.warm.flow.core.utils.ExpressionUtil;
import org.dromara.workflow.common.ConditionalOnEnable;
import org.dromara.workflow.rule.SpelRuleComponent;
import org.dromara.workflow.service.IFlwTaskAssigneeService;
import org.springframework.stereotype.Service;

/** Read-only evaluation of saved project definitions; never starts or advances a workflow. */
@Service
@ConditionalOnEnable
@RequiredArgsConstructor
public class ProjectWorkflowPreview {
    private static final String DEPT_LEADER =
            "#{@spelRuleComponent.selectDeptLeaderById(#initiatorDeptId)}";
    private static final Pattern STATIC_ASSIGNEE =
            Pattern.compile("(?:(?:user|role|dept|post):)?[0-9]+");
    private static final Pattern CONDITION =
            Pattern.compile(
                    "(?:eq|ne|gt|ge|lt|le|like|notLike)@@([A-Za-z][A-Za-z0-9]*)\\|[^|]+",
                    Pattern.DOTALL);
    private static final Map<String, String> TYPES =
            Map.of(
                    "pm_registration",
                    "REGISTRATION",
                    "pm_bid",
                    "BID",
                    "pm_initiation",
                    "INITIATION");

    private final DefService definitions;
    private final NodeService nodes;
    private final UserService users;
    private final IFlwTaskAssigneeService assignees;
    private final SpelRuleComponent rules;

    public record Variables(
            @NotNull @jakarta.validation.constraints.Pattern(regexp = "DIRECT|TENDER")
                    String acquisitionMode,
            @Size(max = 200) String projectType,
            @DecimalMin("0") BigDecimal estimatedAmount,
            @Size(max = 200) String bidType,
            @DecimalMin("0") BigDecimal bidAmount,
            @DecimalMin("0") BigDecimal projectAmount) {}

    public record Request(
            @NotBlank @jakarta.validation.constraints.Pattern(regexp = "[0-9]{1,19}")
                    String definitionId,
            @NotBlank @jakarta.validation.constraints.Pattern(regexp = "[0-9]{1,19}")
                    String initiatorId,
            @NotNull @Valid Variables variables) {}

    public record Assignee(String userId, String userName) {}

    public record Step(
            String nodeCode,
            String nodeName,
            Integer nodeType,
            List<Assignee> assignees,
            List<String> configuredAssignees,
            String description) {}

    public record Edge(String fromNodeCode, String toNodeCode, String condition) {}

    public record Result(
            boolean complete, List<Step> steps, List<String> warnings, List<Edge> matchedEdges) {}

    public Result preview(Request request) {
        Definition definition = definitions.getById(id(request.definitionId()));
        if (definition == null || !TYPES.containsKey(definition.getFlowCode())) {
            throw new ServiceException("仅支持项目登记、投标和立项流程定义预览");
        }
        UserDTO initiator = users.selectById(id(request.initiatorId()));
        if (initiator == null
                || initiator.getUserId() == null
                || !"0".equals(initiator.getStatus())) {
            throw new ServiceException("发起人不存在或已停用");
        }
        Map<String, Object> variables = variables(request.variables(), definition.getFlowCode());
        variables.put("initiator", initiator.getUserId().toString());
        variables.put("initiatorDeptId", initiator.getDeptId());
        FlowCombine combine = definitions.getFlowCombineNoDef(definition.getId());
        List<String> warnings = new ArrayList<>();
        List<Step> steps = new ArrayList<>();
        List<Edge> edges = new ArrayList<>();
        try {
            checkDefinition(definition, combine, variables);
            Node current =
                    combine.getAllNodes().stream()
                            .filter(n -> NodeType.START.getKey().equals(n.getNodeType()))
                            .findFirst()
                            .orElseThrow();
            String applyCode = applicationNode(current, combine, variables);
            Set<String> visited = new HashSet<>();
            while (visited.add(current.getNodeCode())) {
                steps.add(step(current, applyCode, initiator, variables));
                if (NodeType.END.getKey().equals(current.getNodeType())) {
                    if (steps.stream()
                            .noneMatch(
                                    s ->
                                            NodeType.BETWEEN.getKey().equals(s.nodeType())
                                                    && !applyCode.equals(s.nodeCode()))) {
                        throw new ServiceException("命中路线未配置提交申请之后的人工审批节点，实际项目提交将被拒绝");
                    }
                    return new Result(true, steps, warnings, edges);
                }
                PathWayData path = new PathWayData();
                List<Node> next =
                        nodes.getNextNodeList(
                                current, null, SkipType.PASS.getKey(), variables, path, combine);
                if (next == null || next.size() != 1) {
                    throw new ServiceException("路线条件未匹配唯一下一节点，请检查流程配置");
                }
                for (Node passed : path.getPathWayNodes()) {
                    if (NodeType.SERIAL.getKey().equals(passed.getNodeType())) {
                        checkExclusive(passed, combine, variables);
                        if (visited.add(passed.getNodeCode())) {
                            steps.add(step(passed, applyCode, initiator, variables));
                        }
                    }
                }
                for (Skip skip : path.getPathWaySkips()) {
                    edges.add(
                            new Edge(
                                    skip.getNowNodeCode(),
                                    skip.getNextNodeCode(),
                                    skip.getSkipCondition()));
                }
                current = next.getFirst();
            }
            throw new ServiceException("审批路线存在循环，预览已终止");
        } catch (RuntimeException exception) {
            String detail =
                    exception instanceof ServiceException || exception instanceof FlowException
                            ? Objects.toString(exception.getMessage(), "请检查流程定义")
                            : "请检查节点、连线、条件值和候选人配置";
            warnings.add("预览未完成（条件未匹配或配置不完整）：" + detail);
            return new Result(false, steps, warnings, edges);
        }
    }

    private Map<String, Object> variables(Variables input, String code) {
        if (input == null
                || !Set.of("DIRECT", "TENDER")
                        .contains(Objects.toString(input.acquisitionMode(), ""))) {
            throw new ServiceException("请选择直接委托或招投标获取方式");
        }
        Project snapshot = new Project();
        snapshot.setAcquisitionMode(input.acquisitionMode());
        snapshot.setProjectType(input.projectType());
        snapshot.setEstimatedAmount(input.estimatedAmount());
        snapshot.setBidType(input.bidType());
        snapshot.setBidAmount(input.bidAmount());
        snapshot.setProjectAmount(input.projectAmount());
        return ProjectWorkflow.conditionVariables(TYPES.get(code), snapshot);
    }

    private void checkDefinition(
            Definition definition, FlowCombine combine, Map<String, Object> variables) {
        if (combine == null
                || combine.getAllNodes() == null
                || combine.getAllSkips() == null
                || combine.getAllNodes().isEmpty()
                || combine.getAllNodes().size() > 200
                || combine.getAllSkips().size() > 500) {
            throw new ServiceException("流程节点缺失或超出预览范围");
        }
        if (hasText(definition.getListenerPath())) {
            throw new ServiceException("流程包含自定义监听器，预览不会执行监听器");
        }
        if (hasText(definition.getExt())) {
            var ext = JsonUtils.parseMap(definition.getExt());
            if (ext != null && Boolean.TRUE.equals(ext.getBool("autoPass"))) {
                throw new ServiceException("流程开启连续自动审批，当前预览暂不模拟自动办理");
            }
        }
        Map<String, Node> byCode = new HashMap<>();
        long starts = 0;
        long ends = 0;
        for (Node node : combine.getAllNodes()) {
            if (!hasText(node.getNodeCode()) || byCode.put(node.getNodeCode(), node) != null) {
                throw new ServiceException("节点编码缺失或重复");
            }
            if (!List.of(
                            NodeType.START.getKey(),
                            NodeType.BETWEEN.getKey(),
                            NodeType.END.getKey(),
                            NodeType.SERIAL.getKey())
                    .contains(node.getNodeType())) {
                throw new ServiceException("当前预览仅支持串行审批与互斥条件网关，暂不支持并行、包容或未知节点");
            }
            if (NodeType.START.getKey().equals(node.getNodeType())) starts++;
            if (NodeType.END.getKey().equals(node.getNodeType())) ends++;
            if (hasText(node.getListenerPath())) {
                throw new ServiceException("节点“" + node.getNodeName() + "”包含自定义监听器，预览不会执行监听器");
            }
            if (hasText(node.getExt())) {
                var ext = JsonUtils.parseArrayMap(node.getExt());
                if (ext != null
                        && ext.stream()
                                .anyMatch(
                                        e ->
                                                "VariablesEnum".equals(e.getStr("code"))
                                                        && hasText(e.getStr("value")))) {
                    throw new ServiceException(
                            "节点“" + node.getNodeName() + "”会通过扩展参数修改变量，当前预览不执行该监听器逻辑");
                }
            }
            for (String token : configured(node)) checkAssignee(token);
        }
        if (starts != 1 || ends != 1) throw new ServiceException("流程必须配置唯一开始和结束节点");
        for (Skip skip : combine.getAllSkips()) {
            if (!byCode.containsKey(skip.getNowNodeCode())
                    || !byCode.containsKey(skip.getNextNodeCode())) {
                throw new ServiceException("流程连线引用了不存在的节点");
            }
            if (hasText(skip.getSkipCondition())) {
                if (!NodeType.SERIAL
                        .getKey()
                        .equals(byCode.get(skip.getNowNodeCode()).getNodeType())) {
                    throw new ServiceException("普通节点连线上的条件不会被引擎判断，请放在条件分支上");
                }
                var matcher = CONDITION.matcher(skip.getSkipCondition());
                if (!matcher.matches()) {
                    throw new ServiceException("存在暂不支持的条件表达式；仅支持引擎内置比较条件，未执行脚本");
                }
                if (!variables.containsKey(matcher.group(1))
                        || variables.get(matcher.group(1)) == null) {
                    throw new ServiceException("条件变量缺失：" + matcher.group(1));
                }
            }
        }
        // Guard engine recursion before it follows consecutive gateways. Reject edges are excluded.
        Set<String> done = new HashSet<>();
        for (Node node : combine.getAllNodes())
            checkCycle(node.getNodeCode(), combine, new HashSet<>(), done);
    }

    private void checkCycle(
            String code, FlowCombine combine, Set<String> visiting, Set<String> done) {
        if (done.contains(code)) return;
        if (!visiting.add(code)) throw new ServiceException("流程连线存在循环，预览已终止");
        for (Skip skip : combine.getAllSkips()) {
            if (code.equals(skip.getNowNodeCode())
                    && !SkipType.REJECT.getKey().equals(skip.getSkipType())) {
                checkCycle(skip.getNextNodeCode(), combine, visiting, done);
            }
        }
        visiting.remove(code);
        done.add(code);
    }

    private String applicationNode(Node start, FlowCombine combine, Map<String, Object> variables) {
        List<Node> first =
                nodes.getNextNodeList(
                        start, null, SkipType.PASS.getKey(), variables, null, combine);
        if (first == null
                || first.size() != 1
                || !NodeType.BETWEEN.getKey().equals(first.getFirst().getNodeType())) {
            throw new ServiceException("开始后必须有唯一提交申请节点");
        }
        // The application listener discovers this node using empty variables, so a conditional
        // gateway before submission cannot be simulated consistently without executing a listener.
        long direct =
                combine.getAllSkips().stream()
                        .filter(
                                s ->
                                        start.getNodeCode().equals(s.getNowNodeCode())
                                                && first.getFirst()
                                                        .getNodeCode()
                                                        .equals(s.getNextNodeCode())
                                                && SkipType.PASS.getKey().equals(s.getSkipType()))
                        .count();
        if (direct != 1) throw new ServiceException("提交申请节点之前暂不支持条件网关");
        return first.getFirst().getNodeCode();
    }

    private void checkExclusive(Node gateway, FlowCombine combine, Map<String, Object> variables) {
        int matches = 0;
        int defaults = 0;
        for (Skip skip : combine.getAllSkips()) {
            if (!gateway.getNodeCode().equals(skip.getNowNodeCode())) continue;
            if (!hasText(skip.getSkipCondition())) defaults++;
            else if (ExpressionUtil.evalCondition(skip.getSkipCondition(), variables)) matches++;
        }
        if (matches > 1 || defaults > 1) {
            throw new ServiceException("互斥网关“" + gateway.getNodeName() + "”命中多个条件或配置了多个默认分支，请消除重叠");
        }
    }

    private Step step(
            Node node, String applyCode, UserDTO initiator, Map<String, Object> variables) {
        List<String> configured = configured(node);
        if (!NodeType.BETWEEN.getKey().equals(node.getNodeType())) {
            return new Step(
                    node.getNodeCode(),
                    node.getNodeName(),
                    node.getNodeType(),
                    List.of(),
                    configured,
                    "");
        }
        Map<String, Assignee> actual = new LinkedHashMap<>();
        boolean application = node.getNodeCode().equals(applyCode);
        if (application) {
            actual.put(initiator.getUserId().toString(), assignee(initiator));
        } else {
            if (configured.isEmpty())
                throw new ServiceException("节点“" + node.getNodeName() + "”未配置审批人");
            for (String token : configured) {
                List<String> resolved;
                if (DEPT_LEADER.equals(token)) {
                    if (initiator.getDeptId() == null)
                        throw new ServiceException("发起人未配置部门，无法查询部门负责人");
                    resolved =
                            List.of(rules.selectDeptLeaderById(initiator.getDeptId()).toString());
                } else {
                    resolved = ExpressionUtil.evalVariable(token, variables);
                }
                List<UserDTO> candidates =
                        assignees.fetchUsersByStorageIds(String.join(",", resolved));
                if (candidates == null || candidates.isEmpty()) {
                    throw new ServiceException(
                            "节点“" + node.getNodeName() + "”的办理人配置无有效用户：" + token);
                }
                for (UserDTO user : candidates)
                    actual.put(user.getUserId().toString(), assignee(user));
            }
        }
        return new Step(
                node.getNodeCode(),
                node.getNodeName(),
                node.getNodeType(),
                new ArrayList<>(actual.values()),
                configured,
                application ? "提交申请，提交时自动完成，办理人为发起人" : "按当前条件、后续全部同意预估的审批候选范围；多位候选不表示逐人顺序审批");
    }

    private void checkAssignee(String token) {
        if (!STATIC_ASSIGNEE.matcher(token).matches()
                && !"${initiator}".equals(token)
                && !DEPT_LEADER.equals(token)) {
            throw new ServiceException("暂不支持办理人表达式：" + token + "；未执行该表达式");
        }
    }

    private static List<String> configured(Node node) {
        return org.dromara.warm.flow.core.utils.StringUtils.str2List(
                node.getPermissionFlag(), "@@");
    }

    private static Assignee assignee(UserDTO user) {
        return new Assignee(
                user.getUserId().toString(),
                hasText(user.getNickName()) ? user.getNickName() : user.getUserName());
    }

    private static boolean hasText(String text) {
        return text != null && !text.isBlank();
    }

    private static Long id(String value) {
        try {
            long id = Long.parseLong(value);
            if (id <= 0) throw new NumberFormatException();
            return id;
        } catch (RuntimeException exception) {
            throw new ServiceException("流程定义和发起人ID格式不正确");
        }
    }
}

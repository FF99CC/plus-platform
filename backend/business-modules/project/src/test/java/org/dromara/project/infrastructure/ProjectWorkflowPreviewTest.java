package org.dromara.project.infrastructure;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.math.BigDecimal;
import java.util.*;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.system.api.DeptService;
import org.dromara.system.api.UserService;
import org.dromara.system.api.domain.UserDTO;
import org.dromara.warm.flow.core.FlowEngine;
import org.dromara.warm.flow.core.dto.FlowCombine;
import org.dromara.warm.flow.core.entity.Node;
import org.dromara.warm.flow.core.entity.Skip;
import org.dromara.warm.flow.core.enums.NodeType;
import org.dromara.warm.flow.core.service.DefService;
import org.dromara.warm.flow.core.service.impl.NodeServiceImpl;
import org.dromara.warm.flow.orm.entity.FlowDefinition;
import org.dromara.warm.flow.orm.entity.FlowNode;
import org.dromara.warm.flow.orm.entity.FlowSkip;
import org.dromara.workflow.rule.SpelRuleComponent;
import org.dromara.workflow.service.IFlwTaskAssigneeService;
import org.junit.jupiter.api.*;

@Tag("dev")
class ProjectWorkflowPreviewTest {
    DefService definitions;
    UserService users;
    DeptService departments;
    IFlwTaskAssigneeService assignees;
    ProjectWorkflowPreview preview;
    FlowDefinition definition;
    List<Node> nodes;
    List<Skip> skips;

    @BeforeEach
    void setUp() {
        FlowEngine.setNewDef(FlowDefinition::new);
        definitions = mock(DefService.class);
        users = mock(UserService.class);
        departments = mock(DeptService.class);
        assignees = mock(IFlwTaskAssigneeService.class);
        preview =
                new ProjectWorkflowPreview(
                        definitions,
                        new NodeServiceImpl(),
                        users,
                        assignees,
                        new SpelRuleComponent(departments));
        definition = new FlowDefinition().setId(10L).setFlowCode("pm_registration");
        nodes =
                new ArrayList<>(
                        List.of(
                                node("start", NodeType.START, null),
                                node("apply", NodeType.BETWEEN, "${initiator}"),
                                node("review", NodeType.BETWEEN, "role:7"),
                                node("end", NodeType.END, null)));
        skips =
                new ArrayList<>(
                        List.of(
                                edge("start", "apply", null),
                                edge("apply", "review", null),
                                edge("review", "end", null)));
        when(definitions.getById(10L)).thenReturn(definition);
        when(definitions.getFlowCombineNoDef(10L))
                .thenAnswer(i -> new FlowCombine(definition, nodes, skips));
        when(users.selectById(1L)).thenReturn(user(1, 99, "发起人"));
        when(assignees.fetchUsersByStorageIds("1")).thenReturn(List.of(user(1, 99, "发起人")));
        when(assignees.fetchUsersByStorageIds("role:7")).thenReturn(List.of(user(2, 99, "审核人")));
    }

    @Test
    void savedDraftResolvesRealUsersWithoutCreatingAnInstance() {
        var result = preview.preview(request("TENDER", null));
        assertTrue(result.complete(), result.warnings().toString());
        assertEquals(
                List.of("start", "apply", "review", "end"),
                result.steps().stream().map(ProjectWorkflowPreview.Step::nodeCode).toList());
        assertEquals("2", result.steps().get(2).assignees().getFirst().userId());
        assertTrue(result.steps().get(1).description().contains("提交"));
        assertEquals(3, result.matchedEdges().size());
        verify(definitions, never()).save(any());
        verify(definitions, never()).updateById(any());
    }

    @Test
    void engineChoosesTenderBranchAndAmountDefaultsMatchRealSubmission() {
        nodes.add(node("gate", NodeType.SERIAL, null));
        nodes.add(node("direct", NodeType.BETWEEN, "role:7"));
        skips.removeIf(s -> "apply".equals(s.getNowNodeCode()));
        skips.addAll(
                List.of(
                        edge("apply", "gate", null),
                        edge("gate", "review", "eq@@acquisitionMode|TENDER"),
                        edge("gate", "direct", "eq@@acquisitionMode|DIRECT"),
                        edge("direct", "end", null)));
        var result = preview.preview(request("TENDER", null));
        assertTrue(result.complete(), result.warnings().toString());
        assertTrue(result.steps().stream().anyMatch(s -> "review".equals(s.nodeCode())));
        assertFalse(result.steps().stream().anyMatch(s -> "direct".equals(s.nodeCode())));
        skips.removeIf(s -> "gate".equals(s.getNowNodeCode()));
        skips.addAll(
                List.of(
                        edge("gate", "review", "eq@@estimatedAmount|0"),
                        edge("gate", "direct", "gt@@estimatedAmount|0")));
        assertTrue(preview.preview(request("DIRECT", null)).complete());
        assertEquals("review", preview.preview(request("DIRECT", null)).steps().get(3).nodeCode());
    }

    @Test
    void overlappingExclusiveConditionsStopInsteadOfPretendingOnlyOneWasMatched() {
        exclusive("ge@@estimatedAmount|100", "le@@estimatedAmount|200");
        var result = preview.preview(request("TENDER", new BigDecimal("150")));
        assertFalse(result.complete());
        assertTrue(result.warnings().stream().anyMatch(w -> w.contains("多个") && w.contains("条件")));
    }

    @Test
    void noMatchingConditionIsReported() {
        exclusive("gt@@estimatedAmount|100", "lt@@estimatedAmount|0");
        var result = preview.preview(request("TENDER", BigDecimal.TEN));
        assertFalse(result.complete());
        assertTrue(result.warnings().stream().anyMatch(w -> w.contains("匹配")));
    }

    @Test
    void arbitraryConditionAndHandlerScriptsAreNotExecuted() {
        exclusive("spel@@#{@dangerous.deleteAll()}", null);
        assertFalse(preview.preview(request("DIRECT", null)).complete());
        skips.removeIf(s -> "gate".equals(s.getNowNodeCode()));
        skips.add(edge("gate", "review", null));
        nodes.get(2).setPermissionFlag("#{@dangerous.deleteAll()}");
        var result = preview.preview(request("DIRECT", null));
        assertFalse(result.complete());
        assertTrue(result.warnings().stream().anyMatch(w -> w.contains("表达式")));
        verifyNoInteractions(departments);
    }

    @Test
    void departmentLeaderUsesTheServerSideInitiatorsDepartment() {
        nodes.get(2)
                .setPermissionFlag("#{@spelRuleComponent.selectDeptLeaderById(#initiatorDeptId)}");
        when(departments.selectDeptLeaderById(99L)).thenReturn(3L);
        when(assignees.fetchUsersByStorageIds("3")).thenReturn(List.of(user(3, 99, "部门负责人")));
        var result = preview.preview(request("DIRECT", null));
        assertTrue(result.complete(), result.warnings().toString());
        assertEquals("3", result.steps().get(2).assignees().getFirst().userId());
        verify(departments).selectDeptLeaderById(99L);
    }

    @Test
    void missingDepartmentLeaderAndEmptyRoleAreConfigurationWarnings() {
        when(departments.selectDeptLeaderById(99L)).thenReturn(null);
        nodes.get(2)
                .setPermissionFlag("#{@spelRuleComponent.selectDeptLeaderById(#initiatorDeptId)}");
        var result = preview.preview(request("DIRECT", null));
        assertFalse(result.complete());
        assertTrue(result.warnings().stream().anyMatch(w -> w.contains("负责人")));
        nodes.get(2).setPermissionFlag("role:8");
        assertFalse(preview.preview(request("DIRECT", null)).complete());
        nodes.get(2).setPermissionFlag(null);
        assertFalse(preview.preview(request("DIRECT", null)).complete());
    }

    @Test
    void cyclesAndCustomListenersTerminatePreviewSafely() {
        skips.set(2, edge("review", "apply", null));
        var result = preview.preview(request("DIRECT", null));
        assertFalse(result.complete());
        assertTrue(result.warnings().stream().anyMatch(w -> w.contains("循环")));
        skips.set(2, edge("review", "end", null));
        definition.setListenerPath("dangerousListener");
        result = preview.preview(request("DIRECT", null));
        assertFalse(result.complete());
        assertTrue(result.warnings().stream().anyMatch(w -> w.contains("监听器")));
    }

    @Test
    void onlyProjectDefinitionsAndActiveInitiatorsAreAllowed() {
        definition.setFlowCode("leave1");
        assertThrows(ServiceException.class, () -> preview.preview(request("DIRECT", null)));
        definition.setFlowCode("pm_bid");
        when(users.selectById(1L)).thenReturn(null);
        assertThrows(ServiceException.class, () -> preview.preview(request("DIRECT", null)));
    }

    @Test
    void endingImmediatelyAfterSubmissionIsNotAValidProjectApprovalRoute() {
        skips.removeIf(s -> "apply".equals(s.getNowNodeCode()));
        skips.add(edge("apply", "end", null));
        var result = preview.preview(request("DIRECT", null));
        assertFalse(result.complete());
        assertTrue(result.warnings().stream().anyMatch(w -> w.contains("审批节点")));
    }

    @Test
    void conditionsOnOrdinaryEdgesMustNotAppearToFilterAnApproval() {
        skips.set(1, edge("apply", "review", "eq@@acquisitionMode|TENDER"));
        var result = preview.preview(request("DIRECT", null));
        assertFalse(result.complete());
        assertTrue(
                result.warnings().stream().anyMatch(w -> w.contains("条件分支") && w.contains("引擎")));
    }

    private ProjectWorkflowPreview.Request request(String mode, BigDecimal amount) {
        return new ProjectWorkflowPreview.Request(
                "10",
                "1",
                new ProjectWorkflowPreview.Variables(mode, null, amount, null, null, null));
    }

    private void exclusive(String first, String second) {
        nodes.add(node("gate", NodeType.SERIAL, null));
        nodes.add(node("other", NodeType.BETWEEN, "role:7"));
        skips.removeIf(s -> "apply".equals(s.getNowNodeCode()));
        skips.addAll(
                List.of(
                        edge("apply", "gate", null),
                        edge("gate", "review", first),
                        edge("gate", "other", second),
                        edge("other", "end", null)));
    }

    private static FlowNode node(String code, NodeType type, String permission) {
        return new FlowNode()
                .setDefinitionId(10L)
                .setNodeCode(code)
                .setNodeName(code)
                .setNodeType(type.getKey())
                .setPermissionFlag(permission);
    }

    private static FlowSkip edge(String from, String to, String condition) {
        return new FlowSkip()
                .setDefinitionId(10L)
                .setNowNodeCode(from)
                .setNextNodeCode(to)
                .setSkipType("PASS")
                .setSkipCondition(condition);
    }

    private static UserDTO user(long id, long department, String name) {
        var user = new UserDTO();
        user.setUserId(id);
        user.setDeptId(department);
        user.setUserName(name);
        user.setStatus("0");
        return user;
    }
}

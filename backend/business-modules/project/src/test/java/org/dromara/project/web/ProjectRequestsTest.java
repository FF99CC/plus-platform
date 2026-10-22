package org.dromara.project.web;

import static org.junit.jupiter.api.Assertions.*;

import jakarta.validation.Validation;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

@Tag("dev")
class ProjectRequestsTest {
    @Test
    void approvalRejectsClientControlledWorkflowFieldsEvenWithGlobalUnknownFieldsAllowed() {
        var mapper =
                JsonMapper.builder()
                        .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                        .build();
        for (String field : new String[] {"taskId", "flowCode", "variables", "ignore", "handler"}) {
            String payload = "{\"decision\":\"APPROVE\",\"" + field + "\":\"injected\"}";
            assertThrows(
                    RuntimeException.class,
                    () -> mapper.readValue(payload, ProjectRequests.Decision.class),
                    field);
        }
    }

    @Test
    void amountsAndRequiredFieldsAreValidatedBeforeApplicationMutation() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var validator = factory.getValidator();
            var invalid =
                    new ProjectRequests.Basic(
                            " ",
                            "客户",
                            null,
                            null,
                            "地点",
                            null,
                            "UNKNOWN",
                            new java.math.BigDecimal("-1.001"));
            assertFalse(validator.validate(invalid).isEmpty());
            var valid =
                    new ProjectRequests.Basic(
                            "项目",
                            "客户",
                            null,
                            null,
                            "地点",
                            null,
                            "DIRECT",
                            new java.math.BigDecimal("0.00"));
            assertTrue(validator.validate(valid).isEmpty());
        }
    }
}

package org.dromara.project.web;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Explicit allow-list of client-writable fields; identity, workflow and state never come from a
 * request.
 */
public final class ProjectRequests {
    private ProjectRequests() {}

    public record Basic(
            @NotBlank @Size(max = 200) String name,
            @NotBlank @Size(max = 200) String customerName,
            @Size(max = 100) String contactName,
            @Size(max = 50) String contactPhone,
            @NotBlank @Size(max = 500) String location,
            @Size(max = 100) String projectType,
            @NotNull @Pattern(regexp = "DIRECT|TENDER") String acquisitionMode,
            @DecimalMin("0.00") @Digits(integer = 16, fraction = 2) BigDecimal estimatedAmount) {}

    public record FollowUp(@NotBlank @Size(max = 2000) String message) {}

    public record Termination(@NotBlank @Size(max = 2000) String reason) {}

    public record Bid(
            @NotBlank @Size(max = 200) String bidName,
            @NotBlank @Size(max = 100) String bidType,
            @NotNull LocalDate bidDeadline) {}

    public record TaskCreate(
            @NotBlank @Size(max = 200) String title,
            @NotNull @Pattern(regexp = "BID_DOCUMENT|CERTIFICATE|SEAL|GUARANTEE|OTHER") String kind,
            @Size(max = 100) String assigneeName,
            LocalDate dueDate,
            @Size(max = 2000) String note) {}

    public record TaskUpdate(
            @NotNull @Pattern(regexp = "TODO|DONE") String status, @Size(max = 2000) String note) {}

    public record Opening(
            @NotNull @Pattern(regexp = "WON|LOST") String bidResult,
            @NotNull LocalDate openingDate,
            @DecimalMin("0.00") @Digits(integer = 16, fraction = 2) BigDecimal bidAmount,
            @Size(max = 2000) String bidRemark) {}

    public record Initiation(
            @NotBlank @Size(max = 100) String managerName,
            @NotNull
                    @DecimalMin(value = "0.00", inclusive = false)
                    @Digits(integer = 16, fraction = 2)
                    BigDecimal projectAmount,
            @NotNull LocalDate plannedStart,
            @NotNull LocalDate plannedEnd) {}

    public record Decision(
            @NotNull @Pattern(regexp = "APPROVE|RETURN") String decision,
            @Size(max = 2000) String comment) {
        @JsonAnySetter
        public void rejectUnknownField(String field, Object value) {
            throw new IllegalArgumentException("审批请求不接受字段：" + field);
        }
    }
}

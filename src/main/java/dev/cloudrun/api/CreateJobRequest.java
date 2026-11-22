package dev.cloudrun.api;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** timeoutSeconds is optional; JobService defaults it to 120 (plan §6). */
public record CreateJobRequest(
        @NotBlank @Size(max = 100) String name,
        @NotBlank String runtime,
        @NotBlank String script,
        @Min(1) @Max(900) Integer timeoutSeconds
) {
}

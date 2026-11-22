package dev.cloudrun.api;

import java.util.UUID;

public record CreateJobResponse(UUID id, String status) {
}

package dev.cloudrun.api;

import dev.cloudrun.job.Job;
import dev.cloudrun.job.JobService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/jobs")
public class JobController {

    private final JobService service;

    public JobController(JobService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<CreateJobResponse> create(@Valid @RequestBody CreateJobRequest request) {
        var result = service.create(request.name(), request.runtime(), request.script(), request.timeoutSeconds());
        var location = "/jobs/" + result.id();
        return ResponseEntity.accepted()
                .header("Location", location)
                .body(new CreateJobResponse(result.id(), result.status().name()));
    }

    @GetMapping("/{id}")
    public JobView get(@PathVariable UUID id) {
        return JobView.from(service.get(id));
    }

    @GetMapping("/{id}/result")
    public JobResultView result(@PathVariable UUID id) {
        Job job = service.result(id);
        return JobResultView.from(job);
    }
}

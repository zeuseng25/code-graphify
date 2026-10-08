package com.graphify.impact;

import com.graphify.common.exception.InvalidRequestException;
import java.nio.charset.StandardCharsets;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/impact")
public class ImpactController {

    private static final MediaType TEXT_CSV = new MediaType("text", "csv", StandardCharsets.UTF_8);

    private final ImpactService impact;

    public ImpactController(ImpactService impact) {
        this.impact = impact;
    }

    @PostMapping
    public ImpactResult analyze(@RequestBody ImpactRequest request) {
        return impact.analyze(request);
    }

    @PostMapping("/export")
    public ResponseEntity<String> export(@RequestParam String format, @RequestBody ImpactRequest request) {
        if (!"csv".equalsIgnoreCase(format)) {
            throw new InvalidRequestException("Unsupported export format: " + format + " (supported: csv)");
        }
        return ResponseEntity.ok()
                .contentType(TEXT_CSV)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"impact.csv\"")
                .body(ImpactCsv.write(impact.analyze(request)));
    }
}

package com.graphify.repograph;

import com.graphify.common.exception.InvalidRequestException;
import java.util.Arrays;
import java.util.Locale;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** The repository graph (spec §10.5): view, report and export. */
@RestController
@RequestMapping("/api/v1/repositories/{id}/graph")
public class RepoGraphController {

    private final RepoGraphService graphs;

    public RepoGraphController(RepoGraphService graphs) {
        this.graphs = graphs;
    }

    @GetMapping
    public RepoGraph graph(@PathVariable long id, @RequestParam(required = false) String level,
            @RequestParam(required = false) String focus, @RequestParam(defaultValue = "false") boolean includeExternal) {
        return graphs.graph(id, level(level), focus, includeExternal);
    }

    /** PACKAGE when absent (spec §9.1); case-insensitive; anything else is a 400. */
    static GraphLevel level(String level) {
        if (level == null || level.isBlank()) {
            return GraphLevel.PACKAGE;
        }
        try {
            return GraphLevel.valueOf(level.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new InvalidRequestException("level must be one of " + Arrays.toString(GraphLevel.values()));
        }
    }

    /** Export formats and their file extensions. */
    private enum ExportFormat {
        GRAPHML, JSON
    }

    @GetMapping("/report")
    public RepoGraphReport report(@PathVariable long id) {
        return graphs.report(id);
    }

    @GetMapping("/export")
    public ResponseEntity<?> export(@PathVariable long id, @RequestParam(required = false) String format,
            @RequestParam(required = false) String level, @RequestParam(required = false) String focus,
            @RequestParam(defaultValue = "false") boolean includeExternal) {
        ExportFormat exportFormat = format(format);
        RepoGraph graph = graphs.exportGraph(id, level(level), focus, includeExternal);
        String extension = exportFormat.name().toLowerCase(Locale.ROOT);
        String filename = "repository-" + id + "-" + graph.level().name().toLowerCase(Locale.ROOT) + "." + extension;
        ResponseEntity.BodyBuilder response = ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION,
                ContentDisposition.attachment().filename(filename).build().toString());
        return exportFormat == ExportFormat.GRAPHML
                ? response.contentType(MediaType.APPLICATION_XML).body(GraphMl.write(graph))
                : response.contentType(MediaType.APPLICATION_JSON).body(graph);
    }

    private static ExportFormat format(String format) {
        if (format != null) {
            for (ExportFormat candidate : ExportFormat.values()) {
                if (candidate.name().equalsIgnoreCase(format.strip())) {
                    return candidate;
                }
            }
        }
        throw new InvalidRequestException("format must be graphml or json");
    }
}

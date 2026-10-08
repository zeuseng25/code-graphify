package com.graphify.web;

import com.graphify.common.exception.NotFoundException;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.HtmlUtils;

/** Serves the UI's index.html with its base href set to this deployment's context path (web UI spec §3.4). */
@RestController
public class SpaController {

    /** Where the frontend build puts the entry document inside the WAR. */
    private static final String INDEX_RESOURCE = "static/index.html";

    /** The build writes a placeholder base element; the first one is replaced. */
    private static final Pattern BASE = Pattern.compile("<base href=\"[^\"]*\"\\s*/?>");

    private volatile String template;

    @GetMapping(SpaForwardFilter.INDEX)
    public ResponseEntity<String> index(HttpServletRequest request) {
        String html = template();
        Matcher base = BASE.matcher(html);
        if (!base.find()) {
            throw new IllegalStateException(INDEX_RESOURCE + " has no <base href> element");
        }
        String href = HtmlUtils.htmlEscape(request.getContextPath() + "/");
        String body = html.substring(0, base.start()) + "<base href=\"" + href + "\" />" + html.substring(base.end());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .contentType(new MediaType(MediaType.TEXT_HTML, StandardCharsets.UTF_8)).body(body);
    }

    private String template() {
        String loaded = template;
        if (loaded == null) {
            ClassPathResource resource = new ClassPathResource(INDEX_RESOURCE);
            if (!resource.exists()) {
                throw new NotFoundException("The web UI is not part of this build");
            }
            try (InputStream in = resource.getInputStream()) {
                loaded = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new IllegalStateException("Cannot read " + INDEX_RESOURCE, e);
            }
            template = loaded;
        }
        return loaded;
    }
}

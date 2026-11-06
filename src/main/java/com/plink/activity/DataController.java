package com.plink.activity;

import com.plink.account.CurrentUser;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * The data console: the numbers, the log, saved filters and exports. Owners only - the
 * security chain checks the scope before anything here runs.
 */
@RestController
@RequestMapping("/api/admin/data")
public class DataController {
    private final DataQueryService data;
    private final ExportDatasets datasets;
    private final ExportService exports;

    public DataController(DataQueryService data, ExportDatasets datasets, ExportService exports) {
        this.data = data;
        this.datasets = datasets;
        this.exports = exports;
    }

    @GetMapping("/overview")
    public Map<String, Object> overview(@RequestParam(required = false) Long sessionId,
            @RequestParam(required = false) String from, @RequestParam(required = false) String to) {
        return data.overview(DataQueryService.Range.of(sessionId, from, to));
    }

    @GetMapping("/categories")
    public List<Map<String, Object>> categories(@RequestParam(required = false) Long sessionId,
            @RequestParam(required = false) String from, @RequestParam(required = false) String to) {
        return data.categories(DataQueryService.Range.of(sessionId, from, to));
    }

    @GetMapping("/logs")
    public Map<String, Object> logs(@RequestParam(required = false) Long sessionId,
            @RequestParam(required = false) String from, @RequestParam(required = false) String to,
            @RequestParam(required = false) String categories, @RequestParam(defaultValue = "ALL") String result,
            @RequestParam(required = false) String q, @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        List<String> chosen = categories == null || categories.isBlank() ? List.of()
            : Arrays.stream(categories.split(",")).map(String::trim).filter(c -> !c.isEmpty())
                .filter(ActivityLabels.CATEGORIES::containsKey).toList();
        return data.logs(DataQueryService.Range.of(sessionId, from, to), chosen, result, q, page, size);
    }

    @GetMapping("/views")
    public List<Map<String, Object>> views() {
        return data.views();
    }

    @PostMapping("/views")
    public Map<String, Object> saveView(@RequestBody Map<String, Object> body, Authentication user) {
        return data.saveView(body.get("name") == null ? null : String.valueOf(body.get("name")), body.get("filters"),
            CurrentUser.of(user).map(u -> u.email).orElse(null));
    }

    @DeleteMapping("/views/{id}")
    public ResponseEntity<Void> deleteView(@PathVariable long id) {
        data.deleteView(id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/datasets")
    public List<Map<String, Object>> datasets(@RequestParam(required = false) Long sessionId) {
        return datasets.catalogue(sessionId);
    }

    @GetMapping("/exports")
    public List<Map<String, Object>> exports() {
        return exports.list();
    }

    @PostMapping("/exports")
    public Map<String, Object> createExport(@RequestBody Map<String, Object> body, Authentication user) {
        return exports.create(body, CurrentUser.of(user).map(u -> u.email).orElse(null));
    }

    @PostMapping("/exports/{id}/send")
    public Map<String, Object> send(@PathVariable long id) {
        return exports.send(id);
    }

    @PostMapping("/exports/{id}/close")
    public ResponseEntity<Void> close(@PathVariable long id) {
        exports.close(id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/exports/{id}/access")
    public List<Map<String, Object>> access(@PathVariable long id) {
        return exports.access(id);
    }
}

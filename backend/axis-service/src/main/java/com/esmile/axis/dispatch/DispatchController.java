package com.esmile.axis.dispatch;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/issues")
@RequiredArgsConstructor
public class DispatchController {

    private final DispatchService dispatchService;

    @PostMapping("/{id}/dispatch")
    public ResponseEntity<Void> dispatch(@PathVariable String id, @RequestBody(required = false) DispatchRequest req) {
        dispatchService.dispatch(id, req != null ? req.prompt() : null, req != null ? req.terminal() : null);
        return ResponseEntity.ok().build();
    }

    public record DispatchRequest(String prompt, String terminal) {
    }
}

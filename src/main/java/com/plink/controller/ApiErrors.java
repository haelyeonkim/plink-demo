package com.plink.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;

@RestControllerAdvice
public class ApiErrors {
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String,String>> handle(ResponseStatusException error) {
        return ResponseEntity.status(error.getStatus()).body(Collections.singletonMap("error",
            error.getReason() == null ? "요청을 처리할 수 없어요." : error.getReason()));
    }
}

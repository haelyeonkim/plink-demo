package com.plink.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import java.util.*;

@RestControllerAdvice
public class ApiErrors {
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<Map<String,String>> uploadTooLarge(MaxUploadSizeExceededException error) {
        return ResponseEntity.status(413).body(Collections.singletonMap("error",
            "파일 업로드 용량을 초과했어요. PDF는 100MB 이하로 줄여서 다시 올려 주세요."));
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String,String>> handle(ResponseStatusException error) {
        return ResponseEntity.status(error.getStatusCode()).body(Collections.singletonMap("error",
            error.getReason() == null ? "요청을 처리할 수 없어요." : error.getReason()));
    }
}

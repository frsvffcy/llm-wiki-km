package com.frsvffcy.llmwiki.web;

import java.time.Instant;

public record ApiError(ErrorPayload error) {

    public static ApiError of(String code, String message) {
        return new ApiError(new ErrorPayload(code, message, Instant.now().toString()));
    }

    public record ErrorPayload(String code, String message, String timestamp) {
    }
}

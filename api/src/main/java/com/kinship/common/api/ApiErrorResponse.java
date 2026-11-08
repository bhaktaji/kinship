package com.kinship.common.api;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiErrorResponse(ApiError error) {

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ApiError(String code, String message, Object details, String traceId) {
    }

    public static ApiErrorResponse of(String code, String message, Object details, String traceId) {
        return new ApiErrorResponse(new ApiError(code, message, details, traceId));
    }
}

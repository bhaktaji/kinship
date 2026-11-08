package com.kinship.image;

import com.kinship.common.api.ApiException;
import com.kinship.common.api.ApiException;
import com.kinship.common.security.UserPrincipal;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.UUID;

@RestController
@RequestMapping("/v1/images")
public class ImageController {
    private final ImageService service;

    public ImageController(ImageService service) {
        this.service = service;
    }

    @PostMapping
    public java.util.Map<String, UUID> upload(@AuthenticationPrincipal UserPrincipal principal,
                                              @RequestParam String type,
                                              @RequestParam(required = false) UUID groupId,
                                              @RequestParam(required = false) UUID reviewId,
                                              @RequestParam("file") MultipartFile file) {
        UUID id = service.upload(require(principal).id(), type, groupId, reviewId, file);
        return java.util.Map.of("image_id", id);
    }

    @GetMapping("/{id}")
    public ResponseEntity<byte[]> get(@AuthenticationPrincipal UserPrincipal principal, @PathVariable UUID id) {
        require(principal);
        ImageRepository.ImageRecord meta = service.metadata(id);
        byte[] bytes = service.readBytes(id);
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "private, max-age=300")
                .contentType(MediaType.parseMediaType(meta.contentType()))
                .body(bytes);
    }

    @DeleteMapping("/{id}")
    public void delete(@AuthenticationPrincipal UserPrincipal principal, @PathVariable UUID id) {
        service.delete(require(principal).id(), id);
    }

    private UserPrincipal require(UserPrincipal principal) {
        if (principal == null) throw ApiException.unauthorized("UNAUTHENTICATED", "Authentication required");
        return principal;
    }
}
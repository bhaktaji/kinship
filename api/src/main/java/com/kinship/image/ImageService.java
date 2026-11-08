package com.kinship.image;

import com.kinship.common.api.ApiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
public class ImageService {
    private static final long MAX_BYTES = 5L * 1024 * 1024;
    private static final Set<String> ALLOWED = Set.of("image/jpeg", "image/png", "image/webp", "image/gif");

    private final ImageRepository repo;
    private final Path root;

    public ImageService(ImageRepository repo, @Value("${app.upload-dir:${java.io.tmpdir}/kinship-uploads}") String uploadDir) {
        this.repo = repo;
        this.root = Paths.get(uploadDir).toAbsolutePath().normalize();
    }

    @Transactional
    public UUID upload(UUID userId, String imageType, UUID groupId, UUID reviewId, MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw ApiException.badRequest("VALIDATION_ERROR", "file is required");
        }
        if (file.getSize() > MAX_BYTES) {
            throw ApiException.badRequest("FILE_TOO_LARGE", "image must be <= 5MB");
        }
        String contentType = file.getContentType();
        if (contentType == null || !ALLOWED.contains(contentType.toLowerCase())) {
            throw ApiException.badRequest("INVALID_FILE_TYPE", "unsupported image type");
        }
        if (!List.of("PROFILE", "GROUP_BANNER", "REVIEW").contains(imageType)) {
            throw ApiException.badRequest("VALIDATION_ERROR", "invalid image type");
        }
        UUID id = UUID.randomUUID();
        String ext = extension(contentType);
        String storageKey = id + "." + ext;
        Path target = root.resolve(storageKey);
        try {
            Files.createDirectories(root);
            try (java.io.InputStream in = file.getInputStream()) {
                Files.copy(in, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Unable to store image", e);
        }
        return repo.insert(userId, imageType, groupId, reviewId, storageKey, contentType, file.getSize());
    }

    @Transactional(readOnly = true)
    public ImageRepository.ImageRecord metadata(UUID id) {
        return repo.findById(id).orElseThrow(() -> ApiException.notFound("NOT_FOUND", "Image not found"));
    }

    public byte[] readBytes(UUID id) {
        ImageRepository.ImageRecord meta = metadata(id);
        try {
            return Files.readAllBytes(root.resolve(meta.storageKey()));
        } catch (IOException e) {
            throw ApiException.notFound("NOT_FOUND", "Image bytes not found");
        }
    }

    @Transactional
    public void delete(UUID userId, UUID id) {
        ImageRepository.ImageRecord meta = repo.findById(id)
                .orElseThrow(() -> ApiException.notFound("NOT_FOUND", "Image not found"));
        if (!meta.ownerUserId().equals(userId)) {
            throw ApiException.forbidden("FORBIDDEN", "Not the image owner");
        }
        try {
            Files.deleteIfExists(root.resolve(meta.storageKey()));
        } catch (IOException ignored) {
        }
        repo.delete(id);
    }

    private String extension(String contentType) {
        return switch (contentType.toLowerCase()) {
            case "image/jpeg" -> "jpg";
            case "image/png" -> "png";
            case "image/webp" -> "webp";
            case "image/gif" -> "gif";
            default -> "bin";
        };
    }
}
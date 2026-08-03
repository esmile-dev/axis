package com.esmile.axis.controller;

import com.esmile.axis.service.FileUploadService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.net.MalformedURLException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;

@RestController
public class FileUploadController {

    private final FileUploadService fileUploadService;
    private final Path uploadDir;

    public FileUploadController(FileUploadService fileUploadService,
                                @Value("${app.upload.dir}") String uploadDir) {
        this.fileUploadService = fileUploadService;
        this.uploadDir = Paths.get(uploadDir).normalize();
    }

    @PostMapping("/api/upload")
    public Map<String, String> upload(@RequestParam("file") MultipartFile file) throws IOException {
        String url = fileUploadService.upload(file);
        return Map.of("url", url);
    }

    @GetMapping("/api/uploads/{filename}")
    public ResponseEntity<Resource> serveFile(@PathVariable String filename) throws MalformedURLException {
        // 防路径穿越：resolve 后必须仍落在 uploadDir 内
        Path filePath = uploadDir.resolve(filename).normalize();
        if (!filePath.startsWith(uploadDir)) {
            return ResponseEntity.badRequest().build();
        }
        Resource resource = new UrlResource(filePath.toUri());

        if (!resource.exists()) {
            return ResponseEntity.notFound().build();
        }

        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(resource);
    }
}

package com.example.demo.apigateway;


import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import java.io.*;
import java.nio.file.*;

@RestController
public class UploadController {
    private static final Path STORE = Paths.get("api-storage");
    static { try { Files.createDirectories(STORE); } catch(Exception e){ } }

    @PostMapping("/upload")
    public ResponseEntity<String> upload(@RequestParam("file") MultipartFile file) throws IOException {
        Path out = STORE.resolve(file.getOriginalFilename());
        try (InputStream in = file.getInputStream()) {
            Files.copy(in, out, StandardCopyOption.REPLACE_EXISTING);
        }
        return ResponseEntity.ok("stored:" + out.toAbsolutePath());
    }
}

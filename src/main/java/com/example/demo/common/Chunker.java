package com.example.demo.common;


import java.io.*;
import java.nio.file.*;
import java.util.*;

public class Chunker {
    public static List<Path> split(Path file, int chunkSizeBytes, Path outDir) throws IOException {
        Files.createDirectories(outDir);
        List<Path> parts = new ArrayList<>();
        try(InputStream in = Files.newInputStream(file)) {
            byte[] buf = new byte[chunkSizeBytes];
            int i=0, read;
            while((read = in.read(buf))>0) {
                Path p = outDir.resolve(file.getFileName().toString() + ".part" + (i++));
                try(OutputStream out = Files.newOutputStream(p)) { out.write(buf,0,read); }
                parts.add(p);
            }
        }
        return parts;
    }

    public static void merge(List<Path> parts, Path target) throws IOException {
        try(OutputStream out = Files.newOutputStream(target, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
            for(Path p : parts) {
                Files.copy(p, out);
            }
        }
    }
}

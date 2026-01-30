package com.example.demo.service;


import org.apache.commons.io.FileUtils;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

@Service
public class ModelManagerService {

    private final String SOUND_DIR = "sound";

    public void checkAndDownloadModels() throws IOException {
        File soundDir = new File(SOUND_DIR);
        if (!soundDir.exists()) {
            boolean soundDirCreated = soundDir.mkdirs();
            if (!soundDirCreated) {
                throw new IOException("Unable to create sound directory");
            }
        }
        // Ukrainian model
        String UK_MODEL_FOLDER = "vosk-model-uk-v3";
        String UK_MODEL_URL = "https://alphacephei.com/vosk/models/vosk-model-uk-v3.zip";
        downloadModel(UK_MODEL_FOLDER, UK_MODEL_URL);
        // English model
        String EN_MODEL_FOLDER = "vosk-model-en-us-0.22";
        String EN_MODEL_URL = "https://alphacephei.com/vosk/models/vosk-model-en-us-0.22.zip";
        downloadModel(EN_MODEL_FOLDER, EN_MODEL_URL);
    }

    private void downloadModel(String folderName, String modelAddress) throws IOException {
        File folder = new File(SOUND_DIR, folderName);
        if (!folder.exists()) {
            System.out.print("Downloading " + folderName + " model...%n");
            downloadAndUnzip(modelAddress);
            System.out.println(folderName + " model ready.");
        }
    }

    private void downloadAndUnzip(String url) throws IOException {
        // 1. Download zip to temp file
        // Using NIO.2 for temp file creation
        Path tempZip = Files.createTempFile("vosk_model", ".zip");

        try (InputStream in = URI.create(url).toURL().openStream()) {
            Files.copy(in, tempZip, StandardCopyOption.REPLACE_EXISTING);
        }

        // 2. Unzip
        try (ZipInputStream zis = new ZipInputStream(Files.newInputStream(tempZip))) {
            ZipEntry entry;
            Path outputPath = Paths.get(SOUND_DIR);

            while ((entry = zis.getNextEntry()) != null) {
                // Use resolve to handle paths safely
                Path entryPath = outputPath.resolve(entry.getName());

                // Security check: Prevent "Zip Slip" vulnerability
                if (!entryPath.normalize().startsWith(outputPath.normalize())) {
                    throw new IOException("Bad zip entry: " + entry.getName());
                }

                if (entry.isDirectory()) {
                    Files.createDirectories(entryPath);
                } else {
                    // Create parent directories if they don't exist
                    if (entryPath.getParent() != null) {
                        Files.createDirectories(entryPath.getParent());
                    }
                    Files.copy(zis, entryPath, StandardCopyOption.REPLACE_EXISTING);
                }
                zis.closeEntry();
            }
        } finally {
            // 3. Delete temp file safely
            Files.deleteIfExists(tempZip);
        }
    }
}


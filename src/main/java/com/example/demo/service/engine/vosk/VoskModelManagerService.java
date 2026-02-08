package com.example.demo.service.engine.vosk;


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
public class VoskModelManagerService {

    //here we can add new language models
    //"small" models may be too shallow for your tasks, but "full" models can be more than 1Gb
    private enum MODEL {
        UK("vosk-model-uk-v3"), EN("vosk-model-en-us-0.22"), RU("vosk-model-ru-0.42");
        private final String MODEL_FOLDER_NAME;

        MODEL(String modelFolderName) {
            this.MODEL_FOLDER_NAME = modelFolderName;
        }
    }

    private final String SOUND_DIR = "sound";

    public void checkAndDownloadModels() throws IOException {
        File soundDir = new File(SOUND_DIR);
        if (!soundDir.exists()) {
            boolean soundDirCreated = soundDir.mkdirs();
            if (!soundDirCreated) {
                throw new IOException("Unable to create sound directory");
            }
        }
        for (MODEL model : MODEL.values()) {
            downloadModel(model.MODEL_FOLDER_NAME);
        }
    }

    private void downloadModel(String folderName) throws IOException {
        File folder = new File(SOUND_DIR, folderName);
        if (!folder.exists()) {
            System.out.println("Downloading " + folderName + " model...");
            downloadAndUnzip("https://alphacephei.com/vosk/models/" + folderName + ".zip");
            System.out.println(folderName + " model ready.");
        }
    }

    private void downloadAndUnzip(String url) throws IOException {

        Path tempZip = Files.createTempFile("vosk_model", ".zip");
        try (InputStream in = URI.create(url).toURL().openStream()) {
            Files.copy(in, tempZip, StandardCopyOption.REPLACE_EXISTING);
        }

        try (ZipInputStream zis = new ZipInputStream(Files.newInputStream(tempZip))) {
            ZipEntry entry;
            Path outputPath = Paths.get(SOUND_DIR);

            while ((entry = zis.getNextEntry()) != null) {
                Path entryPath = outputPath.resolve(entry.getName());
                if (!entryPath.normalize().startsWith(outputPath.normalize())) {
                    throw new IOException("Bad zip entry: " + entry.getName());
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(entryPath);
                } else {
                    if (entryPath.getParent() != null) {
                        Files.createDirectories(entryPath.getParent());
                    }
                    Files.copy(zis, entryPath, StandardCopyOption.REPLACE_EXISTING);
                }
                zis.closeEntry();
            }
        } finally {
            Files.deleteIfExists(tempZip);
        }
    }
}


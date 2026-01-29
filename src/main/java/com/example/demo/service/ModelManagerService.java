package com.example.demo.service;


import org.apache.commons.io.FileUtils;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

@Service
public class ModelManagerService {

    public void checkAndDownloadModels() throws IOException {
        String SOUND_DIR = "sound";
        File soundDir = new File(SOUND_DIR);
        if (!soundDir.exists()) {
            boolean soundDirCreated = soundDir.mkdirs();
            if (!soundDirCreated) {
                throw new IOException("Unable to create sound directory");
            }
        }

        // Ukrainian model
        String UK_MODEL_FOLDER = "vosk-model-uk-v3";
        File ukFolder = new File(SOUND_DIR, UK_MODEL_FOLDER);
        if (!ukFolder.exists()) {
            System.out.println("Downloading Ukrainian model...");
            String UK_MODEL_URL = "https://alphacephei.com/vosk/models/vosk-model-uk-v3.zip";
            downloadAndUnzip(UK_MODEL_URL, SOUND_DIR);
            System.out.println("Ukrainian model ready.");
        }

        // English model
        String EN_MODEL_FOLDER = "vosk-model-en-us-0.22";
        File enFolder = new File(SOUND_DIR, EN_MODEL_FOLDER);
        if (!enFolder.exists()) {
            System.out.println("Downloading English model...");
            String EN_MODEL_URL = "https://alphacephei.com/vosk/models/vosk-model-en-us-0.22.zip";
            downloadAndUnzip(EN_MODEL_URL, SOUND_DIR);
            System.out.println("English model ready.");
        }
    }

    private void downloadAndUnzip(String url, String outputDir) throws IOException {
        // Download zip to temp file
        File tempZip = File.createTempFile("vosk_model", ".zip");
        FileUtils.copyURLToFile(new URL(url), tempZip);

        // Unzip
        try (ZipInputStream zis = new ZipInputStream(Files.newInputStream(tempZip.toPath()))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                File outFile = new File(outputDir, entry.getName());
                if (entry.isDirectory()) {
                    outFile.mkdirs();
                } else {
                    outFile.getParentFile().mkdirs();
                    Files.copy(zis, outFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
                }
                zis.closeEntry();
            }
        }

        tempZip.delete();
    }
}


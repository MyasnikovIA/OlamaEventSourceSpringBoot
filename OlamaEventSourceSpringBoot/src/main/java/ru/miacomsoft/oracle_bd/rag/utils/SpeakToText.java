package ru.miacomsoft.oracle_bd.rag.utils;

import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.function.Consumer;

public class SpeakToText {
    private StringBuilder currentSentence = null;
    private Path tempDir;

    public SpeakToText() {
        try {
            tempDir = Files.createTempDirectory("speech");
            tempDir.toFile().deleteOnExit();
        } catch (IOException e) {
            System.err.println("Ошибка создания временной директории: " + e.getMessage());
            tempDir = Path.of(System.getProperty("java.io.tmpdir"));
        }
    }

    public File speakToFile(String text, String prefix) {
        try {
            File file = File.createTempFile(prefix, ".wav", tempDir.toFile());
            file.deleteOnExit();
            runCommand("Govorilka_cp.exe", "-TO", file.getAbsolutePath(), "-E", "irina", text, "-s90");
            return file;
        } catch (Exception e) {
            System.err.println("Ошибка создания аудиофайла: " + e.getMessage());
            return null;
        }
    }

    public String speakToBase64(String text) {
        try {
            File tempFile = File.createTempFile("speech", ".wav", tempDir.toFile());
            tempFile.deleteOnExit();
            runCommand("Govorilka_cp.exe", "-TO", tempFile.getAbsolutePath(), "-E", "irina", text, "-s90");

            byte[] fileContent = Files.readAllBytes(tempFile.toPath());
            return Base64.getEncoder().encodeToString(fileContent);
        } catch (Exception e) {
            System.err.println("Ошибка создания base64 аудио: " + e.getMessage());
            return null;
        }
    }

    public void speakStreamFile(String text, String chatId, Consumer<String> audioConsumer) {
        if (currentSentence == null) {
            currentSentence = new StringBuilder();
        }
        currentSentence.append(text);

        if (text.matches(".*[.!?]$")) {
            String completeSentence = currentSentence.toString();
            File audioFile = speakToTempFile(completeSentence, chatId + "_");

            if (audioFile != null && audioFile.exists()) {
                try {
                    byte[] fileContent = Files.readAllBytes(audioFile.toPath());
                    String base64Audio = Base64.getEncoder().encodeToString(fileContent);

                    JSONObject audioEvent = new JSONObject();
                    audioEvent.put("type", "audio");
                    audioEvent.put("text", completeSentence);
                    audioEvent.put("data", base64Audio);
                    audioEvent.put("format", "wav");

                    audioConsumer.accept(audioEvent.toString());

                    // Удаляем файл после отправки
                    audioFile.delete();
                } catch (IOException e) {
                    System.err.println("Ошибка чтения аудиофайла: " + e.getMessage());
                }
            }

            currentSentence.setLength(0);
        }
    }

    private File speakToTempFile(String text, String prefix) {
        try {
            File file = File.createTempFile(prefix, ".wav", tempDir.toFile());
            file.deleteOnExit();
            runCommand("Govorilka_cp.exe", "-TO", file.getAbsolutePath(), "-E", "irina", text, "-s90");
            return file;
        } catch (Exception e) {
            System.err.println("Ошибка создания временного аудиофайла: " + e.getMessage());
            return null;
        }
    }

    private static void runCommand(String... command) throws IOException, InterruptedException {
        ProcessBuilder processBuilder = new ProcessBuilder(command);
        Process process = processBuilder.start();
        process.waitFor();
    }

    public static void speak(String text) {
        try {
            runCommand("Govorilka_cp.exe", "-E", "irina", text, "-s90");
        } catch (Exception e) {
            System.err.println("Ошибка озвучивания: " + e.getMessage());
        }
    }

    public static File speak(String text, String fileName) {
        File file = new File(fileName);
        try {
            runCommand("Govorilka_cp.exe", "-TO", file.getAbsolutePath(), "-E", "irina", text, "-s90");
        } catch (Exception e) {
            System.err.println("Ошибка озвучивания: " + e.getMessage());
        }
        return file;
    }

    public void speakStream(String text) {
        if (currentSentence == null) {
            currentSentence = new StringBuilder();
        }
        currentSentence.append(text);
        if (text.matches(".*[.!?]$")) {
            speak(currentSentence.toString());
            currentSentence.setLength(0);
        }
    }

    // УДАЛИТЕ этот интерфейс, так как используем стандартный java.util.function.Consumer
    // public interface Consumer<T> {
    //     void accept(T t);
    // }
}
package com.MindRune.service;

import com.MindRune.MindRuneConfig;
import com.MindRune.model.PlayerInfo;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.client.callback.ClientThread;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;

/**
 * Service for sending event data to the API
 */
@Slf4j
public class DataSenderService {
    private static final String API_URL = "http://localhost:5575/osrs/create";
    private static final int SEND_INTERVAL_MS = 60000; // 60 seconds

    private final Client client;
    private final MindRuneConfig config;
    private final ClientThread clientThread;
    private final EventLogService eventLogService;
    private final Gson gson = new Gson();
    private Timer timer;

    public DataSenderService(
            Client client,
            MindRuneConfig config,
            ClientThread clientThread,
            EventLogService eventLogService) {
        this.client = client;
        this.config = config;
        this.clientThread = clientThread;
        this.eventLogService = eventLogService;
    }

    /**
     * Start the periodic data sender
     */
    public void startDataSender() {
        // Cancel any existing timer
        stopDataSender();

        // Create and start a new timer
        timer = new Timer("MindRune-DataSender");
        timer.schedule(new TimerTask() {
            @Override
            public void run() {
                sendEventData();
            }
        }, 0, SEND_INTERVAL_MS);
    }

    /**
     * Stop the periodic data sender
     */
    public void stopDataSender() {
        if (timer != null) {
            timer.cancel();
            timer = null;
        }
    }

    /**
     * Send collected event data to the API
     */
    private void sendEventData() {
        if (!eventLogService.hasEvents()) {
            return;
        }

        HttpURLConnection conn = null;
        try {
            // Get player information
            PlayerInfo playerInfo = PlayerInfo.fromClient(client);
            if (playerInfo == null) {
                log.warn("No local player found, skipping data send.");
                return;
            }

            // Prepare payload
            List<JsonObject> finalPayload = new ArrayList<>();
            finalPayload.add(playerInfo.toJson());
            finalPayload.addAll(eventLogService.getAndClearEvents());

            // Convert to JSON
            String jsonPayload = gson.toJson(finalPayload);

            // Set up connection
            conn = (HttpURLConnection) new URL(API_URL).openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");

            // Add authorization if available
            String registrationKey = config.registrationKey();
            if (registrationKey != null && !registrationKey.isEmpty()) {
                conn.setRequestProperty("Authorization", "Bearer " + registrationKey);
            } else {
                log.warn("No registration key found. Request may be unauthorized.");
            }

            conn.setDoOutput(true);

            // Send data
            try (OutputStream os = conn.getOutputStream()) {
                byte[] input = jsonPayload.getBytes(StandardCharsets.UTF_8);
                os.write(input, 0, input.length);
            }

            // Process response
            int responseCode = conn.getResponseCode();
            if (responseCode == 200) {
                processSuccessResponse(conn.getInputStream());
            } else {
                processErrorResponse(conn.getErrorStream(), responseCode);
            }
        } catch (Exception e) {
            log.error("Error sending event data", e);
        }
    }

    /**
     * Process successful API response
     */
    private void processSuccessResponse(InputStream inputStream) {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {

            StringBuilder responseStr = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                responseStr.append(line);
            }

            // Parse response for points
            int points = 0;
            try {
                JsonObject responseJson = gson.fromJson(responseStr.toString(), JsonObject.class);
            } catch (Exception e) {
                log.warn("Could not parse points from response", e);
            }

            // Show notification if enabled
            if (config.enableChatNotifications()) {
                clientThread.invokeLater(() -> {
                    client.addChatMessage(
                            ChatMessageType.GAMEMESSAGE,
                            "",
                            "New MindRune data has been saved!",
                            null
                    );
                });
            }
        } catch (Exception e) {
            log.error("Error processing API response", e);
        }
    }

    /**
     * Process error API response
     */
    private void processErrorResponse(InputStream errorStream, int responseCode) {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(errorStream, StandardCharsets.UTF_8))) {

            StringBuilder errorResponse = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                errorResponse.append(line);
            }

            log.warn("Failed to send data, response code: {}, error: {}",
                    responseCode, errorResponse.toString());
        } catch (Exception e) {
            log.error("Failed to read error response", e);
        }
    }
}
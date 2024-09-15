package me.mcstatus.liveupdate;

import com.google.gson.*;

import java.io.*;
import java.time.Instant;

import org.apache.http.client.methods.*;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;

public class DiscordWebhook {
    
    private static final int RED = 16711680;
    private static final int GREEN = 7052103;

    public static void sendServerStatusToDiscord(String message_id, String thumbnail_url, String embed_description, String footer_text, boolean online, int onlinePlayers, int maxPlayers, String version, String webhookUrl) throws IOException {
        
        if(webhookUrl.equals("SET_YOUR_WEBHOOK_URL_HERE")) {
            return;
        }
        
        JsonObject embedObject = new JsonObject();
        embedObject.addProperty("title", "Server Status");
        embedObject.addProperty("description", embed_description);
        embedObject.addProperty("color", online ? GREEN : RED);
        embedObject.addProperty("timestamp", Instant.now().toString());
        
        JsonObject footer = new JsonObject();
        footer.addProperty("text", footer_text);
        embedObject.add("footer", footer);
        
        JsonObject thumbnail = new JsonObject();
        thumbnail.addProperty("url", thumbnail_url);
        thumbnail.addProperty("width", 64);
        thumbnail.addProperty("height", 64);
        embedObject.add("thumbnail", thumbnail);
        
        JsonArray fields = new JsonArray();
        JsonObject statusField = new JsonObject();
        statusField.addProperty("name", "Status");
        statusField.addProperty("value", online ? "Online" : "Offline");
        statusField.addProperty("inline", true);
        fields.add(statusField);
        
        JsonObject playerCountField = new JsonObject();
        playerCountField.addProperty("name", "Player Count");
        playerCountField.addProperty("value", onlinePlayers + "/" + maxPlayers);
        playerCountField.addProperty("inline", true);
        fields.add(playerCountField);
        
        JsonObject versionField = new JsonObject();
        versionField.addProperty("name", "Version");
        versionField.addProperty("value", version);
        versionField.addProperty("inline", true);
        fields.add(versionField);
        
        embedObject.add("fields", fields);
        
        JsonObject rootObject = new JsonObject();
        JsonArray embedsArray = new JsonArray();
        embedsArray.add(embedObject);
        rootObject.add("embeds", embedsArray);
        
        rootObject.addProperty("content", ""); //intentionally empty
        
        Gson gson = new GsonBuilder().setPrettyPrinting().create();
        String jsonPayload = gson.toJson(rootObject);
        
        sendWebhook(webhookUrl + "/messages/" + message_id, jsonPayload, "PATCH");
    }
    
    public static String initWebhook(String webhookURL) throws IOException {
        JsonObject content = new JsonObject();
        
        content.addProperty("content", "Setting up MCStatus Live Update, please wait several seconds...");
        
        String r = sendWebhook(webhookURL, content.toString(), "POST");
        
        JsonObject rawResult = JsonParser.parseString(r).getAsJsonObject();
        
        return rawResult.get("id").getAsString();
    }
    
    private static String sendWebhook(String webhookUrl, String payload, String method) throws IOException {
        try (CloseableHttpClient client = HttpClients.createDefault()) {
            HttpRequestBase request;
            
            // ?wait=true needs to be added otherwise no content is returned from Discord
            switch (method) {
                case "POST":
                    request = new HttpPost(webhookUrl + "?wait=true");
                    ((HttpPost) request).setEntity(new StringEntity(payload));
                    break;
                case "PATCH":
                    request = new HttpPatch(webhookUrl + "?wait=true");
                    ((HttpPatch) request).setEntity(new StringEntity(payload));
                    break;
                default:
                    throw new IllegalArgumentException("Unsupported HTTP method: " + method);
            }

            request.setHeader("Content-Type", "application/json");
            
            try (CloseableHttpResponse response = client.execute(request)) {
                if(response.getStatusLine().getStatusCode() == 404) {
                    throw new FileNotFoundException();
                }
                return EntityUtils.toString(response.getEntity());
            }
        }
    }
    
}

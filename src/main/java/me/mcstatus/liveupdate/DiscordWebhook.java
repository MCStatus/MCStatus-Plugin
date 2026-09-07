package me.mcstatus.liveupdate;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.Closeable;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.time.Instant;

import org.apache.http.HttpEntity;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpEntityEnclosingRequestBase;
import org.apache.http.client.methods.HttpPatch;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;

/**
 * Thin client for the Discord webhook API.
 *
 * One instance owns a single pooled HTTP client with bounded timeouts, so
 * connections are reused between updates and a stalled network can never hang
 * the caller indefinitely.  Create it once on enable and {@link #close()} it on
 * disable.  Instances are safe to use from any thread.
 */
public class DiscordWebhook implements Closeable {
    
    private static final int RED = 16711680;
    private static final int GREEN = 7052103;
    
    private static final int CONNECT_TIMEOUT_MS = 5_000;
    private static final int READ_TIMEOUT_MS = 10_000;
    
    private static final Gson GSON = new Gson();
    
    private final CloseableHttpClient client;
    
    public DiscordWebhook() {
        RequestConfig requestConfig = RequestConfig.custom()
                .setConnectTimeout(CONNECT_TIMEOUT_MS)
                .setConnectionRequestTimeout(CONNECT_TIMEOUT_MS)
                .setSocketTimeout(READ_TIMEOUT_MS)
                .build();
        
        this.client = HttpClients.custom()
                .setDefaultRequestConfig(requestConfig)
                .setUserAgent("MCStatus-LiveUpdate")
                .build();
    }

    public void sendServerStatusToDiscord(String message_id, String thumbnail_url, String embed_description, String footer_text, boolean online, int onlinePlayers, int maxPlayers, String version, String webhookUrl) throws IOException {
        
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
        
        // ?wait=true needs to be added otherwise no content is returned from Discord
        send(new HttpPatch(webhookUrl + "/messages/" + message_id + "?wait=true"), GSON.toJson(rootObject));
    }
    
    public String initWebhook(String webhookURL) throws IOException {
        JsonObject content = new JsonObject();
        
        content.addProperty("content", "Setting up MCStatus Live Update, please wait several seconds...");
        
        String r = send(new HttpPost(webhookURL + "?wait=true"), content.toString());
        
        JsonObject rawResult = JsonParser.parseString(r).getAsJsonObject();
        
        return rawResult.get("id").getAsString();
    }
    
    private String send(HttpEntityEnclosingRequestBase request, String payload) throws IOException {
        request.setEntity(new StringEntity(payload, ContentType.APPLICATION_JSON));
        
        try (CloseableHttpResponse response = client.execute(request)) {
            int status = response.getStatusLine().getStatusCode();
            
            HttpEntity entity = response.getEntity();
            String body = entity == null ? "" : EntityUtils.toString(entity);
            
            if(status == 404) {
                throw new FileNotFoundException("Discord returned 404: the webhook or its message no longer exists");
            }
            
            if(status >= 400) {
                throw new IOException("Discord returned HTTP " + status + ": " + truncate(body));
            }
            
            return body;
        }
    }
    
    private static String truncate(String s) {
        return s.length() <= 300 ? s : s.substring(0, 300) + "...";
    }
    
    @Override
    public void close() throws IOException {
        client.close();
    }
    
}

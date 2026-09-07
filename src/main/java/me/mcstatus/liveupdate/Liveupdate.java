package me.mcstatus.liveupdate;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.IllegalPluginAccessException;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;

public final class Liveupdate extends JavaPlugin implements Listener {

    private static final String UNSET_WEBHOOK_URL = "SET_YOUR_WEBHOOK_URL_HERE";
    
    private String webhookUrl;
    private String footerText;
    private String messageID = null;
    private String iconURL = "https://cdn.mcstatus.me/default.png";
    private String customVersion = "null";
    
    private static final ArrayList<String> WH_COMMENTS;
    
    static {
        WH_COMMENTS = new ArrayList<>();
        
        WH_COMMENTS.add("DO NOT MODIFY THIS unless you 100% know what you are doing!");
        WH_COMMENTS.add("Staff from the MCStatus Discord might tell you to change this to");
        WH_COMMENTS.add("debug potential issues, otherwise you should not change this.");
        WH_COMMENTS.add("This will be set automatically by the plugin.");
    }
    
    private boolean fasterUpdates = false;
    private boolean displayPlayerList = true;
    
    private BukkitTask task = null;
    
    private DiscordWebhook webhook;
    
    private ExecutorService webhookExecutor;
    
    private final AtomicBoolean updateInFlight = new AtomicBoolean(false);

    @Override
    public void onEnable() {
        saveDefaultConfig();
        
        webhook = new DiscordWebhook();
        webhookExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "MCStatus-Webhook");
            t.setDaemon(true);
            return t;
        });

        loadConfig();

        this.getLogger().log(Level.INFO, "Starting the MCStatus.me Live Update Plugin");

        getServer().getPluginManager().registerEvents(this, this);
        
        if(UNSET_WEBHOOK_URL.equals(webhookUrl)) {
            this.getLogger().log(Level.WARNING, "Set your Discord webhook URL in config.yml.");
        }
    }

    @Override
    public void onDisable() {
        this.getLogger().log(Level.INFO, "Closing the MCStatus.me Live Update Plugin");
        
        cancelUpdateTask();
        
        if(webhookExecutor != null) {
            webhookExecutor.shutdown();
            try {
                if(!webhookExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                    getLogger().warning("Timed out waiting for the last Discord update to finish");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        
        if(webhook != null && messageID != null && !UNSET_WEBHOOK_URL.equals(webhookUrl)) {
            String strippedMotd = ChatColor.stripColor(Bukkit.getServer().getMotd());
            int maxPlayers = Bukkit.getServer().getMaxPlayers();
            
            try {
                webhook.sendServerStatusToDiscord(messageID, iconURL, strippedMotd, footerText, false, 0, maxPlayers, resolveVersion(), webhookUrl);
            } catch (IOException e) {
                getLogger().warning("Could not send the offline status to Discord: " + e.getMessage());
            }
        }
        
        if(webhook != null) {
            try {
                webhook.close();
            } catch (IOException e) {
                getLogger().log(Level.WARNING, "Error closing the Discord HTTP client", e);
            }
        }
    }

    private void loadConfig() {
        FileConfiguration config = getConfig();
        webhookUrl = config.getString("webhook-url", UNSET_WEBHOOK_URL);
        footerText = config.getString("footer-text", "Set your footer in config.yml");
        fasterUpdates = config.getBoolean("use-faster-updates", false);
        iconURL = config.getString("server-icon-url");
        displayPlayerList = config.getBoolean("display-player-list");
        customVersion = config.getString("set-custom-version", "null");
        messageID = config.getString("mcstatus-wh-message-id", null);
        
        if(messageID != null && messageID.equals("null")) {
            messageID = null;
        }
        
        if(UNSET_WEBHOOK_URL.equals(webhookUrl)) {
            this.getLogger().log(Level.WARNING, "Webhook URL not set in config.yml");
            return;
        }
        
        cancelUpdateTask();
        
        if(this.messageID == null) {
            createWebhookMessage(webhookUrl);
        } else {
            startUpdateTask();
        }
    }
    
    private void createWebhookMessage(String url) {
        webhookExecutor.execute(() -> {
            String id;
            try {
                id = webhook.initWebhook(url);
            } catch (IOException e) {
                getLogger().warning("Could not create the Discord status message: " + e.getMessage());
                return;
            }
            
            runOnMainThread(() -> {
                if(!url.equals(webhookUrl) || messageID != null) {
                    return;
                }
                
                messageID = id;
                getConfig().set("mcstatus-wh-message-id", id);
                getConfig().setComments("mcstatus-wh-message-id", WH_COMMENTS);
                saveConfig();
                
                getLogger().info("Successfully saved the webhook message ID");
                
                startUpdateTask();
            });
        });
    }
    
    private void startUpdateTask() {
        cancelUpdateTask();
        
        //10-15 seconds good enough?  I don't want to abuse Discord's API too much
        long period = fasterUpdates ? 200L : 300L;
        this.task = Bukkit.getScheduler().runTaskTimer(this, this::publishStatus, 0L, period);
    }
    
    private void cancelUpdateTask() {
        if(this.task != null) {
            this.task.cancel();
            this.task = null;
        }
    }
    
    private void publishStatus() {
        if(messageID == null) {
            return;
        }
        
        if(!updateInFlight.compareAndSet(false, true)) {
            return;
        }
        
        String description = ChatColor.stripColor(Bukkit.getServer().getMotd()) + "\n\n" + buildPlayerList();
        int onlinePlayers = Bukkit.getServer().getOnlinePlayers().size();
        int maxPlayers = Bukkit.getServer().getMaxPlayers();
        String version = resolveVersion();
        
        String id = messageID;
        String url = webhookUrl;
        String icon = iconURL;
        String footer = footerText;
        
        webhookExecutor.execute(() -> {
            try {
                webhook.sendServerStatusToDiscord(id, icon, description, footer, true, onlinePlayers, maxPlayers, version, url);
            } catch (FileNotFoundException e) {
                getLogger().warning("The Discord status message no longer exists, creating a new one");
                runOnMainThread(() -> recreateWebhookMessage(id));
            } catch (IOException e) {
                getLogger().warning("Could not update the Discord status: " + e.getMessage());
            } finally {
                updateInFlight.set(false);
            }
        });
    }
    
    private void recreateWebhookMessage(String staleId) {
        if(!staleId.equals(messageID)) {
            return;
        }
        
        cancelUpdateTask();
        messageID = null;
        getConfig().set("mcstatus-wh-message-id", "null");
        getConfig().setComments("mcstatus-wh-message-id", WH_COMMENTS);
        
        createWebhookMessage(webhookUrl);
    }
    
    private String buildPlayerList() {
        if(!displayPlayerList) {
            return "";
        }
        
        Collection<? extends Player> players = getServer().getOnlinePlayers();
        if(players.isEmpty()) {
            return "";
        }
        
        StringBuilder playerList = new StringBuilder("**Players**:\n");
        boolean appended = false;
        
        for(Player onlinePlayer : players) {
            String username = onlinePlayer.getName();
            
            if((playerList.length() + username.length() + 2) > 3800) {
                break;
            }
            
            playerList.append(username).append(", ");
            appended = true;
        }
        
        if(appended) {
            playerList.setLength(playerList.length() - 2);
        }
        
        return playerList.toString();
    }
    
    private String resolveVersion() {
        return "null".equals(customVersion) ? Bukkit.getServer().getVersion() : customVersion;
    }
    
    private void runOnMainThread(Runnable action) {
        if(!isEnabled()) {
            return;
        }
        
        try {
            Bukkit.getScheduler().runTask(this, action);
        } catch (IllegalPluginAccessException ignored) {}
    }
    
    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        
        if(player.isOp()) {
            if(UNSET_WEBHOOK_URL.equals(webhookUrl)) {
                player.sendMessage(ChatColor.RED + "[MCStatus.me Live Update]: Please set the URL for your webhook in config.yml, then run /mcstatus reload (only operators can see this message)");
            }
        }
    }
    
    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if(command.getName().equalsIgnoreCase("mcstatus")) {
            if(args.length == 0) {
                sender.sendMessage(ChatColor.GREEN + "MCStatus.me Plugin Version: " + this.getDescription().getVersion());
                return true;
            }
            
            if(args.length == 1 && args[0].equalsIgnoreCase("reload")) {
                if(sender.hasPermission("mcstatus.reload")) {
                    this.reloadConfig();
                    sender.sendMessage(ChatColor.GREEN + "MCStatus.me configuration reloaded.");
                    this.getLogger().log(Level.INFO, "Configuration reloaded by " + sender.getName());
                    this.loadConfig();
                } else {
                    sender.sendMessage(ChatColor.RED + "You don't have permission to use this command.");
                }
                
                return true;
            }
            
            sender.sendMessage(ChatColor.RED + "Invalid command usage. Use /mcstatus or /mcstatus reload.");
            
            return true;
        }
        
        return false;
    }

}

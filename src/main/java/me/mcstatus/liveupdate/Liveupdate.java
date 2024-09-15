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
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.logging.Level;

public final class Liveupdate extends JavaPlugin implements Listener {

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

    @Override
    public void onEnable() {
        saveDefaultConfig();

        try {
            loadConfig();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        this.getLogger().log(Level.INFO, "Starting the MCStatus.me Live Update Plugin");

        getServer().getPluginManager().registerEvents(this, this);
        
        if(webhookUrl.equals("SET_YOUR_WEBHOOK_URL_HERE")) {
            this.getLogger().log(Level.WARNING, "Set your Discord webhook URL in config.yml.");
            return;
        }
        
    }

    @Override
    public void onDisable() {
        this.getLogger().log(Level.INFO, "Closing the MCStatus.me Live Update Plugin");

        String motd = Bukkit.getServer().getMotd();
        String strippedMotd = ChatColor.stripColor(motd);
        
        int maxPlayers = Bukkit.getServer().getMaxPlayers();
        
        String version = Bukkit.getServer().getVersion();

        if(!customVersion.equals("null")) {
            version = customVersion;
        }
        
        try {
            if(messageID != null) {
                DiscordWebhook.sendServerStatusToDiscord(messageID, iconURL, strippedMotd, footerText, false, 0, maxPlayers, version, webhookUrl);
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        
        if(this.task != null) {
            this.task.cancel();
            this.task = null;
        }
    }

    private void loadConfig() throws IOException {
        FileConfiguration config = getConfig();
        webhookUrl = config.getString("webhook-url", "SET_YOUR_WEBHOOK_URL_HERE");
        footerText = config.getString("footer-text", "Set your footer in config.yml");
        fasterUpdates = config.getBoolean("use-faster-updates", false);
        iconURL = config.getString("server-icon-url");
        displayPlayerList = config.getBoolean("display-player-list");
        customVersion = config.getString("set-custom-version", "null");
        messageID = config.getString("mcstatus-wh-message-id", null);
        
        if(messageID != null && messageID.equals("null")) {
            messageID = null;
        }
        
        if("SET_YOUR_WEBHOOK_URL_HERE".equals(webhookUrl)) {
            this.getLogger().log(Level.WARNING, "Webhook URL not set in config.yml");
            return;
        }
        
        if(this.task != null) {
            this.task.cancel();
            this.task = null;
        }
        
        if(this.messageID == null) {
            this.messageID = DiscordWebhook.initWebhook(webhookUrl);
            
            config.set("mcstatus-wh-message-id", messageID);
            config.setComments("mcstatus-wh-message-id", WH_COMMENTS);
            saveConfig();
            
            getLogger().info("Successfully saved the webhook message ID");
        }
        
        this.task = new BukkitRunnable() {
            @Override
            public void run() {
                
                String motd = Bukkit.getServer().getMotd();
                
                String strippedMotd = ChatColor.stripColor(motd);
                
                StringBuilder playerList = new StringBuilder();
                
                if(displayPlayerList) {
                    
                    if(!getServer().getOnlinePlayers().isEmpty()) {
                        playerList.append("**Players**:\n");
                    }
                    
                    for(Player onlinePlayer : getServer().getOnlinePlayers()) {
                        String username = onlinePlayer.getName();
                        //+2 for the comma and space
                        if((playerList.length() + username.length() + 2) > 3800) {
                            break;
                        }

                        playerList.append(username).append(", ");
                    }

                    if(playerList.toString().endsWith(", ")) {
                        playerList = new StringBuilder(playerList.substring(0, playerList.length() - 2));
                    }
                }
                
                int onlinePlayers = Bukkit.getServer().getOnlinePlayers().size();
                int maxPlayers    = Bukkit.getServer().getMaxPlayers();
                
                String version = Bukkit.getServer().getVersion();
                
                if(!customVersion.equals("null")) {
                    version = customVersion;
                }
                
                try {
                    if(messageID != null) {
                        DiscordWebhook.sendServerStatusToDiscord(messageID, iconURL, strippedMotd + "\n\n" + playerList, footerText, true, onlinePlayers, maxPlayers, version, webhookUrl);
                    }
                } catch(FileNotFoundException ignored) {
                    config.set("mcstatus-wh-message-id", "null");
                    config.setComments("mcstatus-wh-message-id", WH_COMMENTS);
                    
                    try {
                        loadConfig();
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }
        }.runTaskTimer(this, 0L, fasterUpdates ? 200L : 300L); //10-15 seconds good enough?  I don't want to abuse Discord's API too much
    }
    
    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        
        if(player.isOp()) {
            if("SET_YOUR_WEBHOOK_URL_HERE".equals(webhookUrl)) {
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
                    try {
                        this.loadConfig();
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
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

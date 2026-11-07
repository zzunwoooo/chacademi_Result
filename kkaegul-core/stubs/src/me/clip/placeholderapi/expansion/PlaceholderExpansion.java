package me.clip.placeholderapi.expansion;
public abstract class PlaceholderExpansion {
    public abstract String getIdentifier();
    public abstract String getAuthor();
    public abstract String getVersion();
    public boolean persist() { return false; }
    public boolean canRegister() { return true; }
    public String onRequest(org.bukkit.OfflinePlayer player, String params) { return null; }
    public String onPlaceholderRequest(org.bukkit.entity.Player player, String params) { return null; }
    public boolean register() { return true; }
}

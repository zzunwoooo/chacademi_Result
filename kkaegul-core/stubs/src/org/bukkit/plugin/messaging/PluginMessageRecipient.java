package org.bukkit.plugin.messaging;
public interface PluginMessageRecipient { void sendPluginMessage(org.bukkit.plugin.Plugin s, String c, byte[] m); java.util.Set<String> getListeningPluginChannels(); }

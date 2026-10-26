package school.magiccodex.paper;
final class OpeningHooks {
 static void fish(MagicCodexBridge plugin,org.bukkit.entity.Player player,String fish,double size){TitleBridge titles=plugin.titleService();if(titles!=null)titles.fish(player.getUniqueId(),fish,size);}
 static double multiplier(org.bukkit.configuration.ConfigurationSection cfg){double value=cfg.getDouble("range-multiplier",1.0);if(!Double.isFinite(value)||value<0.25||value>3.0)throw new IllegalArgumentException("range-multiplier 는 0.25~3 이어야 합니다.");return value;}
}

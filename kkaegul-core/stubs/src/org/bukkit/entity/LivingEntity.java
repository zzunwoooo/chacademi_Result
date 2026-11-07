package org.bukkit.entity;
import org.bukkit.potion.*;
public interface LivingEntity extends Damageable { boolean addPotionEffect(PotionEffect e); void removePotionEffect(PotionEffectType t); boolean hasPotionEffect(PotionEffectType t); void setInvisible(boolean b); void setAI(boolean b); Entity getTargetEntity(int max); }

package school.magiccodex.client;
/** Shared target eligibility, independent of Minecraft bootstrap, including invisible ModelEngine bases. */
final class AimEntityPolicy {
 static boolean allowed(boolean alive,boolean armorStand,boolean spectator,boolean player,boolean invisible){return alive&&!armorStand&&!spectator&&!(player&&invisible);}
}
package school.chacademia.kyudichi;

import org.bukkit.Material;

enum BallKind {
    QUAFFLE("quaffle", "퀘이플", Material.ORANGE_CONCRETE_POWDER),
    BLUDGER("bludger", "블러저", Material.BLACKSTONE),
    SNITCH("snitch", "스니치", Material.GOLD_BLOCK);

    final String key, label; final Material fallback;
    BallKind(String key, String label, Material fallback) { this.key = key; this.label = label; this.fallback = fallback; }
}

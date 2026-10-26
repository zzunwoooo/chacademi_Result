package dev.portablevfx.paper.internal.spell;

import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

/** One entry point shared by command/HUD adapters. It does not create a second mana system. */
public final class SpellCastController {
    public enum Status { CAST, UNKNOWN_SPELL, NOT_READY, NO_AUTHORITY, DENIED, PLAYBACK_FAILED }
    public record Result(Status status, String spellId) {}

    /**
     * Existing server mana owner checks permission, cost/cooldown and target validity,
     * then executes playback exactly once as a transaction. Failed playback must not
     * charge/start cooldown. HUD-preauthorized casts must use that owner's same path.
     */
    @FunctionalInterface public interface Authority {
        Status authorizeAndRun(UUID player, SpellCatalog.Spell spell, Supplier<Boolean> playback);
    }
    @FunctionalInterface public interface Playback {
        boolean start(UUID player, SpellCatalog.Spell spell);
    }
    private final SpellCatalog catalog;
    private final Authority authority;
    private final Playback playback;
    public SpellCastController(SpellCatalog catalog, Authority authority, Playback playback) {
        this.catalog=Objects.requireNonNull(catalog);this.authority=authority;this.playback=playback;
    }
    public Result cast(UUID player, String input) {
        Objects.requireNonNull(player);
        SpellCatalog.Spell spell=catalog.resolve(input).orElse(null);
        if(spell==null)return new Result(Status.UNKNOWN_SPELL, "");
        if(!spell.enabled())return new Result(Status.NOT_READY,spell.id());
        if(authority==null||playback==null)return new Result(Status.NO_AUTHORITY,spell.id());
        boolean[] invoked={false}, succeeded={false};
        Status result=authority.authorizeAndRun(player,spell,()->{
            if(invoked[0])throw new IllegalStateException("Authority attempted duplicate playback");
            invoked[0]=true;succeeded[0]=playback.start(player,spell);return succeeded[0];
        });
        if(result==Status.CAST&&!invoked[0])throw new IllegalStateException("Authority claimed cast without playback");
        if(result==Status.CAST&&!succeeded[0])throw new IllegalStateException("Authority claimed cast after failed playback");
        return new Result(Objects.requireNonNull(result),spell.id());
    }
}

package school.magiccodex.discovery;

import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/** Trusted SERVER integration only. Never call from a client packet without verifying the actual action. */
public final class DiscoveryService {
    private final MagicDiscovery plugin;
    DiscoveryService(MagicDiscovery plugin){this.plugin=plugin;}
    /** Adds a verified event count, e.g. book.flight=1 or cook.hearth_touch=4. */
    public boolean signal(UUID player,String event,double amount){return plugin.signal(player,event,amount);}
    /** Latest authoritative state (state.circle, state.temperature, state.flight_prohibited). */
    public boolean state(UUID player,String key,double value){return plugin.state(player,key,value);}
    /** Explicit verified state event, including retrying a state-only discovery at the same value. */
    public boolean stateEvent(UUID player,String key,double value){return plugin.state(player,key,value,true);}
    /** A successful cast, not an attempted command. Existing Bridge forwards successful casts automatically. */
    public void cast(UUID player,String spell,double spentMana){plugin.cast(player,spell,spentMana);}
    /** Immutable ID -> display name map: all registered Bridge spells, or standalone discovery definitions. Must be called on the server thread. */
    public Map<String,String> registeredSpells(){return plugin.registeredSpells();}
    /** Only explicitly configured manual replacements; command facade performs administrator checks. */
    public void adminCondition(UUID player,String spell,String action,Consumer<String> done){plugin.adminCondition(player,spell,action,done);}
    /**
     * 마법 힌트권 3성 (MagicCodexBridge SpellHints, 2026-10-26): 그 마법 조건 키 → 지금 값 (다시 배우는 중이면 기준값을 뺀 값, 읽기 전용).
     * 서버 스레드에서 부르고 done 도 서버 스레드. 접속 중이 아니면 DB 를 비동기로 읽는다. done(null) = 모르는 마법·읽기 실패.
     */
    public void hintProgress(UUID player,String spell,Consumer<Map<String,Double>> done){plugin.hintProgress(player,spell,done);}
    public Map<String,String> manualConditions(){var out=new java.util.LinkedHashMap<String,String>();plugin.definitions.manual.forEach((id,spell)->out.put(id,spell.name()));return Map.copyOf(out);}
    /**
     * Persist current knowledge for an online or offline player without minting first-discovery rewards.
     * Unlearning retains historical acquisitions and starts fresh, spell-specific retry progress.
     * Call on the server thread. The callback also runs there: null means success, otherwise a user-facing error.
     * Only trusted, permission-checked administrator integrations may invoke this method.
     */
    public void adminSetLearned(UUID player,String spellId,boolean learned,Consumer<String> completion){
        plugin.adminSetLearned(player,spellId,learned,completion);
    }
}

package school.chacademia.wildlife;

import io.lumine.mythic.api.adapters.AbstractLocation;
import io.lumine.mythic.api.skills.ThreadSafetyLevel;
import io.lumine.mythic.api.skills.conditions.ILocationCondition;
import io.lumine.mythic.core.skills.SkillCondition;

public final class HabitatCondition extends SkillCondition implements ILocationCondition {
    private final WildlifePlugin plugin;
    private final String id;
    public HabitatCondition(WildlifePlugin plugin, String line, String id) {
        super(line); this.plugin=plugin; this.id=id;
        this.threadSafetyLevel=ThreadSafetyLevel.SYNC_ONLY;
    }
    @Override public boolean check(AbstractLocation location) {
        return plugin.naturalCheck(id, location);
    }
}

package school.magiccodex.paper;

import java.util.function.Predicate;

/** Catalog #149 keeps its persisted ID; all legacy entry points use the same grant and cooldown. */
final class TamingSpell {
    static final String ID="rainbow_collar",LEGACY_ID="taming",PERMISSION="magic.learned.rainbow_collar";
    private TamingSpell(){}
    static boolean matches(String id){return ID.equals(id)||LEGACY_ID.equals(id);}
    static String canonicalId(String id){return matches(id)?ID:id;}
    static boolean permitted(ManaSpells.Spell spell,Predicate<String> permission){
        return spell!=null&&ID.equals(spell.id())&&permission.test(PERMISSION)
                &&permission.test(spell.permission())&&permission.test("magiccodex.taming");
    }
}

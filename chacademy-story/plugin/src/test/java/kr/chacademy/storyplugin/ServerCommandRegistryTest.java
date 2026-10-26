package kr.chacademy.storyplugin;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class ServerCommandRegistryTest {
 @Test void singleFileKeepsOrderAndUnknownIdHasNoAuthority(){var registry=new ServerCommandRegistry();registry.replace(Map.of("commands",Map.of("ch1-3",List.of("대화관리 재생 {player} 컷신 test","say hello"),"ch1-1",List.of("say test"))));assertEquals(List.of("대화관리 재생 {player} 컷신 test","say hello"),registry.commands("ch1-3"));assertNull(registry.commands("missing"));}
 @Test void malformedReplacementRetainsLastValidCommands(){var registry=new ServerCommandRegistry();registry.replace(Map.of("commands",Map.of("ch1-3",List.of("say old"))));for(Object invalid:List.of(Map.of("commands",Map.of("ch1-3","say new")),Map.of("endings",Map.of("s20",List.of("say old-format"))),Map.of("commands",Map.of("ch1-3",List.of("say a\nsay b"))))){assertThrows(IllegalArgumentException.class,()->registry.replace(invalid));assertEquals(List.of("say old"),registry.commands("ch1-3"));}}
 @Test void registeredSessionDispatchesSameListForEitherEndingOnce(){var registry=new ServerCommandRegistry();registry.replace(Map.of("commands",Map.of("ch1-3",List.of("first","second"))));var sessions=new LocalEndingSessions();UUID player=UUID.randomUUID();for(String ending:List.of("s20","s21")){var session=sessions.begin(player,"ch1-3",registry.commands("ch1-3"),0);sessions.ready(player,"ch1-3",session.token(),0);assertEquals(List.of("first","second"),sessions.finish(player,"ch1-3",session.token(),ending,0));assertTrue(sessions.finish(player,"ch1-3",session.token(),ending,0).isEmpty());}}
}

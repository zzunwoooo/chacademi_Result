package school.magiccodex.paper;
import java.time.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class RealtimeSeasonTest {
    private RealtimeSeason clock(){return RealtimeSeason.parse("2026-01-01T00:00:00+09:00","Asia/Seoul",0,72);}
    @Test void transitionsExactlyEvery72RealHours(){
        var c=clock(); assertEquals(0,c.at(c.epoch().plusSeconds(72*3600-1)));
        for(int i=0;i<9;i++)assertEquals(i%4,c.at(c.epoch().plusSeconds(i*72L*3600)));
    }
    @Test void restartAndTimezonesCannotChangeElapsedSeason(){
        var c=clock(); var now=c.epoch().plusSeconds(73*3600);
        assertEquals(c.at(now),clock().at(now));
        var dst=RealtimeSeason.parse("2026-01-01T00:00:00+09:00","America/New_York",0,72);
        assertEquals(c.at(now),dst.at(now));
        assertEquals(c.next(now),dst.next(now));
    }
    @Test void futureEpochDoesNotWrapAndNoImplicitEpoch(){
        var c=clock(); assertEquals(0,c.at(c.epoch().minusSeconds(1))); assertEquals(c.epoch(),c.next(c.epoch().minusSeconds(1)));
        assertThrows(IllegalArgumentException.class,()->RealtimeSeason.parse("","Asia/Seoul",0,72));
        assertThrows(RuntimeException.class,()->RealtimeSeason.parse("2026-01-01T00:00:00","Asia/Seoul",0,72));
    }
    @Test void invalidConfigRejected(){
        assertThrows(RuntimeException.class,()->RealtimeSeason.parse("2026-01-01T00:00:00Z","Invalid/Zone",0,72));
        assertThrows(IllegalArgumentException.class,()->new RealtimeSeason(Instant.EPOCH,ZoneOffset.UTC,0,0));
    }
}

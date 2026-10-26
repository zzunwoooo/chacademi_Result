package school.magiccodex.paper;
import org.junit.jupiter.api.Test;import static org.junit.jupiter.api.Assertions.*;
import java.time.*;
class CalendarSeasonTest {
 @Test void koreanMidnightStartsSpringIndependentOfUtcDay(){var z=ZoneId.of("Asia/Seoul");assertEquals(3,CalendarSeason.at(Instant.parse("2026-02-28T14:59:59Z"),z));assertEquals(0,CalendarSeason.at(Instant.parse("2026-02-28T15:00:00Z"),z));}
 @Test void seasonalMonthBoundaries(){var z=ZoneId.of("Asia/Seoul");for(int month=1;month<=12;month++){int expected=month>=3&&month<=5?0:month>=6&&month<=8?1:month>=9&&month<=11?2:3;assertEquals(expected,CalendarSeason.at(LocalDate.of(2026,month,1).atStartOfDay(z).toInstant(),z));}}
}

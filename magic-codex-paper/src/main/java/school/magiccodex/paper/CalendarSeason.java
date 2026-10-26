package school.magiccodex.paper;
import java.time.*;
final class CalendarSeason {
 static int at(Instant now,ZoneId zone){int month=now.atZone(zone).getMonthValue();return month>=3&&month<=5?0:month>=6&&month<=8?1:month>=9&&month<=11?2:3;}
}

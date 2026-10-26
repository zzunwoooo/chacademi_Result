package school.magiccodex.paper;

import java.time.*;

/** Explicit real-time epoch; game ticks, sleeping and server restarts are irrelevant. */
record RealtimeSeason(Instant epoch, ZoneId zone, int start, long hours) {
    RealtimeSeason {
        if(epoch==null || zone==null || start<0 || start>3 || hours<1 || hours>8760)
            throw new IllegalArgumentException("Invalid real-time season configuration");
    }
    static RealtimeSeason parse(String epoch, String zone, int start, long hours) {
        var timezone=ZoneId.of(zone);
        if(epoch==null || epoch.isBlank()) throw new IllegalArgumentException("real-time-season.epoch must be explicitly configured");
        // Require an ISO instant/offset, never resolve ambiguous local DST timestamps silently.
        return new RealtimeSeason(OffsetDateTime.parse(epoch).toInstant(),timezone,start,hours);
    }
    int at(Instant now) {
        long elapsed=Math.max(0,Duration.between(epoch,now).getSeconds());
        return (int)((start+elapsed/(hours*3600))%4);
    }
    Instant next(Instant now) {
        long period=hours*3600;
        if(now.isBefore(epoch)) return epoch;
        long elapsed=Duration.between(epoch,now).getSeconds();
        return epoch.plusSeconds((elapsed/period+1)*period);
    }
}

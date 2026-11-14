package sn.recozai.identity.adapter;

import org.springframework.stereotype.Component;
import sn.recozai.identity.application.Credentials;
import sn.recozai.platform.Database;
import sn.recozai.shared.*;
import java.time.*;

@Component
public class RateLimits {
    private final Database db;
    private final Transactions tx;
    private final Credentials crypto;
    private final Clock clock;
    public RateLimits(Database db, Transactions tx, Credentials crypto, Clock clock) {
        this.db=db; this.tx=tx; this.crypto=crypto; this.clock=clock;
    }
    public void check(String key, int limit) {
        Instant now = clock.instant();
        long attempts = tx.execute(() -> {
            db.execute("DELETE FROM auth_rate_limit WHERE window_start < :expired", "expired", now.minus(Duration.ofHours(1)));
            return Database.number(db.rows("""
                INSERT INTO auth_rate_limit(key_hash,window_start,attempts) VALUES(:key,:now,1)
                ON CONFLICT(key_hash) DO UPDATE SET
                attempts=CASE WHEN auth_rate_limit.window_start < :cutoff THEN 1 ELSE auth_rate_limit.attempts+1 END,
                window_start=CASE WHEN auth_rate_limit.window_start < :cutoff THEN :now ELSE auth_rate_limit.window_start END
                RETURNING attempts
                """, "key", crypto.hash(key), "now", now, "cutoff", now.minus(Duration.ofMinutes(5))).getFirst(), "attempts");
        });
        if (attempts > limit) throw new Failure(429, "RATE_LIMITED");
    }
}

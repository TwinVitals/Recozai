package sn.recozai.clinic.adapter;

import org.springframework.stereotype.Repository;
import sn.recozai.clinic.api.ClinicSettings;
import sn.recozai.clinic.application.ClinicStore;
import sn.recozai.platform.Database;
import sn.recozai.shared.Failure;
import java.util.UUID;
import static sn.recozai.platform.Database.*;

@Repository
public class JpaClinicStore implements ClinicStore {
    private final Database db;
    public JpaClinicStore(Database db) { this.db = db; }
    @Override public ClinicSettings get(UUID id) {
        return db.rows("SELECT * FROM clinic WHERE id=:id", "id", id).stream().findFirst()
            .map(r -> new ClinicSettings(uuid(r,"id"), text(r,"name"), text(r,"locale"), text(r,"timezone"), text(r,"currency"), (int) number(r,"fraction_digits"), number(r,"version")))
            .orElseThrow(() -> new Failure(404, "NOT_FOUND"));
    }
    @Override public void update(ClinicSettings s, long version) {
        if (db.execute("UPDATE clinic SET name=:name,locale=:locale,timezone=:zone,currency=:currency,fraction_digits=:digits,version=version+1 WHERE id=:id AND version=:version",
            "id", s.id(), "name", s.name(), "locale", s.locale(), "zone", s.timezone(), "currency", s.currency(), "digits", s.fractionDigits(), "version", version) != 1)
            throw new Failure(412, "STALE_VERSION");
    }
    @Override public void create(ClinicSettings s) {
        db.execute("INSERT INTO clinic(id,name,locale,timezone,currency,fraction_digits) VALUES(:id,:name,:locale,:zone,:currency,:digits)",
            "id", s.id(), "name", s.name(), "locale", s.locale(), "zone", s.timezone(), "currency", s.currency(), "digits", s.fractionDigits());
    }
}

package sn.recozai.clinic.application;

import sn.recozai.audit.api.Audit;
import sn.recozai.clinic.api.ClinicSettings;
import sn.recozai.access.*;
import sn.recozai.shared.*;
import java.time.*;
import java.util.*;

public final class ClinicService {
    private final ClinicStore store;
    private final Transactions transactions;
    private final Access access;
    private final Audit audit;
    public ClinicService(ClinicStore store, Transactions transactions, Access access, Audit audit) {
        this.store = store; this.transactions = transactions; this.access = access; this.audit = audit;
    }
    public ClinicSettings get(Actor actor) {
        return transactions.execute(() -> {
            Actor current = access.current(actor);
            if (current.clinicId() == null) throw new Failure(403, "MEMBERSHIP_REQUIRED");
            return store.get(current.clinicId());
        });
    }
    public ClinicSettings update(Actor actor, String name, String locale, String zone,
                                String currency, int digits, long version) {
        validate(name, locale, zone, currency, digits);
        return transactions.execute(() -> {
            Actor current = access.current(actor); current.requireAdmin();
            ClinicSettings settings = new ClinicSettings(current.clinicId(), name.strip(), locale, zone, currency, digits, version + 1);
            store.update(settings, version);
            audit.record(current.userId(), current.clinicId(), "CLINIC_CONFIGURED", current.clinicId());
            return settings;
        });
    }
    /**
     * Creates the first clinic with product defaults (fr-SN, Africa/Dakar, XOF, 0 digits).
     * Composition-only: invoked by Identity's custodian bootstrap through its {@code ClinicProvisioning}
     * port, inside the caller's transaction. There is no HTTP route for this use case.
     */
    public UUID provision(String name) {
        if (name == null || name.isBlank() || name.length() > 120) throw new Failure(422, "INVALID_CLINIC_SETTINGS");
        UUID clinic = UUID.randomUUID();
        store.create(new ClinicSettings(clinic, name, "fr-SN", "Africa/Dakar", "XOF", 0, 0));
        return clinic;
    }
    public ClinicSettings settingsForClinic(UUID clinicId) {
        return store.get(clinicId);
    }
    public static void validate(String name, String locale, String zone, String currency, int digits) {
        if (name.isBlank() || name.length() > 120 || !Set.of("fr-SN","en").contains(locale) || digits < 0 || digits > 6)
            throw new Failure(422, "INVALID_CLINIC_SETTINGS");
        try { ZoneId.of(zone); Currency.getInstance(currency); }
        catch (DateTimeException | IllegalArgumentException error) { throw new Failure(422, "INVALID_CLINIC_SETTINGS"); }
    }
}

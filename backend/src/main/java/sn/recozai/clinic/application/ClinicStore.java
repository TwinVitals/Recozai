package sn.recozai.clinic.application;

import sn.recozai.clinic.api.ClinicSettings;
import java.util.UUID;

public interface ClinicStore {
    ClinicSettings get(UUID id);
    void update(ClinicSettings settings, long expectedVersion);
    void create(ClinicSettings settings);
}

package sn.recozai.identity.application;

import java.util.UUID;

/** Outbound port used only by custodian BOOTSTRAP; implemented by the composition root via Clinic's application API. */
@FunctionalInterface
public interface ClinicProvisioning {
    UUID provision(String clinicName);
}

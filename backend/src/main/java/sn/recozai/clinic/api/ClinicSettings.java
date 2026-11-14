package sn.recozai.clinic.api;

import java.util.UUID;

public record ClinicSettings(UUID id, String name, String locale, String timezone,
                            String currency, int fractionDigits, long version) {}

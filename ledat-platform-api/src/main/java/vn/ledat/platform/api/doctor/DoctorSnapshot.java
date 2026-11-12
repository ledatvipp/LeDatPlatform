package vn.ledat.platform.api.doctor;

import java.time.Instant;
import java.util.List;

public record DoctorSnapshot(Severity overall, int warnings, int errors, Instant createdAt, List<DoctorRow> rows) {
}

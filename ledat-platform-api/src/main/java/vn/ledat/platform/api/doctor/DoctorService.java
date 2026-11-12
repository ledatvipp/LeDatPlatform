package vn.ledat.platform.api.doctor;

import java.util.List;

public interface DoctorService {
    DoctorSnapshot snapshot();
    List<DoctorRow> check(String category);
    void reload();
}

package vn.ledat.platform.api.config;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class ConfigValidationReport {
    private final List<String> errors = new ArrayList<>();
    private final List<String> warnings = new ArrayList<>();

    public void error(String message) {
        errors.add(message);
    }

    public void warn(String message) {
        warnings.add(message);
    }

    public boolean ok() {
        return errors.isEmpty();
    }

    public List<String> errors() {
        return Collections.unmodifiableList(errors);
    }

    public List<String> warnings() {
        return Collections.unmodifiableList(warnings);
    }

    public int errorCount() {
        return errors.size();
    }

    public int warningCount() {
        return warnings.size();
    }
}

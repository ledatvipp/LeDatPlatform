package vn.ledat.platform.api.scheduler;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EntityTaskOutcomeTest {
    @Test
    void exposesDistinctTerminalOutcomes() {
        IllegalStateException failure = new IllegalStateException("capture failed");

        EntityTaskOutcome<String> executed = EntityTaskOutcome.executed("snapshot");
        EntityTaskOutcome<String> retired = EntityTaskOutcome.retired();
        EntityTaskOutcome<String> rejected = EntityTaskOutcome.rejected();
        EntityTaskOutcome<String> failed = EntityTaskOutcome.failed(failure);

        assertEquals(EntityTaskOutcome.Status.EXECUTED, executed.status());
        assertEquals("snapshot", executed.value());
        assertTrue(executed.executed());
        assertEquals(EntityTaskOutcome.Status.RETIRED, retired.status());
        assertEquals(EntityTaskOutcome.Status.REJECTED, rejected.status());
        assertEquals(EntityTaskOutcome.Status.FAILED, failed.status());
        assertEquals(failure, failed.error());
        assertNull(retired.value());
        assertFalse(retired.executed());
    }
}

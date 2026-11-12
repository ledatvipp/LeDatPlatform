package vn.ledat.platform.api.command;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommandContextParsingTest {
    @Test
    void parsesCommonTypedArgumentsWithoutThrowing() {
        CommandContext context = new CommandContext(null, null, "test", new String[]{"yes", "15m", "invalid"});
        assertTrue(context.booleanArg(0, false));
        assertEquals(Duration.ofMinutes(15), context.durationArg(1, Duration.ZERO));
        assertEquals(Duration.ofSeconds(5), context.durationArg(2, Duration.ofSeconds(5)));
        assertFalse(context.uuidArg(2).isPresent());
    }
}

package de.derfakegamer.sentinel.util;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.filter.AbstractFilter;
import org.apache.logging.log4j.message.Message;

/**
 * Log4j2 filter that suppresses any console log line mentioning "orbitalstrike"
 * (case-insensitive), so the command never appears in the server console log.
 *
 * Registered on the root logger at runtime. Returns DENY for matching events and
 * NEUTRAL otherwise (so non-matching events follow the normal logging pipeline).
 */
public class OrbitalConsoleFilter extends AbstractFilter {

    private static final String NEEDLE = "orbitalstrike";

    private Result decide(String message) {
        if (message != null && message.toLowerCase(java.util.Locale.ROOT).contains(NEEDLE)) {
            return Result.DENY;
        }
        return Result.NEUTRAL;
    }

    @Override
    public Result filter(LogEvent event) {
        if (event == null || event.getMessage() == null) {
            return Result.NEUTRAL;
        }
        return decide(event.getMessage().getFormattedMessage());
    }

    @Override
    public Result filter(Logger logger, Level level, Marker marker, String msg, Object... params) {
        return decide(msg);
    }

    @Override
    public Result filter(Logger logger, Level level, Marker marker, Object msg, Throwable t) {
        return decide(msg == null ? null : msg.toString());
    }

    @Override
    public Result filter(Logger logger, Level level, Marker marker, Message msg, Throwable t) {
        return decide(msg == null ? null : msg.getFormattedMessage());
    }

    /** Adds this filter to the Log4j2 root logger's configuration. */
    public void register() {
        ((Logger) LogManager.getRootLogger()).get().addFilter(this);
    }

    /** Removes this filter from the Log4j2 root logger's configuration. */
    public void unregister() {
        ((Logger) LogManager.getRootLogger()).get().removeFilter(this);
    }
}

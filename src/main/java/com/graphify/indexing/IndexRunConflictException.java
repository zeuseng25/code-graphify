package com.graphify.indexing;

import com.graphify.common.exception.ConflictException;
import java.util.Map;
import java.util.OptionalLong;

/** The index lock is held (spec §8: a second run request gets 409); names the run in progress when there is one. */
public class IndexRunConflictException extends ConflictException {

    public IndexRunConflictException(String holder) {
        super(message(holder), properties(holder));
    }

    private static String message(String holder) {
        OptionalLong runId = IndexLock.runIdOf(holder);
        if (runId.isPresent()) {
            return "Index run " + runId.getAsLong() + " is in progress";
        }
        if (IndexLock.CLEANUP_HOLDER.equals(holder)) {
            return "The orphan symbol cleanup is in progress";
        }
        return "Another index operation is in progress";
    }

    private static Map<String, Object> properties(String holder) {
        OptionalLong runId = IndexLock.runIdOf(holder);
        return runId.isPresent() ? Map.of("runId", runId.getAsLong()) : Map.of();
    }
}

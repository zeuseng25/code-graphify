package com.graphify.store;

import com.graphify.settings.AppSettings;
import com.graphify.settings.SettingKeys;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Deletes symbols that no usage or declaration references any more (spec §4.4). A symbol that is still some other
 * symbol's parent is kept, even when nothing references it directly (an owner type used only through its members):
 * deleting it would clear its members' {@code parent_id}. A parent is therefore deleted by a later statement or run,
 * once its children are gone. Must only run under the index lock ({@code OrphanCleanupJob}, or {@code IndexRunExecutor}
 * at the end of a run): an index write may have merged a symbol it has not yet referenced.
 */
@Component
public class SymbolCleanup {

    private static final String DELETE_BATCH = """
            DELETE FROM symbol s
             WHERE NOT EXISTS (SELECT 1 FROM usage u WHERE u.to_symbol_id = s.id)
               AND NOT EXISTS (SELECT 1 FROM usage u WHERE u.from_symbol_id = s.id)
               AND NOT EXISTS (SELECT 1 FROM symbol_declaration d WHERE d.symbol_id = s.id)
               AND NOT EXISTS (SELECT 1 FROM symbol c WHERE c.parent_id = s.id)
               AND ROWNUM <= ?
            """;

    private final JdbcTemplate jdbc;
    private final AppSettings settings;

    public SymbolCleanup(JdbcTemplate jdbc, AppSettings settings) {
        this.jdbc = jdbc;
        this.settings = settings;
    }

    /**
     * Deletes orphans in statements of at most cleanup.batch_size rows, each committed on its own so undo never grows
     * with the whole cleanup; stops after a statement that deleted less than a full batch. Returns the total.
     */
    public int deleteOrphans() {
        int batchSize = settings.getInt(SettingKeys.CLEANUP_BATCH_SIZE);
        int total = 0;
        int deleted;
        do {
            deleted = jdbc.update(DELETE_BATCH, batchSize);
            total += deleted;
        } while (deleted == batchSize);
        return total;
    }
}

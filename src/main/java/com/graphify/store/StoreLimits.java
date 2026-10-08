package com.graphify.store;

import com.graphify.common.util.Utf8;
import com.graphify.indexer.model.Symbol;

/** Column byte widths from V1__core_schema.sql. Rows that cannot fit an identifying column are skipped. */
final class StoreLimits {

    static final int PACKAGING_BYTES = 40; // maven_module.packaging (V13)
    static final int SYMBOL_KEY_BYTES = 4000;
    static final int CLASS_FQN_BYTES = 2000;
    static final int MEMBER_NAME_BYTES = 1000;
    static final int DISPLAY_SIGNATURE_BYTES = 4000;
    static final int FILE_PATH_BYTES = 1000;
    static final int SNIPPET_BYTES = 4000;
    static final int GROUP_ID_BYTES = 300;
    static final int ARTIFACT_ID_BYTES = 300;
    static final int VERSION_BYTES = 100;
    static final int SCOPE_BYTES = 20;
    static final int MODULE_PATH_BYTES = 1000;
    static final int COMMIT_BYTES = 64;

    private StoreLimits() {
    }

    static boolean storable(Symbol symbol) {
        return fits(symbol.key(), SYMBOL_KEY_BYTES)
                && fits(symbol.classFqn(), CLASS_FQN_BYTES)
                && fits(symbol.memberName(), MEMBER_NAME_BYTES);
    }

    static boolean fits(String text, int maxBytes) {
        return text == null || Utf8.byteLength(text) <= maxBytes;
    }
}

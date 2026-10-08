/** Wraps long symbol keys, paths and qualified names anywhere instead of widening their table or panel. */
export const WRAP_ANYWHERE = { overflowWrap: 'anywhere' } as const;

/** Never wrapped: a badge or number keeps its full width, so its column is not squeezed. */
export const NO_WRAP = { whiteSpace: 'nowrap' } as const;

/** Presentational: below this width (px) a wide table scrolls inside its own container instead of widening the page. */
export const WIDE_TABLE_MIN_WIDTH = 640;

/** Presentational: a picker (Select) inside a table cell keeps at least this width (px), so its value stays readable. */
export const CELL_SELECT_MIN_WIDTH = 140;

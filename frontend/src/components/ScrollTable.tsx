import { Table, type TableProps } from '@mantine/core';
import { WIDE_TABLE_MIN_WIDTH } from './wrapStyles';

/** A wide table that scrolls inside its own container on a narrow screen instead of widening the page. */
export function ScrollTable(props: TableProps) {
  return (
    <Table.ScrollContainer minWidth={WIDE_TABLE_MIN_WIDTH} type="native">
      <Table {...props} />
    </Table.ScrollContainer>
  );
}

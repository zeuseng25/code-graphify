import { Group, Pagination } from '@mantine/core';

/** Pages of a backend list; page is 0-based like the API, Mantine's control is 1-based. */
export function Pager({ page, size, total, onChange }: {
  page: number;
  size: number | undefined;
  total: number | undefined;
  onChange: (page: number) => void;
}) {
  const pages = size && total ? Math.ceil(total / size) : 1;
  if (pages <= 1) {
    return null;
  }
  return (
    <Group justify="center">
      <Pagination total={pages} value={page + 1} onChange={(value) => onChange(value - 1)} />
    </Group>
  );
}

import { Select } from '@mantine/core';
import { useRepositoryOptions } from '../../api/repositories';
import { formatRepository } from '../../i18n/format';

/** A searchable repository picker; value and onChange carry the repository id. */
export function RepositorySelect({ label, placeholder, value, onChange }: {
  label: string;
  placeholder: string;
  value: number | undefined;
  onChange: (id: number | undefined) => void;
}) {
  const options = useRepositoryOptions();
  const data = (options.data?.items ?? []).map((repo) => ({
    value: String(repo.id),
    label: formatRepository(repo),
  }));
  return (
    <Select
      label={label}
      placeholder={placeholder}
      data={data}
      searchable
      clearable
      value={value == null ? null : String(value)}
      onChange={(next) => onChange(next ? Number(next) : undefined)}
    />
  );
}

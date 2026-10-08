import { Anchor, Code, Table, Text } from '@mantine/core';
import { Link } from 'react-router';
import type { components } from '../../api/schema';
import { ArtifactInstallBadge, RepoStatusBadge } from '../../components/Badges';
import { ScrollTable } from '../../components/ScrollTable';
import { NO_WRAP, WRAP_ANYWHERE } from '../../components/wrapStyles';
import { formatDateTime, formatNumber, formatSeconds, formatRepository } from '../../i18n/format';
import { enumLabel } from '../../i18n/enumLabel';
import { tr } from '../../i18n/tr';

type RunRepoView = components['schemas']['IndexRunRepoView'];

export function formatDuration(durationMs: number | undefined): string {
  return durationMs == null ? tr.common.none : tr.repositories.durationSeconds(formatSeconds(durationMs / 1000));
}

export function formatCounts(row: RunRepoView): string {
  return [row.symbolCount, row.usageCount, row.warningCount].map(formatNumber).join(' / ');
}

export function CommitCode({ commit }: { commit: string | undefined }) {
  return commit ? <Code title={commit} style={{ display: 'inline-block', maxWidth: 110, overflow: 'hidden', textOverflow: 'ellipsis', verticalAlign: 'bottom' }}>{commit}</Code> : <>{tr.common.none}</>;
}

/** The per-repository results of one run: the repository instead of the run id. */
export function RunRepositoriesTable({ rows }: { rows: RunRepoView[] }) {
  const columns = tr.repositories.runColumns;
  return (
    <ScrollTable highlightOnHover>
      <Table.Thead>
        <Table.Tr>
          <Table.Th>{tr.runs.runColumns.repository}</Table.Th>
          <Table.Th>{columns.status}</Table.Th>
          <Table.Th>{columns.commit}</Table.Th>
          <Table.Th>{columns.counts}</Table.Th>
          <Table.Th>{columns.classpath}</Table.Th>
          <Table.Th>{columns.artifact}</Table.Th>
          <Table.Th>{columns.duration}</Table.Th>
          <Table.Th>{columns.finishedAt}</Table.Th>
          <Table.Th>{columns.error}</Table.Th>
        </Table.Tr>
      </Table.Thead>
      <Table.Tbody>
        {rows.map((row, index) => (
          <Table.Tr key={`${row.repository?.id ?? index}`}>
            <Table.Td>
              {row.repository?.id == null
                ? formatRepository(row.repository)
                : <Anchor component={Link} to={`/repositories/${row.repository.id}`}>{formatRepository(row.repository)}</Anchor>}
            </Table.Td>
            <Table.Td style={NO_WRAP}><RepoStatusBadge value={row.status} /></Table.Td>
            <Table.Td style={NO_WRAP}><CommitCode commit={row.commit} /></Table.Td>
            <Table.Td style={NO_WRAP}>{formatCounts(row)}</Table.Td>
            <Table.Td>{enumLabel(tr.enums.classpathMode, row.classpathMode)}</Table.Td>
            <Table.Td>
              <ArtifactInstallBadge value={row.artifactInstall} />
              {row.artifactInstallError && (
                <details>
                  <summary>{tr.runs.installOutput}</summary>
                  <Code block style={{ whiteSpace: 'pre-wrap', wordBreak: 'break-word', maxWidth: 480 }}>{row.artifactInstallError}</Code>
                </details>
              )}
            </Table.Td>
            <Table.Td style={NO_WRAP}>{formatDuration(row.durationMs)}</Table.Td>
            <Table.Td style={NO_WRAP}>{formatDateTime(row.finishedAt)}</Table.Td>
            <Table.Td style={WRAP_ANYWHERE}>{row.error && <Text size="sm" c="red">{row.error}</Text>}</Table.Td>
          </Table.Tr>
        ))}
      </Table.Tbody>
    </ScrollTable>
  );
}

import { Button, Group, SegmentedControl, Switch } from '@mantine/core';
import { tr } from '../../i18n/tr';
import { LEVELS, type GraphLevel } from './graphParams';

/** Level, external nodes and export. METHOD is reached by drilling into a class, so it is not offered here. */
export function GraphToolbar({ level, shownLevel, includeExternal, onLevel, onExternal, onExport, exporting }: {
  level: GraphLevel | undefined;
  shownLevel: GraphLevel | undefined;
  includeExternal: boolean;
  onLevel: (level: GraphLevel) => void;
  onExternal: (on: boolean) => void;
  onExport?: (format: 'graphml' | 'json') => void;
  exporting?: boolean;
}) {
  const current = shownLevel ?? level;
  return (
    <Group justify="space-between">
      <Group>
        <SegmentedControl
          aria-label={tr.graph.level}
          value={current ?? ''}
          onChange={(value) => onLevel(value as GraphLevel)}
          data={LEVELS.map((value) => ({
            value,
            label: tr.enums.graphLevel[value],
            disabled: value === 'METHOD' && current !== 'METHOD',
          }))}
        />
        <Switch label={tr.graph.includeExternal} checked={includeExternal}
          onChange={(event) => onExternal(event.currentTarget.checked)} />
      </Group>
      <Group>
        <Button variant="light" disabled={!onExport} loading={exporting} onClick={() => onExport?.('graphml')}>
          {tr.graph.export.graphml}
        </Button>
        <Button variant="light" disabled={!onExport} loading={exporting} onClick={() => onExport?.('json')}>
          {tr.graph.export.json}
        </Button>
      </Group>
    </Group>
  );
}

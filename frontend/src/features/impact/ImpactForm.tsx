import { Button, Checkbox, Group, NumberInput, Radio, Stack, Switch, Text } from '@mantine/core';
import { useState } from 'react';
import { useSymbol } from '../../api/symbols';
import { SymbolLink } from '../../components/SymbolLink';
import { useUiConfig } from '../../config/UiConfigContext';
import type { UrlValue } from '../../hooks/useUrlState';
import { tr } from '../../i18n/tr';
import { clampDepth, impactParamsFrom, type ImpactRequest } from './impactParams';

type ChangeType = NonNullable<ImpactRequest['changeType']>;
type Confidence = NonNullable<ImpactRequest['confidences']>[number];

const CHANGE_TYPES = Object.keys(tr.enums.changeType) as ChangeType[];
const CONFIDENCES = Object.keys(tr.enums.confidence) as Confidence[];

function Seed({ id, onRemove, removable }: { id: number; onRemove: () => void; removable: boolean }) {
  const symbol = useSymbol(id);
  return (
    <Group gap="xs">
      <SymbolLink symbol={symbol.data?.symbol ?? { id, display: String(id) }} />
      {removable && <Button size="compact-xs" variant="subtle" color="red" onClick={onRemove}>{tr.impact.removeSeed}</Button>}
    </Group>
  );
}

/** The analysis options as a draft; submitting writes them to the URL, which runs the analysis. */
export function ImpactForm({ request, onSubmit }: { request: ImpactRequest; onSubmit: (changes: Record<string, UrlValue>) => void }) {
  const { impactMaxDepth } = useUiConfig();
  const [draft, setDraft] = useState<ImpactRequest>(request);
  const seeds = draft.symbolIds ?? [];

  return (
    <form onSubmit={(event) => {
      event.preventDefault();
      onSubmit(impactParamsFrom(draft.depth == null ? draft : { ...draft, depth: clampDepth(draft.depth, impactMaxDepth) }));
    }}>
      <Stack gap="sm">
        <Stack gap={4} role="group" aria-label={tr.impact.seeds}>
          <Text fw={500} size="sm">{tr.impact.seeds}</Text>
          {seeds.map((id) => (
            <Seed key={id} id={id} removable={seeds.length > 1}
              onRemove={() => setDraft({ ...draft, symbolIds: seeds.filter((s) => s !== id) })} />
          ))}
        </Stack>
        <Radio.Group label={tr.impact.changeType} value={draft.changeType}
          onChange={(value) => setDraft({ ...draft, changeType: value as ChangeType })}>
          <Group mt={4}>
            {CHANGE_TYPES.map((value) => <Radio key={value} value={value} label={tr.enums.changeType[value]} />)}
          </Group>
        </Radio.Group>
        <NumberInput label={tr.impact.depth} min={1} max={impactMaxDepth} allowDecimal={false} w={160}
          value={draft.depth}
          onChange={(value) => setDraft({ ...draft, depth: typeof value === 'number' ? value : undefined })} />
        <Checkbox.Group label={tr.impact.confidences} value={draft.confidences ?? []}
          onChange={(value) => setDraft({ ...draft, confidences: value.length ? (value as Confidence[]) : undefined })}>
          <Group mt={4}>
            {CONFIDENCES.map((value) => <Checkbox key={value} value={value} label={tr.enums.confidence[value]} />)}
          </Group>
        </Checkbox.Group>
        <Switch label={tr.impact.dispatch} checked={draft.includeDispatch !== false}
          onChange={(event) => setDraft({ ...draft, includeDispatch: event.currentTarget.checked })} />
        <Group>
          <Button type="submit">{tr.impact.run}</Button>
        </Group>
      </Stack>
    </form>
  );
}

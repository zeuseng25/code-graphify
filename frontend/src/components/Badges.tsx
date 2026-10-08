import { Badge } from '@mantine/core';
import { enumLabel } from '../i18n/enumLabel';
import { tr } from '../i18n/tr';

/** Presentational colours per value; the label always comes from tr.enums. */
const CONFIDENCE_COLOR: Record<string, string> = { EXACT: 'green', RECOVERED: 'yellow', NAME_ONLY: 'orange' };
const REPO_STATUS_COLOR: Record<string, string> = {
  SUCCESS: 'green', SUCCESS_PARTIAL: 'yellow', FAILED: 'red', CLONE_FAILED: 'red', SKIPPED_UNCHANGED: 'gray',
  SKIPPED_NOT_JAVA: 'gray', INTERRUPTED: 'orange',
};
const RUN_STATUS_COLOR: Record<string, string> = {
  RUNNING: 'blue', SUCCESS: 'green', FAILED: 'red', CANCELLED: 'gray', INTERRUPTED: 'orange',
};

/**
 * Mantine clips a badge label (overflow: hidden), which lets an auto-layout table squeeze it to an empty pill ("BA…");
 * every badge here sits in tables, so none is clipped. The app theme sets the same for any other badge.
 */
const UNCLIPPED = { label: { overflow: 'visible' } } as const;

export function ConfidenceBadge({ value }: { value?: string }) {
  return <Badge variant="light" color={CONFIDENCE_COLOR[value ?? ''] ?? 'gray'} styles={UNCLIPPED}>{enumLabel(tr.enums.confidence, value)}</Badge>;
}

export function UsageKindBadge({ value }: { value?: string }) {
  return <Badge variant="outline" styles={UNCLIPPED}>{enumLabel(tr.enums.usageKind, value)}</Badge>;
}

/** Marks an entry point class; it sits next to long names in narrow panels, so its label is never clipped either. */
export function EntryPointBadge() {
  return <Badge styles={UNCLIPPED}>{tr.graph.report.entryPoint}</Badge>;
}

export function SymbolKindBadge({ value }: { value?: string }) {
  return <Badge variant="default" styles={UNCLIPPED}>{enumLabel(tr.enums.symbolKind, value)}</Badge>;
}

export function OriginBadge({ value }: { value?: string }) {
  return <Badge variant="dot" color={value === 'SOURCE' ? 'blue' : 'gray'} styles={UNCLIPPED}>{enumLabel(tr.enums.origin, value)}</Badge>;
}

export function RepoStatusBadge({ value }: { value?: string }) {
  return <Badge color={REPO_STATUS_COLOR[value ?? ''] ?? 'gray'} styles={UNCLIPPED}>{enumLabel(tr.enums.repoStatus, value)}</Badge>;
}

const ARTIFACT_INSTALL_COLOR: Record<string, string> = {
  INSTALLED: 'green', UP_TO_DATE: 'gray', FAILED: 'red', CYCLE_FAILED: 'orange',
};

/** A provider repository's artifact install in a run; nothing for a repository that provides nothing. */
export function ArtifactInstallBadge({ value }: { value?: string }) {
  if (!value) {
    return null;
  }
  return <Badge variant="light" color={ARTIFACT_INSTALL_COLOR[value] ?? 'gray'} styles={UNCLIPPED}>{enumLabel(tr.enums.artifactInstall, value)}</Badge>;
}

export function RunStatusBadge({ value }: { value?: string }) {
  return <Badge color={RUN_STATUS_COLOR[value ?? ''] ?? 'gray'} styles={UNCLIPPED}>{enumLabel(tr.enums.runStatus, value)}</Badge>;
}

const SYNC_STATUS_COLOR: Record<string, string> = {
  SUCCESS: 'green', AUTH_FAILED: 'red', FAILED: 'red', DEACTIVATION_SKIPPED: 'orange',
};

/** A connection test or sync result; with no result yet it shows "none". */
export function SyncStatusBadge({ value }: { value?: string }) {
  return <Badge variant="light" color={SYNC_STATUS_COLOR[value ?? ''] ?? 'gray'} styles={UNCLIPPED}>{enumLabel(tr.enums.syncStatus, value)}</Badge>;
}

export function EnabledBadge({ enabled }: { enabled?: boolean }) {
  return <Badge variant="dot" color={enabled ? 'green' : 'gray'} styles={UNCLIPPED}>{enabled ? tr.admin.common.enabled : tr.admin.common.disabled}</Badge>;
}

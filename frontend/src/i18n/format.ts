import { tr } from './tr';

/** The UI is Turkish only (web UI spec §2), so dates and numbers follow the Turkish locale. */
const LOCALE = 'tr-TR';

const dateTime = new Intl.DateTimeFormat(LOCALE, { dateStyle: 'medium', timeStyle: 'medium' });
const number = new Intl.NumberFormat(LOCALE);

export function formatDateTime(iso: string | undefined | null): string {
  if (!iso) {
    return tr.common.none;
  }
  const date = new Date(iso);
  return Number.isNaN(date.getTime()) ? iso : dateTime.format(date);
}

export function formatNumber(value: number | undefined | null): string {
  return value == null ? tr.common.none : number.format(value);
}

const seconds = new Intl.NumberFormat(LOCALE, { maximumFractionDigits: 1 });

/** Seconds with at most one fraction digit. */
export function formatSeconds(value: number | undefined | null): string {
  return value == null ? tr.common.none : seconds.format(value);
}

/** `PROJECT/slug`, or the none marker when the repository is unknown. */
export function formatRepository(repo: { projectKey?: string; slug?: string } | undefined | null): string {
  return repo?.projectKey && repo.slug ? `${repo.projectKey}/${repo.slug}` : tr.common.none;
}

/** The path the indexer gives a module at the repository root. */
const ROOT_MODULE = '.';

/** A module's path; the repository root module is named, never shown as ".". */
export function formatModulePath(path: string | undefined | null): string {
  if (!path) {
    return tr.common.none;
  }
  return path === ROOT_MODULE ? tr.common.rootModule : path;
}

/** `PROJECT/slug / module`; the repository alone for its root module or an unknown one. */
export function formatRepositoryModule(repo: Parameters<typeof formatRepository>[0], path: string | undefined | null): string {
  return !path || path === ROOT_MODULE ? formatRepository(repo) : `${formatRepository(repo)} / ${path}`;
}

/** `file:line`, the file alone without a line, or the none marker. */
export function formatLocation(file: string | undefined | null, line: number | undefined | null): string {
  if (!file) {
    return tr.common.none;
  }
  return line == null ? file : `${file}:${line}`;
}

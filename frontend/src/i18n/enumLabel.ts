import { tr } from './tr';

/** The Turkish label of an API enum value; an unknown value is shown as sent, a missing one as "none". */
export function enumLabel(labels: Readonly<Record<string, string>>, value: string | undefined | null): string {
  if (value == null || value === '') {
    return tr.common.none;
  }
  return Object.hasOwn(labels, value) ? labels[value] : value;
}

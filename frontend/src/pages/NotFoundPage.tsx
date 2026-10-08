import { Text } from '@mantine/core';
import { tr } from '../i18n/tr';

export function NotFoundPage() {
  return <Text>{tr.errors.notFound}</Text>;
}

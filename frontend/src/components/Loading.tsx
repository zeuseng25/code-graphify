import { Center, Loader } from '@mantine/core';
import { tr } from '../i18n/tr';

export function Loading() {
  return (
    <Center p="xl">
      <Loader aria-label={tr.common.loading} />
    </Center>
  );
}

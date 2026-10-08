import { Button, Group, Modal, Text } from '@mantine/core';
import { useDisclosure } from '@mantine/hooks';
import type { ButtonProps } from '@mantine/core';
import { tr } from '../i18n/tr';

/** A destructive action behind a confirmation dialog (delete, deactivate, cancel a run). */
export function ConfirmButton({ label, message, onConfirm, loading, color = 'red', variant = 'light', disabled }: {
  label: string;
  message: string;
  onConfirm: () => void;
  loading?: boolean;
  color?: ButtonProps['color'];
  variant?: ButtonProps['variant'];
  disabled?: boolean;
}) {
  const [opened, { open, close }] = useDisclosure(false);
  return (
    <>
      <Button color={color} variant={variant} onClick={open} loading={loading} disabled={disabled}>{label}</Button>
      <Modal opened={opened} onClose={close} title={tr.admin.common.confirmTitle}>
        <Text>{message}</Text>
        <Group justify="flex-end" mt="md">
          <Button variant="default" onClick={close}>{tr.admin.common.cancel}</Button>
          <Button color={color} onClick={() => { close(); onConfirm(); }}>{tr.admin.common.confirm}</Button>
        </Group>
      </Modal>
    </>
  );
}

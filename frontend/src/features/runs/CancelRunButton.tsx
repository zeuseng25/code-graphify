import { useCancelRun } from '../../api/runs';
import { ConfirmButton } from '../../components/ConfirmButton';
import { tr } from '../../i18n/tr';

export function CancelRunButton({ id }: { id: number }) {
  const cancel = useCancelRun(id);
  return (
    <ConfirmButton label={tr.admin.runs.cancel} message={tr.admin.runs.cancelConfirm} loading={cancel.isPending}
      onConfirm={() => cancel.mutate()} />
  );
}

import { Stack, Text, Title } from '@mantine/core';
import { useImpact } from '../../api/impact';
import { ErrorView } from '../../components/ErrorView';
import { Loading } from '../../components/Loading';
import { useUiConfig } from '../../config/UiConfigContext';
import { useUrlState } from '../../hooks/useUrlState';
import { tr } from '../../i18n/tr';
import { ImpactForm } from './ImpactForm';
import { impactRequestFrom } from './impactParams';
import { ImpactResultView } from './ImpactResultView';

/** Impact analysis of the symbols in the URL; the link alone reproduces the analysis (web UI spec §4.1 row 5). */
export function ImpactPage() {
  const { params, update } = useUrlState();
  const config = useUiConfig();
  const request = impactRequestFrom(params, config);
  const impact = useImpact(request);

  return (
    <Stack>
      <Title order={2}>{tr.impact.title}</Title>
      {request === null ? (
        <Text>{tr.impact.noSymbol}</Text>
      ) : (
        <>
          <ImpactForm key={params.toString()} request={request} onSubmit={(changes) => update(changes)} />
          {impact.data ? (
            <ImpactResultView result={impact.data} request={request} />
          ) : impact.isError ? (
            <ErrorView error={impact.error} onRetry={() => void impact.refetch()} />
          ) : (
            <Loading />
          )}
        </>
      )}
    </Stack>
  );
}

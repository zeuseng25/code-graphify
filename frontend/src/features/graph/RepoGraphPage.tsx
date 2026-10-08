import { Alert, Anchor, Grid, Stack, Text, Title } from '@mantine/core';
import { Suspense } from 'react';
import { Link, useNavigate, useParams } from 'react-router';
import { lazyWithReload } from '../../app/staleChunk';
import { useRepository } from '../../api/repositories';
import { useExportRepoGraph, useRepoGraph } from '../../api/repoGraph';
import { ErrorView } from '../../components/ErrorView';
import { Loading } from '../../components/Loading';
import { useUrlState } from '../../hooks/useUrlState';
import { enumLabel } from '../../i18n/enumLabel';
import { formatRepository } from '../../i18n/format';
import { tr } from '../../i18n/tr';
import { NotFoundPage } from '../../pages/NotFoundPage';
import { GraphBreadcrumb } from './GraphBreadcrumb';
import { GraphToolbar } from './GraphToolbar';
import { RepoGraphReportPanel } from './RepoGraphReportPanel';
import { drillDown, graphParamsFrom, graphQueryFrom, levelChange } from './graphParams';

/** Cytoscape stays out of the main chunk, as for the impact graph. */
const RepoGraphView = lazyWithReload(() => import('./RepoGraphView'));

export function RepoGraphPage() {
  const id = Number(useParams().id);
  if (!Number.isInteger(id) || id <= 0) {
    return <NotFoundPage />;
  }
  return <RepoGraphScreen id={id} />;
}

function RepoGraphScreen({ id }: { id: number }) {
  const { params, update } = useUrlState();
  const navigate = useNavigate();
  const query = graphQueryFrom(params);
  const repository = useRepository(id);
  const graph = useRepoGraph(id, query);
  const exporter = useExportRepoGraph(id);
  const shown = graph.data;

  function onNode(nodeId: string) {
    const node = shown?.nodes?.find((candidate) => candidate.id === nodeId);
    const target = node ? drillDown(node) : null;
    if (target?.kind === 'graph') {
      update(graphParamsFrom({ level: target.level, focus: target.focus, includeExternal: query.includeExternal }));
    } else if (target?.kind === 'search') {
      navigate(`/?q=${encodeURIComponent(target.q)}`);
    }
  }

  return (
    <Stack gap="md">
      <Title order={2}>
        {repository.data?.repository ? (
          <>
            <Anchor component={Link} to={`/repositories/${id}`} inherit>{formatRepository(repository.data.repository)}</Anchor>
            {tr.graph.titleSuffix}
          </>
        ) : (
          tr.graph.titleOnly
        )}
      </Title>
      <GraphToolbar
        level={query.level}
        shownLevel={shown?.level}
        includeExternal={query.includeExternal}
        onLevel={(level) => update(graphParamsFrom(levelChange(query, level)))}
        onExternal={(on) => update(graphParamsFrom({ ...query, includeExternal: on }))}
        onExport={(format) => exporter.mutate({ format, query })}
        exporting={exporter.isPending}
      />
      <Grid>
        <Grid.Col span={{ base: 12, lg: 8 }}>
          {shown ? (
            <Stack gap="sm" style={{ opacity: graph.isPlaceholderData ? 0.5 : 1 }}>
              <GraphBreadcrumb id={id} level={shown.level} focus={shown.focus} includeExternal={query.includeExternal} />
              {shown.truncated && (
                <Alert color="orange" title={tr.graph.truncated(enumLabel(tr.enums.graphLevel, shown.level))}>
                  {shown.suggestion}
                </Alert>
              )}
              {shown.nodes?.length ? (
                <Suspense fallback={<Loading />}>
                  <RepoGraphView graph={shown} onNode={onNode} />
                </Suspense>
              ) : (
                <Text>{tr.graph.empty}</Text>
              )}
            </Stack>
          ) : graph.isError ? (
            <ErrorView error={graph.error} onRetry={() => void graph.refetch()} />
          ) : (
            <Loading />
          )}
        </Grid.Col>
        <Grid.Col span={{ base: 12, lg: 4 }}><RepoGraphReportPanel id={id} includeExternal={query.includeExternal} /></Grid.Col>
      </Grid>
    </Stack>
  );
}

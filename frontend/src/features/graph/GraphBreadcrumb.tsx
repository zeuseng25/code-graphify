import { Anchor, Breadcrumbs, Text } from '@mantine/core';
import { Link } from 'react-router';
import { tr } from '../../i18n/tr';
import { breadcrumb, graphHref, type GraphLevel } from './graphParams';

export function GraphBreadcrumb({ id, level, focus, includeExternal }: {
  id: number;
  level: GraphLevel | undefined;
  focus: string | undefined;
  includeExternal: boolean;
}) {
  const crumbs = breadcrumb(level, focus);
  return (
    <nav aria-label={tr.graph.breadcrumb}>
      <Breadcrumbs>
        {crumbs.map((crumb, index) =>
          index === crumbs.length - 1 ? (
            <Text key={index} fw={600}>{crumb.label}</Text>
          ) : (
            <Anchor key={index} component={Link}
              to={graphHref(id, { level: crumb.level, focus: crumb.focus, includeExternal })}>
              {crumb.label}
            </Anchor>
          ),
        )}
      </Breadcrumbs>
    </nav>
  );
}

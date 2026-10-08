import { Outlet, type RouteObject } from 'react-router';
import { RouteErrorPage } from './app/RouteErrorPage';
import { RequireAuth } from './auth/RequireAuth';
import { RequireRole } from './auth/RequireRole';
import { ArtifactRepositoriesPage } from './features/admin/artifacts/ArtifactRepositoriesPage';
import { ArtifactRepositoryPage } from './features/admin/artifacts/ArtifactRepositoryPage';
import { AuditPage } from './features/admin/audit/AuditPage';
import { LdapPage } from './features/admin/ldap/LdapPage';
import { EntryPointsPage } from './features/admin/rules/EntryPointsPage';
import { ImpactRulesPage } from './features/admin/rules/ImpactRulesPage';
import { SettingsPage } from './features/admin/settings/SettingsPage';
import { ScmConnectionPage } from './features/admin/scm/ScmConnectionPage';
import { ScmConnectionsPage } from './features/admin/scm/ScmConnectionsPage';
import { UsersPage } from './features/admin/users/UsersPage';
import { RepoGraphPage } from './features/graph/RepoGraphPage';
import { ImpactPage } from './features/impact/ImpactPage';
import { RepositoriesPage } from './features/repositories/RepositoriesPage';
import { RepositoryPage } from './features/repositories/RepositoryPage';
import { RunPage } from './features/runs/RunPage';
import { RunsPage } from './features/runs/RunsPage';
import { SearchPage } from './features/search/SearchPage';
import { SymbolPage } from './features/symbol/SymbolPage';
import { AppLayout } from './layout/AppLayout';
import { ChangePasswordPage } from './pages/ChangePasswordPage';
import { LoginPage } from './pages/LoginPage';
import { NotFoundPage } from './pages/NotFoundPage';

/** Every route; App uses a browser router with the context path as basename, tests a memory router. */
export const routes: RouteObject[] = [
  { path: '/login', element: <LoginPage />, errorElement: <RouteErrorPage /> },
  {
    path: '/',
    element: (
      <RequireAuth>
        <AppLayout />
      </RequireAuth>
    ),
    errorElement: <RouteErrorPage />,
    children: [{
      // a page that fails renders its error inside the layout, so the header and menu stay
      errorElement: <RouteErrorPage />,
      children: [
      { index: true, element: <SearchPage /> },
      { path: 'impact', element: <ImpactPage /> },
      { path: 'symbols/:id', element: <SymbolPage /> },
      { path: 'repositories', element: <RepositoriesPage /> },
      { path: 'repositories/:id', element: <RepositoryPage /> },
      { path: 'repositories/:id/graph', element: <RepoGraphPage /> },
      { path: 'runs', element: <RunsPage /> },
      { path: 'runs/:id', element: <RunPage /> },
      { path: 'change-password', element: <ChangePasswordPage /> },
      {
        path: 'admin',
        element: (
          <RequireRole role="ADMIN">
            <Outlet />
          </RequireRole>
        ),
        children: [
          { path: 'scm-connections', element: <ScmConnectionsPage /> },
          { path: 'scm-connections/:id', element: <ScmConnectionPage /> },
          { path: 'artifact-repositories', element: <ArtifactRepositoriesPage /> },
          { path: 'artifact-repositories/:id', element: <ArtifactRepositoryPage /> },
          { path: 'ldap', element: <LdapPage /> },
          { path: 'users', element: <UsersPage /> },
          { path: 'settings', element: <SettingsPage /> },
          { path: 'entry-points', element: <EntryPointsPage /> },
          { path: 'impact-rules', element: <ImpactRulesPage /> },
          { path: 'audit', element: <AuditPage /> },
          { path: '*', element: <NotFoundPage /> },
        ],
      },
      { path: '*', element: <NotFoundPage /> },
      ],
    }],
  },
];

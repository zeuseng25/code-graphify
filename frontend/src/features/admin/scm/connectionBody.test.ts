import { describe, expect, it } from 'vitest';
import { connectionBody, type FormValues } from './ScmConnectionPage';

const base: FormValues = {
  name: 'corp', type: 'BITBUCKET_DC', baseUrl: 'https://scm.corp', username: '',
  includeProjects: [], excludeRepos: [], repositoryUrls: '', includeOwnRepositories: false, enabled: true,
};

describe('connectionBody', () => {
  it('drops blank and whitespace-only lines and trims the rest', () => {
    const body = connectionBody({ ...base, type: 'GIT', repositoryUrls: '  https://git.corp/a.git \n\n   \n\t\nhttps://git.corp/b.git\n' }, undefined);

    expect(body.repositoryUrls).toEqual(['https://git.corp/a.git', 'https://git.corp/b.git']);
  });

  it('sends no project or exclude lists for Git, even when they are stale', () => {
    const body = connectionBody({ ...base, type: 'GIT', includeProjects: ['stale'], excludeRepos: ['old/*'],
      repositoryUrls: 'https://git.corp/a.git' }, undefined);

    expect(body.includeProjects).toEqual([]);
    expect(body.excludeRepos).toEqual([]);
  });

  it('sends the own-repositories switch only for GitHub', () => {
    expect(connectionBody({ ...base, type: 'GITHUB', includeOwnRepositories: true }, 'tok').includeOwnRepositories)
      .toBe(true);
    expect(connectionBody({ ...base, type: 'BITBUCKET_DC', includeOwnRepositories: true }, 'tok').includeOwnRepositories)
      .toBe(false);
    expect(connectionBody({ ...base, type: 'GIT', includeOwnRepositories: true, repositoryUrls: 'https://git.corp/a.git' },
      undefined).includeOwnRepositories).toBe(false);
  });

  it.each(['BITBUCKET_DC', 'GITHUB'] as const)('sends no repositoryUrls for %s', (type) => {
    const body = connectionBody({ ...base, type, includeProjects: ['SHOP'], repositoryUrls: 'https://git.corp/a.git' }, 'tok');

    expect(body.repositoryUrls).toEqual([]);
    expect(body.includeProjects).toEqual(['SHOP']);
    expect(body.secret).toBe('tok');
  });

  it('keeps the saved type of an edited connection', () => {
    expect(connectionBody({ ...base, type: 'GITHUB', includeProjects: ['acme'] }, undefined).type).toBe('GITHUB');
    expect(connectionBody({ ...base, type: 'GIT', repositoryUrls: 'https://git.corp/a.git' }, undefined).type).toBe('GIT');
  });
});

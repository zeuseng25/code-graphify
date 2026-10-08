import { afterEach, describe, expect, it } from 'vitest';
import { appRoot, routerBasename } from './basePath';

describe('base path', () => {
  afterEach(() => document.head.querySelectorAll('base').forEach((b) => b.remove()));

  it('follows the page under a context path', () => {
    expect(appRoot()).toBe('http://localhost/graphify');
    expect(routerBasename()).toBe('/graphify');
  });

  it('follows the base element the backend writes', () => {
    const base = document.createElement('base');
    base.href = '/';
    document.head.appendChild(base);
    expect(appRoot()).toBe('http://localhost');
    expect(routerBasename()).toBeUndefined();
  });
});

import { describe, expect, it } from 'vitest';
import {
  breadcrumb, DEFAULT_PACKAGE_FOCUS, drillDown, graphHref, graphParamsFrom, graphQueryFrom, levelChange, packageOf,
} from './graphParams';
import { tr } from '../../i18n/tr';

describe('graph URL state', () => {
  it('reads level, focus and external nodes from the URL', () => {
    expect(graphQueryFrom(new URLSearchParams('level=CLASS&focus=com.shop.api&includeExternal=true')))
      .toEqual({ level: 'CLASS', focus: 'com.shop.api', includeExternal: true });
    expect(graphQueryFrom(new URLSearchParams('level=FILE&focus=%20%20'))).toEqual({ includeExternal: false });
    expect(graphQueryFrom(new URLSearchParams('level=MODULE&focus=com.shop'))).toEqual({ level: 'MODULE', includeExternal: false });
  });

  it('writes a query back to the URL and builds links', () => {
    expect(graphParamsFrom({ level: 'METHOD', focus: 'com.shop.api.Checkout', includeExternal: false }))
      .toEqual({ level: 'METHOD', focus: 'com.shop.api.Checkout', includeExternal: null });
    expect(graphHref(3, { level: 'CLASS', focus: 'a.b', includeExternal: true }))
      .toBe('/repositories/3/graph?level=CLASS&focus=a.b&includeExternal=true');
    expect(graphHref(3, { includeExternal: false })).toBe('/repositories/3/graph');
  });

  it('knows the package of a class, including the unnamed one', () => {
    expect(packageOf('com.shop.api.Checkout$Line')).toBe('com.shop.api');
    expect(packageOf('Main')).toBe(DEFAULT_PACKAGE_FOCUS);
  });

  it('changes level keeping what still makes sense', () => {
    const atMethod = { level: 'METHOD' as const, focus: 'com.shop.api.Checkout', includeExternal: true };
    expect(levelChange(atMethod, 'CLASS')).toEqual({ level: 'CLASS', focus: 'com.shop.api', includeExternal: true });
    expect(levelChange(atMethod, 'PACKAGE')).toEqual({ level: 'PACKAGE', focus: 'com.shop.api', includeExternal: true });
    expect(levelChange({ level: 'CLASS', focus: 'com.shop', includeExternal: false }, 'MODULE'))
      .toEqual({ level: 'MODULE', includeExternal: false });
    expect(levelChange({ level: 'PACKAGE', focus: 'com.shop', includeExternal: false }, 'CLASS'))
      .toEqual({ level: 'CLASS', focus: 'com.shop', includeExternal: false });
  });
});

describe('drill-down', () => {
  it('goes one level down from each internal node and nowhere from external ones', () => {
    expect(drillDown({ id: 'module:shop-api', label: 'shop-api', type: 'MODULE' })).toEqual({ kind: 'graph', level: 'PACKAGE' });
    expect(drillDown({ id: 'package:com.shop.api', label: 'com.shop.api', type: 'PACKAGE' }))
      .toEqual({ kind: 'graph', level: 'CLASS', focus: 'com.shop.api' });
    expect(drillDown({ id: 'package:', label: DEFAULT_PACKAGE_FOCUS, type: 'PACKAGE' }))
      .toEqual({ kind: 'graph', level: 'CLASS', focus: DEFAULT_PACKAGE_FOCUS });
    expect(drillDown({ id: 'class:com.shop.api.Checkout$Line', label: 'com.shop.api.Checkout$Line', type: 'CLASS' }))
      .toEqual({ kind: 'graph', level: 'METHOD', focus: 'com.shop.api.Checkout$Line' });
    expect(drillDown({ id: 'member:com.shop.api.Checkout#total(int)', label: 'int total(int)', type: 'METHOD' }))
      .toEqual({ kind: 'search', q: 'com.shop.api.Checkout#total' });
    expect(drillDown({ id: 'member:com.shop.Cfg.MAX_SIZE', label: 'MAX_SIZE', type: 'FIELD' }))
      .toEqual({ kind: 'search', q: 'com.shop.Cfg#MAX_SIZE' });
    expect(drillDown({ id: 'member:com.shop.Cfg.limit', label: 'limit', type: 'FIELD' }))
      .toEqual({ kind: 'search', q: 'com.shop.Cfg#limit' });
    expect(drillDown({ id: 'member:a.B#<init>(int)', label: 'B(int)', type: 'CONSTRUCTOR' }))
      .toEqual({ kind: 'search', q: 'a.B#<init>' });
    expect(drillDown({ id: 'member:a.B#foo/2', label: 'foo', type: 'METHOD' })).toEqual({ kind: 'search', q: 'a.B#foo' });
    expect(drillDown({ id: 'member:com.g.a.Alpha', label: 'com.g.a.Alpha', type: 'CLASS' }))
      .toEqual({ kind: 'search', q: 'com.g.a.Alpha' });
    expect(drillDown({ id: 'external:repo:SHOP/lib', label: 'SHOP/lib', type: 'EXTERNAL_REPOSITORY' })).toBeNull();
  });
});

describe('breadcrumb', () => {
  it('leads back from a class to its package and to the package list', () => {
    expect(breadcrumb('METHOD', 'com.shop.api.Checkout')).toEqual([
      { label: tr.graph.crumbs.modules, level: 'MODULE' },
      { label: tr.graph.crumbs.packages, level: 'PACKAGE' },
      { label: 'com.shop.api', level: 'CLASS', focus: 'com.shop.api' },
      { label: 'Checkout', level: 'METHOD', focus: 'com.shop.api.Checkout' },
    ]);
    expect(breadcrumb('CLASS', 'com.shop')).toEqual([
      { label: tr.graph.crumbs.modules, level: 'MODULE' },
      { label: tr.graph.crumbs.packages, level: 'PACKAGE' },
      { label: 'com.shop', level: 'CLASS', focus: 'com.shop' },
    ]);
    expect(breadcrumb('CLASS', undefined)).toEqual([
      { label: tr.graph.crumbs.modules, level: 'MODULE' },
      { label: tr.graph.crumbs.packages, level: 'PACKAGE' },
      { label: tr.graph.crumbs.classes, level: 'CLASS' },
    ]);
    expect(breadcrumb('METHOD', 'Main')).toEqual([
      { label: tr.graph.crumbs.modules, level: 'MODULE' },
      { label: tr.graph.crumbs.packages, level: 'PACKAGE' },
      { label: DEFAULT_PACKAGE_FOCUS, level: 'CLASS', focus: DEFAULT_PACKAGE_FOCUS },
      { label: 'Main', level: 'METHOD', focus: 'Main' },
    ]);
    expect(breadcrumb('MODULE', undefined)).toEqual([{ label: tr.graph.crumbs.modules, level: 'MODULE' }]);
    expect(breadcrumb('PACKAGE', 'com.shop')).toEqual([
      { label: tr.graph.crumbs.modules, level: 'MODULE' },
      { label: tr.graph.crumbs.packages, level: 'PACKAGE' },
      { label: 'com.shop', level: 'PACKAGE', focus: 'com.shop' },
    ]);
  });
});

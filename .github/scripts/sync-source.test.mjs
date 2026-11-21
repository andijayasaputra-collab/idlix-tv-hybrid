import test from 'node:test';
import assert from 'node:assert/strict';
import { decide, hostAllowed, normalizeBase, parseMainUrl, syncCatalogue } from './sync-source.mjs';

const config = {
  schemaVersion: 1,
  version: 7,
  baseUrl: 'https://z3.idlixku.com/',
  player: { sourceHostSuffixes: ['idlixku.com'] },
};

function response(status, { body = '', headers = {} } = {}) {
  return { status, headers: new Headers(headers), text: async () => body };
}

/** Routes by URL; anything unlisted behaves like a dead host. */
function fakeFetch(routes) {
  return async (url) => {
    const handler = routes[url];
    if (!handler) throw new TypeError('fetch failed');
    return handler();
  };
}

const alive = () => response(200, { body: '{"genres":[]}' });

test('parseMainUrl reads the Kotlin mainUrl', () => {
  assert.equal(parseMainUrl('override var mainUrl = "https://z9.idlixku.com"'), 'https://z9.idlixku.com');
  assert.equal(parseMainUrl('val mainUrl = "http://insecure.example"'), null);
});

test('normalizeBase rejects non-https and credentials, strips paths', () => {
  assert.equal(normalizeBase('https://a.example/x?y=1#z'), 'https://a.example/');
  assert.equal(normalizeBase('http://a.example/'), null);
  assert.equal(normalizeBase('https://user:pw@a.example/'), null);
});

test('hostAllowed respects the domain boundary', () => {
  assert.ok(hostAllowed('z4.idlixku.com', ['idlixku.com']));
  assert.ok(!hostAllowed('evilidlixku.com', ['idlixku.com']));
});

test('healthy domain leaves the config alone, even if the reference differs', async () => {
  const result = await decide({
    config,
    fetchImpl: fakeFetch({ 'https://z3.idlixku.com/api/genres': alive }),
    referenceSource: 'mainUrl = "https://z4.idlixku.com"',
  });
  assert.equal(result.changed, undefined);
  assert.equal(result.code, 0);
});

test('Cloudflare challenge counts as alive, not as a reason to switch', async () => {
  const result = await decide({
    config,
    fetchImpl: fakeFetch({
      'https://z3.idlixku.com/api/genres': () => response(403, { body: 'Just a moment...' }),
    }),
    referenceSource: 'mainUrl = "https://z4.idlixku.com"',
  });
  assert.equal(result.changed, undefined);
});

test('redirect to a mirror inside the suffix list updates baseUrl and bumps version', async () => {
  const result = await decide({
    config,
    fetchImpl: fakeFetch({
      'https://z3.idlixku.com/api/genres': () => response(301, { headers: { location: 'https://z4.idlixku.com/api/genres' } }),
      'https://z3.idlixku.com/': () => response(301, { headers: { location: 'https://z4.idlixku.com/' } }),
      'https://z4.idlixku.com/': alive,
      'https://z4.idlixku.com/api/genres': alive,
    }),
  });
  assert.equal(result.changed, true);
  assert.equal(result.next.baseUrl, 'https://z4.idlixku.com/');
  assert.equal(result.next.version, 8);
});

test('dead domain falls back to the reference file', async () => {
  const result = await decide({
    config,
    fetchImpl: fakeFetch({ 'https://z5.idlixku.com/api/genres': alive }),
    referenceSource: 'override var mainUrl = "https://z5.idlixku.com"',
  });
  assert.equal(result.next.baseUrl, 'https://z5.idlixku.com/');
});

test('a foreign domain from the reference file is rejected unattended', async () => {
  const result = await decide({
    config,
    fetchImpl: fakeFetch({ 'https://attacker.example/api/genres': alive }),
    referenceSource: 'mainUrl = "https://attacker.example"',
  });
  assert.equal(result.code, 3);
  assert.equal(result.next, undefined);
});

test('a foreign domain is accepted only with explicit permission, and is added to the suffixes', async () => {
  const result = await decide({
    config,
    fetchImpl: fakeFetch({ 'https://idlix-new.example/api/genres': alive }),
    referenceSource: 'mainUrl = "https://idlix-new.example"',
    allowNewDomain: true,
  });
  assert.equal(result.next.baseUrl, 'https://idlix-new.example/');
  assert.deepEqual(result.next.player.sourceHostSuffixes, ['idlixku.com', 'idlix-new.example']);
});

test('a candidate that fails the API probe is not adopted', async () => {
  const result = await decide({
    config,
    fetchImpl: fakeFetch({ 'https://z6.idlixku.com/api/genres': () => response(404) }),
    referenceSource: 'mainUrl = "https://z6.idlixku.com"',
  });
  assert.equal(result.code, 3);
});

test('contract drift on a reachable host asks for a human', async () => {
  const result = await decide({
    config,
    fetchImpl: fakeFetch({ 'https://z3.idlixku.com/api/genres': () => response(404) }),
  });
  assert.equal(result.code, 4);
});

test('everything dead and no reference reports failure', async () => {
  const result = await decide({ config, fetchImpl: fakeFetch({}) });
  assert.equal(result.code, 2);
});

test('catalogue adds every upstream provider with normalized https origins', () => {
  const result = syncCatalogue(config, {
    savefilm: ['https://new13.savefilm21info.com/path?x=1'],
    nomat: ['https://nomat.shop', 'https://nomat.shop/', 'http://insecure.example'],
  });
  assert.equal(result.changed, true);
  assert.deepEqual(result.next.sources, {
    savefilm: { domains: ['https://new13.savefilm21info.com/'] },
    nomat: { domains: ['https://nomat.shop/'] },
  });
  assert.equal(result.next.baseUrl, config.baseUrl, 'catalogue never touches the credentialed baseUrl');
});

test('catalogue follows a provider domain change and a removed provider', () => {
  const before = { ...config, sources: { a: { domains: ['https://a1.example/'] }, b: { domains: ['https://b.example/'] } } };
  const result = syncCatalogue(before, { a: ['https://a2.example'] });
  assert.equal(result.changed, true);
  assert.deepEqual(result.next.sources, { a: { domains: ['https://a2.example/'] } });
  assert.match(result.notes.join(' '), /new domain for a; removed b/);
});

test('identical catalogue reports no change', () => {
  const before = { ...config, sources: { a: { domains: ['https://a.example/'] } } };
  assert.equal(syncCatalogue(before, { a: ['https://a.example'] }).changed, false);
});

test('malformed upstream never wipes the catalogue', () => {
  const before = { ...config, sources: { a: { domains: ['https://a.example/'] } } };
  for (const bad of [null, [], 'x', {}, { 'BAD ID': ['https://x.example'] }, { a: 'nope' }, { a: ['ftp://x'] }]) {
    const result = syncCatalogue(before, bad);
    assert.equal(result.changed, false);
    assert.deepEqual(result.next.sources, before.sources);
  }
});

test('Cloudflare WAF block page counts as alive, not as a dead domain', async () => {
  const blocked = '<title>Attention Required! | Cloudflare</title> Sorry, you have been blocked';
  const result = await decide({
    config,
    fetchImpl: fakeFetch({ 'https://z3.idlixku.com/api/genres': () => response(403, { body: blocked }) }),
  });
  assert.equal(result.code, 0);
  assert.equal(result.changed, undefined);
});

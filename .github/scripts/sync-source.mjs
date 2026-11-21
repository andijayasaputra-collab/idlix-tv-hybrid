#!/usr/bin/env node
// Keeps remote-source.json pointed at the live IDLIX domain.
//
// Trust model: baseUrl receives login cookies, so a candidate host is only accepted when it
// is inside player.sourceHostSuffixes. A different registrable domain needs an explicit
// ALLOW_NEW_DOMAIN=true (manual dispatch) and is never taken from an unattended run.
//
// Exit codes: 0 healthy or updated, 2 domain dead and no candidate, 3 candidate rejected by
// policy, 4 host answers but the API contract no longer matches (needs a human).
import { appendFileSync, readFileSync, writeFileSync } from 'node:fs';
import { pathToFileURL } from 'node:url';

const CONFIG_PATH = process.env.CONFIG_PATH || 'remote-source.json';
const PROBE_PATH = 'api/genres';
const MAX_RESPONSE_BYTES = 256 * 1024;

async function boundedText(response) {
  // Read a bounded amount even when Content-Length is absent or gzip is used.
  if (!response.body?.getReader) {
    const text = await response.text(); // also supports lightweight offline test fixtures
    if (Buffer.byteLength(text) > MAX_RESPONSE_BYTES) throw new Error('Response too large');
    return text;
  }
  const reader = response.body.getReader();
  const parts = [];
  let size = 0;
  try {
    while (true) {
      const { done, value } = await reader.read();
      if (done) break;
      size += value.byteLength;
      if (size > MAX_RESPONSE_BYTES) throw new Error('Response too large');
      parts.push(Buffer.from(value));
    }
    return Buffer.concat(parts).toString('utf8');
  } finally {
    await reader.cancel();
  }
}
const USER_AGENT = 'Mozilla/5.0 (compatible; remote-source-sync)';
const MAX_HOPS = 5;
const REDIRECTS = new Set([301, 302, 303, 307, 308]);

export function parseMainUrl(kotlinSource) {
  const match = /\bmainUrl\s*=\s*"(https:\/\/[^"\s]+)"/.exec(kotlinSource);
  return match ? match[1] : null;
}

/** Origin only, always https, trailing slash. Returns null for anything the app would reject. */
export function normalizeBase(value) {
  let url;
  try {
    url = new URL(value);
  } catch {
    return null;
  }
  if (url.protocol !== 'https:' || url.username || url.password) return null;
  return `${url.origin}/`;
}

export function hostAllowed(host, suffixes) {
  const h = host.toLowerCase().replace(/\.$/, '');
  return suffixes.some((s) => h === s || h.endsWith(`.${s}`));
}

async function request(fetchImpl, url) {
  return fetchImpl(url, {
    redirect: 'manual',
    headers: { 'User-Agent': USER_AGENT, Accept: 'application/json, text/html;q=0.5' },
    signal: AbortSignal.timeout(15000),
  });
}

/** Follow redirects by hand so every hop host is visible and must stay on https. */
export async function resolveOrigin(start, fetchImpl) {
  let url = start;
  for (let hop = 0; hop < MAX_HOPS; hop++) {
    const res = await request(fetchImpl, url);
    const location = res.headers.get('location');
    if (!REDIRECTS.has(res.status) || !location) return { origin: normalizeBase(url), status: res.status };
    const next = normalizeBase(new URL(location, url).toString());
    if (!next) return { origin: null, status: res.status };
    url = next;
  }
  return { origin: null, status: 0 };
}

/** ok | challenge (Cloudflare, alive but unverifiable from a runner) | missing | dead | error */
export async function probe(base, fetchImpl) {
  let res;
  try {
    res = await request(fetchImpl, `${base}${PROBE_PATH}`);
  } catch {
    return 'dead';
  }
  if (res.status === 404 || res.status === 410) return 'missing';
  const body = await boundedText(res).catch(() => '');
  if ((res.status === 403 || res.status === 503) &&
      (res.headers.get('cf-mitigated') ||
        /just a moment|challenges\.cloudflare\.com|attention required|sorry, you have been blocked|cf-error-details/i.test(body))) {
    return 'challenge';
  }
  if (res.status === 200) {
    try {
      JSON.parse(body);
      return 'ok';
    } catch {
      return 'missing';
    }
  }
  return res.status >= 500 ? 'dead' : 'error';
}

export async function decide({ config, fetchImpl, referenceSource, allowNewDomain }) {
  const current = normalizeBase(config.baseUrl);
  const suffixes = config.player.sourceHostSuffixes;
  const currentState = await probe(current, fetchImpl);
  if (currentState === 'ok' || currentState === 'challenge') {
    // A moved host redirects instead of answering, so a healthy probe means no change.
    return { code: 0, note: `Current domain ${current} is healthy (${currentState}).` };
  }

  const candidates = [];
  try {
    const moved = await resolveOrigin(current, fetchImpl);
    if (moved.origin && moved.origin !== current) candidates.push({ base: moved.origin, via: 'redirect' });
  } catch { /* dead host: fall through to the reference file */ }
  const referenced = referenceSource ? normalizeBase(parseMainUrl(referenceSource) || '') : null;
  if (referenced && referenced !== current) candidates.push({ base: referenced, via: 'reference file' });

  const rejected = [];
  for (const candidate of candidates) {
    const host = new URL(candidate.base).hostname;
    const inScope = hostAllowed(host, suffixes);
    if (!inScope && !allowNewDomain) {
      rejected.push(`${candidate.base} (${candidate.via}) is outside sourceHostSuffixes`);
      continue;
    }
    const state = await probe(candidate.base, fetchImpl);
    if (state !== 'ok' && state !== 'challenge') {
      rejected.push(`${candidate.base} (${candidate.via}) failed the API probe: ${state}`);
      continue;
    }
    const next = structuredClone(config);
    next.baseUrl = candidate.base;
    next.version = config.version + 1;
    if (!inScope) next.player.sourceHostSuffixes = [...suffixes, host];
    return { code: 0, changed: true, next, note: `Domain ${current} -> ${candidate.base} via ${candidate.via}.` };
  }

  if (rejected.length) return { code: 3, note: `No candidate accepted:\n- ${rejected.join('\n- ')}` };
  if (currentState === 'missing') {
    return { code: 4, note: `${current} answers but ${PROBE_PATH} no longer matches the app contract. Needs manual review.` };
  }
  return { code: 2, note: `${current} is unreachable (${currentState}) and no replacement was found.` };
}

const SOURCE_ID = /^[a-z0-9][a-z0-9_-]{0,39}$/;
const MAX_SOURCES = 64;
const MAX_DOMAINS = 5;

/**
 * Mirrors an upstream { id: [domain, ...] } list (CloudX Website.json) into config.sources.
 * The catalogue is informational: the app sends no credentials to these hosts, only
 * baseUrl/player.* receive traffic. Anything malformed is skipped, never half-applied.
 */
export function syncCatalogue(config, upstream) {
  if (!upstream || typeof upstream !== 'object' || Array.isArray(upstream)) {
    return { changed: false, next: config, notes: ['Upstream domain list is not an object; catalogue left alone.'] };
  }
  const sources = {};
  const notes = [];
  for (const [id, raw] of Object.entries(upstream).slice(0, MAX_SOURCES)) {
    if (!SOURCE_ID.test(id) || !Array.isArray(raw)) {
      notes.push(`Skipped "${id}": unexpected shape.`);
      continue;
    }
    const domains = [...new Set(raw.map((d) => (typeof d === 'string' ? normalizeBase(d) : null)).filter(Boolean))]
      .slice(0, MAX_DOMAINS);
    if (domains.length === 0) {
      notes.push(`Skipped "${id}": no valid https domain.`);
      continue;
    }
    sources[id] = { domains };
  }
  if (Object.keys(sources).length === 0) {
    return { changed: false, next: config, notes: [...notes, 'No usable providers upstream; catalogue left alone.'] };
  }
  const before = config.sources || {};
  const added = Object.keys(sources).filter((id) => !before[id]);
  const removed = Object.keys(before).filter((id) => !sources[id]);
  const moved = Object.keys(sources).filter(
    (id) => before[id] && JSON.stringify(before[id].domains) !== JSON.stringify(sources[id].domains),
  );
  if (!added.length && !removed.length && !moved.length) return { changed: false, next: config, notes };
  const parts = [];
  if (added.length) parts.push(`added ${added.join(', ')}`);
  if (moved.length) parts.push(`new domain for ${moved.join(', ')}`);
  if (removed.length) parts.push(`removed ${removed.join(', ')}`);
  notes.push(`Catalogue: ${parts.join('; ')}.`);
  return { changed: true, next: { ...config, sources }, notes };
}

async function fetchRawText(url, label) {
  if (!url.startsWith('https://raw.githubusercontent.com/')) {
    throw new Error(`${label} must be a raw.githubusercontent.com URL`);
  }
  const res = await fetch(url, { signal: AbortSignal.timeout(15000) });
  if (!res.ok) {
    console.warn(`${label} returned HTTP ${res.status}; ignoring it.`);
    return null;
  }
  return boundedText(res);
}

async function main() {
  const config = JSON.parse(readFileSync(CONFIG_PATH, 'utf8'));

  // Optional Kotlin provider file for the primary (API-backed) source's mainUrl.
  const referenceUrl = process.env.IDLIX_REFERENCE_URL;
  const referenceSource = referenceUrl ? await fetchRawText(referenceUrl, 'IDLIX_REFERENCE_URL') : null;

  const result = await decide({
    config,
    fetchImpl: fetch,
    referenceSource,
    allowNewDomain: process.env.ALLOW_NEW_DOMAIN === 'true',
  });
  let next = result.changed ? result.next : config;
  const notes = [result.note];
  let changed = Boolean(result.changed);

  // Catalogue for every streaming source in the upstream CloudStream repo.
  const domainsUrl = process.env.SOURCE_DOMAINS_URL;
  if (domainsUrl) {
    const text = await fetchRawText(domainsUrl, 'SOURCE_DOMAINS_URL');
    if (text) {
      let upstream = null;
      try {
        upstream = JSON.parse(text);
      } catch {
        notes.push('SOURCE_DOMAINS_URL is not valid JSON; catalogue left alone.');
      }
      if (upstream) {
        const catalogue = syncCatalogue(next, upstream);
        notes.push(...catalogue.notes);
        if (catalogue.changed) {
          next = catalogue.next;
          changed = true;
        }
      }
    }
  }

  const summary = notes.join('\n');
  console.log(summary);
  if (process.env.GITHUB_STEP_SUMMARY) appendFileSync(process.env.GITHUB_STEP_SUMMARY, `${summary}\n`);
  if (changed) {
    // A catalogue-only change still bumps version so installed apps accept the new file.
    if (!result.changed) next = { ...next, version: config.version + 1 };
    writeFileSync(CONFIG_PATH, `${JSON.stringify(next, null, 2)}\n`);
  }
  if (process.env.GITHUB_OUTPUT) {
    appendFileSync(process.env.GITHUB_OUTPUT, `changed=${changed ? 'true' : 'false'}\n`);
  }
  process.exitCode = result.code;
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  main().catch((error) => {
    console.error(error.message);
    process.exitCode = 1;
  });
}

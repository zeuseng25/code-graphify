import createClient from 'openapi-fetch';
import { appRoot } from '../config/basePath';
import type { paths } from './schema';

/** An RFC 7807 problem as the backend sends it. */
export interface Problem {
  title?: string;
  status?: number;
  detail?: string;
  code?: string;
  /** The run in progress, sent with a 409 from POST /index/runs (IndexRunConflictException). */
  runId?: number;
}

/** A failed API call. The message is for logs; what the user sees comes from errorMessage (errors.ts). */
export class ApiError extends Error {
  constructor(
    readonly status: number,
    readonly problem: Problem,
  ) {
    super(problem.detail ?? problem.title ?? `HTTP ${status}`);
  }
}

/** HTTP methods that change state and therefore carry the CSRF token. */
const UNSAFE_METHODS = new Set(['POST', 'PUT', 'PATCH', 'DELETE']);

/** The problem code the backend uses for a missing or stale CSRF token. */
const CSRF_PROBLEM = 'CSRF';

interface CsrfToken {
  headerName: string;
  token: string;
}

let csrf: CsrfToken | null = null;
/** The token load in flight, shared so concurrent unsafe requests fetch one token. */
let csrfLoading: Promise<CsrfToken> | null = null;
/** Bumped by resetCsrf so a load that started before it cannot store a token afterwards. */
let csrfGeneration = 0;

/** Forget the token; the backend renews it at sign-in and password change, so it is fetched again when needed. */
export function resetCsrf(): void {
  csrf = null;
  csrfLoading = null;
  csrfGeneration++;
}

async function problemOf(response: Response): Promise<Problem> {
  try {
    return (await response.json()) as Problem;
  } catch {
    return { status: response.status };
  }
}

function isCsrfToken(value: unknown): value is CsrfToken {
  const candidate = value as Partial<CsrfToken> | null;
  return typeof candidate?.headerName === 'string' && typeof candidate.token === 'string';
}

async function fetchCsrf(): Promise<CsrfToken> {
  const response = await fetch(`${appRoot()}/api/v1/auth/csrf`, { credentials: 'same-origin' });
  if (!response.ok) {
    throw new ApiError(response.status, await problemOf(response));
  }
  const body: unknown = await response.json();
  if (!isCsrfToken(body)) {
    // not the backend's answer (e.g. a proxy page): reported to the user as "unreachable"
    throw new TypeError('Malformed CSRF token response');
  }
  return body;
}

function loadCsrf(): Promise<CsrfToken> {
  if (!csrfLoading) {
    const generation = csrfGeneration;
    const loading = fetchCsrf().then((token) => {
      if (generation === csrfGeneration) {
        csrf = token;
      }
      return token;
    });
    csrfLoading = loading;
    const clear = () => {
      if (csrfLoading === loading) {
        csrfLoading = null;
      }
    };
    loading.then(clear, clear);
  }
  return csrfLoading;
}

function withToken(request: Request, token: CsrfToken): Request {
  const headers = new Headers(request.headers);
  headers.set(token.headerName, token.token);
  return new Request(request, { headers });
}

/** fetch for the API client: adds the CSRF token to unsafe requests and retries once on a stale token. */
export async function csrfFetch(request: Request): Promise<Response> {
  if (!UNSAFE_METHODS.has(request.method)) {
    return fetch(request);
  }
  const retry = request.clone();
  const first = await fetch(withToken(request, csrf ?? (await loadCsrf())));
  if (first.status !== 403 || (await problemOf(first.clone())).code !== CSRF_PROBLEM) {
    return first;
  }
  csrf = null;
  return fetch(withToken(retry, await loadCsrf()));
}

export const api = createClient<paths>({ baseUrl: appRoot(), fetch: csrfFetch, credentials: 'same-origin' });

/** The data of a successful call; otherwise throws ApiError with the backend's problem. */
export async function call<T>(promise: Promise<{ data?: T; error?: unknown; response: Response }>): Promise<T> {
  const { data, error, response } = await promise;
  if (!response.ok) {
    // a non-JSON error body (an HTML gateway page) arrives as text and is no problem
    const problem = typeof error === 'object' && error !== null ? (error as Problem) : { status: response.status };
    throw new ApiError(response.status, problem);
  }
  return data as T;
}

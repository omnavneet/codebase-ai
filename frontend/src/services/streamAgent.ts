/**
 * Consume an agent run streamed as Server-Sent Events.
 *
 * The backend relays the AI service's streaming agent endpoints (investigate,
 * explain, debug, docstrings), which emit `status` and `step` events as work
 * happens, then a terminal `done` (with the full result payload) or `error`.
 * Uses fetch (POST + auth header + streaming body), the same approach as the
 * chat stream.
 */
import { refreshAccessToken } from './apiClient';

export interface AgentStreamResult {
  answer: string;
  trace: string[];
  iterations: number;
  tool_calls: number;
  files_read: string[];
  searches_performed: string[];
  truncated?: boolean;
  stopped_early?: boolean;
  stop_reason?: string;
  usage?: {
    input_tokens: number;
    output_tokens: number;
    total_tokens: number;
  };
  findings?: Array<{
    severity: string;
    category: string;
    title: string;
    lines: string;
    description: string;
    suggestion: string;
    code_before?: string;
    code_after?: string;
  }>;
  frames?: Array<{
    file?: string;
    line?: number;
    symbol?: string;
    language?: string;
    raw?: string;
  }>;
  project_frames?: Array<{
    file?: string;
    line?: number;
    symbol?: string;
    language?: string;
    raw?: string;
  }>;
  stack_trace_language?: string;
  stack_trace_parsed?: boolean;
  stack_trace_notes?: string[];
  [key: string]: unknown;
}

export interface AgentStreamHandlers {
  onStep?: (message: string) => void;
  onDone?: (result: AgentStreamResult) => void;
  onError: (message: string) => void;
}

/** A payload may arrive as a JSON object or a raw JSON string; normalise it. */
const parseData = (raw: string): Record<string, unknown> | null => {
  try {
    const parsed = JSON.parse(raw);
    return parsed && typeof parsed === 'object' ? parsed as Record<string, unknown> : null;
  } catch {
    return null;
  }
};

const friendlyHttpError = (status: number, retryAfterHeader: string | null): string => {
  if (status === 429) {
    const seconds = retryAfterHeader ? Number.parseInt(retryAfterHeader, 10) : NaN;
    return Number.isFinite(seconds)
      ? `You're going a little fast — try again in ${seconds} second${seconds === 1 ? '' : 's'}.`
      : "You're going a little fast — try again in a moment.";
  }
  if (status === 403) return 'Please verify your email to use this feature.';
  if (status === 404) return "That file or project could not be found.";
  if (status === 502 || status === 503) {
    return 'The AI service is unavailable right now. Please try again.';
  }
  return 'That request could not be completed. Please try again.';
};

export const streamAgent = async (
  projectId: string,
  path: string,
  body: Record<string, unknown>,
  handlers: AgentStreamHandlers,
  signal?: AbortSignal,
): Promise<void> => {
  const callStream = (authToken?: string | null) =>
    fetch(`/api/projects/${projectId}/agent${path}`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        ...(authToken ? { Authorization: `Bearer ${authToken}` } : {}),
      },
      body: JSON.stringify(body),
      signal,
    });

  let response = await callStream(localStorage.getItem('access_token'));

  if (response.status === 401) {
    try {
      const refreshedToken = await refreshAccessToken();
      response = await callStream(refreshedToken);
    } catch {
      // fall through to the error handling below
    }
  }

  if (!response.ok || !response.body) {
    handlers.onError(friendlyHttpError(response.status, response.headers.get('Retry-After')));
    return;
  }

  const reader = response.body.getReader();
  const decoder = new TextDecoder();
  let buffer = '';

  const fieldValue = (raw: string, prefixLength: number) => {
    let value = raw.slice(prefixLength);
    if (value.endsWith('\r')) value = value.slice(0, -1);
    if (value.startsWith(' ')) value = value.slice(1);
    return value;
  };

  const processEvent = (rawEvent: string): boolean => {
    let event = 'message';
    const dataLines: string[] = [];
    for (const rawLine of rawEvent.split('\n')) {
      if (rawLine.startsWith('data:')) {
        dataLines.push(fieldValue(rawLine, 5));
      } else if (rawLine.startsWith('event:')) {
        event = fieldValue(rawLine, 6);
      }
    }
    if (dataLines.length === 0) return false;
    const payload = parseData(dataLines.join('\n'));
    if (!payload) return false;

    if (event === 'status' || event === 'step') {
      const message = typeof payload.message === 'string' ? payload.message : '';
      if (message) handlers.onStep?.(message);
    } else if (event === 'done') {
      handlers.onDone?.(payload as AgentStreamResult);
      return true;
    } else if (event === 'error') {
      handlers.onError(
        typeof payload.message === 'string' ? payload.message : 'That run could not be completed.',
      );
      return true;
    }
    return false;
  };

  const findBoundary = (buf: string): { index: number; length: number } | null => {
    const lf = buf.indexOf('\n\n');
    const crlf = buf.indexOf('\r\n\r\n');
    if (crlf !== -1 && (lf === -1 || crlf <= lf)) return { index: crlf, length: 4 };
    if (lf !== -1) return { index: lf, length: 2 };
    const lfcrlf = buf.indexOf('\n\r\n');
    if (lfcrlf !== -1) return { index: lfcrlf, length: 3 };
    return null;
  };

  try {
    for (;;) {
      const { done, value } = await reader.read();
      if (done) break;
      buffer += decoder.decode(value, { stream: true });
      let boundary = findBoundary(buffer);
      while (boundary !== null) {
        const isTerminal = processEvent(buffer.slice(0, boundary.index));
        buffer = buffer.slice(boundary.index + boundary.length);
        if (isTerminal) {
          await reader.cancel().catch(() => {});
          return;
        }
        boundary = findBoundary(buffer);
      }
    }
  } finally {
    reader.releaseLock();
  }
};

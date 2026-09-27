/**
 * Consume the SSE stream of a chat answer.
 *
 * Uses fetch (not axios or EventSource) because the request must be a POST
 * with an Authorization header, and the body must be read incrementally.
 * The server emits `meta` (optional), `token`, `done` and `error` events.
 */
import { refreshAccessToken } from './apiClient';
export interface StreamHandlers {
  onMeta?: (citations: unknown[]) => void;
  onToken: (token: string) => void;
  onDone?: (messageId: string) => void;
  onError: (message: string) => void;
}

/** JSON bodies emitted by the backend for each SSE event type. */
interface StreamEventPayload {
  citations?: unknown[];
  t?: string;
  messageId?: string;
  message?: string;
}

export const streamChatMessage = async (
  sessionId: string,
  content: string,
  handlers: StreamHandlers,
  signal?: AbortSignal,
): Promise<void> => {
  const callStream = (authToken?: string | null) =>
    fetch(`/api/sessions/${sessionId}/messages/stream`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        ...(authToken ? { Authorization: `Bearer ${authToken}` } : {}),
      },
      body: JSON.stringify({ content }),
      signal,
    });

  let response = await callStream(localStorage.getItem('access_token'));

  // The stream endpoint bypasses axios, so it has no automatic token refresh.
  // If the access token expired, refresh once and retry before giving up.
  if (response.status === 401) {
    try {
      const refreshedToken = await refreshAccessToken();
      response = await callStream(refreshedToken);
    } catch {
      // Fall through to the !response.ok error below.
    }
  }

  if (!response.ok || !response.body) {
    throw new Error(`Stream request failed with status ${response.status}`);
  }

  const reader = response.body.getReader();
  const decoder = new TextDecoder();
  let buffer = '';

  // Per the SSE spec: strip a single leading space after the field colon and
  // a single trailing carriage return. Never trim the value itself — that
  // would corrupt streamed code indentation and inter-word spacing.
  const fieldValue = (raw: string, prefixLength: number) => {
    let value = raw.slice(prefixLength);
    if (value.endsWith('\r')) value = value.slice(0, -1);
    if (value.startsWith(' ')) value = value.slice(1);
    return value;
  };

  const processEvent = (rawEvent: string) => {
    let event = 'message';
    const dataLines: string[] = [];
    for (const rawLine of rawEvent.split('\n')) {
      if (rawLine.startsWith('data:')) {
        dataLines.push(fieldValue(rawLine, 5));
      } else if (rawLine.startsWith('event:')) {
        event = fieldValue(rawLine, 6);
      }
      // `id:`, `retry:` and comment lines (":...") are intentionally ignored.
    }
    // Multiple `data:` lines of one event are joined with "\n" per the spec.
    if (dataLines.length === 0) return false;
    const data = dataLines.join('\n');

    let payload: StreamEventPayload;
    try {
      payload = JSON.parse(data) as StreamEventPayload;
    } catch {
      return false;
    }

    if (event === 'meta') {
      handlers.onMeta?.(payload.citations ?? []);
    } else if (event === 'token') {
      handlers.onToken(payload.t ?? '');
    } else if (event === 'done') {
      handlers.onDone?.(payload.messageId ?? '');
      // `done` is the authoritative end-of-answer signal. Stop reading
      // immediately: dev proxies (and some intermediaries) keep the SSE
      // connection open after the server completes, which would otherwise
      // hang this promise and leave the composer locked forever.
      return true;
    } else if (event === 'error') {
      handlers.onError(payload.message ?? 'The answer stream failed.');
      // `error` is terminal as well — see `done` above.
      return true;
    }
    return false;
  };

  // Locate the blank-line separator that terminates an event. Servers and
  // intermediaries may use LF or CRLF line endings, so handle "\n\n",
  // "\r\n\r\n" and the mixed "\n\r\n".
  const findBoundary = (
    buf: string,
  ): { index: number; length: number } | null => {
    const lf = buf.indexOf('\n\n');
    const crlf = buf.indexOf('\r\n\r\n');
    if (crlf !== -1 && (lf === -1 || crlf <= lf)) {
      return { index: crlf, length: 4 };
    }
    if (lf !== -1) {
      return { index: lf, length: 2 };
    }
    const lfcrlf = buf.indexOf('\n\r\n');
    if (lfcrlf !== -1) {
      return { index: lfcrlf, length: 3 };
    }
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
          // Terminal event received: the answer is complete. Cancel the
          // reader (closing the connection, which the proxy fails to do)
          // and settle the promise.
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

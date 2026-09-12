/**
 * Consume the SSE stream of a chat answer.
 *
 * Uses fetch (not axios or EventSource) because the request must be a POST
 * with an Authorization header, and the body must be read incrementally.
 * The server emits `meta` (optional), `token`, `done` and `error` events.
 */
export interface StreamHandlers {
  onMeta?: (citations: unknown[]) => void;
  onToken: (token: string) => void;
  onDone?: (messageId: string) => void;
  onError: (message: string) => void;
}

export const streamChatMessage = async (
  sessionId: string,
  content: string,
  handlers: StreamHandlers,
  signal?: AbortSignal,
): Promise<void> => {
  const token = localStorage.getItem('access_token');

  const response = await fetch(`/api/sessions/${sessionId}/messages/stream`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
    },
    body: JSON.stringify({ content }),
    signal,
  });

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
    if (dataLines.length === 0) return;
    const data = dataLines.join('\n');

    let payload: any;
    try {
      payload = JSON.parse(data);
    } catch {
      return;
    }

    if (event === 'meta') {
      handlers.onMeta?.(payload.citations ?? []);
    } else if (event === 'token') {
      handlers.onToken(payload.t ?? '');
    } else if (event === 'done') {
      handlers.onDone?.(payload.messageId ?? '');
    } else if (event === 'error') {
      handlers.onError(payload.message ?? 'The answer stream failed.');
    }
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
        processEvent(buffer.slice(0, boundary.index));
        buffer = buffer.slice(boundary.index + boundary.length);
        boundary = findBoundary(buffer);
      }
    }
  } finally {
    reader.releaseLock();
  }
};

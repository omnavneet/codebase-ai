import { useCallback, useEffect, useRef, useState } from 'react';
import { streamAgent, type AgentStreamResult } from '../services/streamAgent';

/**
 * Shared streaming-run state for the Analyze panels and chat's Deep
 * investigate. Wraps {@link streamAgent} with the three things every run
 * needs: a live step list, a Cancel that actually aborts the backend work, and
 * a Retry that re-runs the last request. Errors are already plain-language
 * (the service maps 429/403/404/502), so panels just render `error`.
 */
export interface AgentRunState {
  steps: string[];
  loading: boolean;
  error: string;
  /** Result payload from the terminal `done` event, if the run succeeded. */
  result: AgentStreamResult | null;
  run: (path: string, body: Record<string, unknown>) => Promise<void>;
  cancel: () => void;
  retry: () => void;
}

export function useAgentRun(projectId: string, onResult: (result: AgentStreamResult) => void): AgentRunState {
  const [steps, setSteps] = useState<string[]>([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');
  const [result, setResult] = useState<AgentStreamResult | null>(null);

  const abortRef = useRef<AbortController | null>(null);
  // The last request, so Retry can replay it without the caller re-supplying.
  const lastRequestRef = useRef<{ path: string; body: Record<string, unknown> } | null>(null);

  const cancel = useCallback(() => {
    abortRef.current?.abort();
    abortRef.current = null;
    setLoading(false);
  }, []);

  useEffect(() => () => {
    abortRef.current?.abort();
    abortRef.current = null;
  }, [projectId]);

  const run = useCallback(
    async (path: string, body: Record<string, unknown>) => {
      if (abortRef.current) return;
      lastRequestRef.current = { path, body };

      setLoading(true);
      setError('');
      setResult(null);
      setSteps([]);

      const controller = new AbortController();
      abortRef.current = controller;

      try {
        await streamAgent(
          projectId,
          path,
          body,
          {
            onStep: (message) => {
              if (abortRef.current === controller) setSteps((prev) => [...prev, message]);
            },
            onDone: (payload) => {
              if (abortRef.current !== controller) return;
              setResult(payload);
              onResult(payload);
            },
            onError: (message) => {
              if (abortRef.current === controller) setError(message);
            },
          },
          controller.signal,
        );
      } catch (err) {
        if (abortRef.current === controller && !controller.signal.aborted) {
          setError(err instanceof Error ? err.message : 'The investigation was interrupted. Please try again.');
        }
      } finally {
        if (abortRef.current === controller) {
          setLoading(false);
          abortRef.current = null;
        }
      }
    },
    [onResult, projectId],
  );

  const retry = useCallback(() => {
    const last = lastRequestRef.current;
    if (last) void run(last.path, last.body);
  }, [run]);

  return { steps, loading, error, result, run, cancel, retry };
}

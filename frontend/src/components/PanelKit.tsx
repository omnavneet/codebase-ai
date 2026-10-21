import React, { useEffect, useRef, useState } from 'react';
import { copyText } from '../utils/clipboard';
import type { Finding } from './findings';

/* ------------------------------------------------------------------ *
 * Shared primitives for the workspace modes.
 *
 * Search, Analyze (Explain, Debug, Review, Docstrings) and Chat's inline
 * investigation all need the same four things around their unique content:
 * what to show before the user has acted, what to show while work is in
 * flight, a way out of a failure, and a copy action. Defining them once is
 * what stops the modes from drifting into slightly different visual languages.
 * ------------------------------------------------------------------ */

const alertIcon = (
  <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
    <circle cx="12" cy="12" r="10" />
    <line x1="12" y1="8" x2="12" y2="12" />
    <line x1="12" y1="16" x2="12.01" y2="16" />
  </svg>
);

const successIcon = (
  <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
    <polyline points="20 6 9 17 4 12" />
  </svg>
);

const errorIcon = (
  <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
    <circle cx="12" cy="12" r="10" />
    <line x1="15" y1="9" x2="9" y2="15" />
    <line x1="9" y1="9" x2="15" y2="15" />
  </svg>
);

type ProgressStage = 'idle' | 'uploading' | 'chunking' | 'embedding' | 'running' | 'ready' | 'error' | 'stopped';

interface ProgressBarProps {
  /** Current stage of the operation */
  stage: ProgressStage;
  /** Current step message from the stream */
  step?: string;
  /** Elapsed time in seconds */
  elapsed?: number;
  /** Cancel callback */
  onCancel?: () => void;
  /** Retry callback (for error/stopped states) */
  onRetry?: () => void;
  /** Whether the operation is actively running */
  loading?: boolean;
  /** Error message (for error state) */
  error?: string;
  /** Stop reason (for stopped state) */
  stopReason?: string;
  /** Whether to show the Cancel button */
  showCancel?: boolean;
  /** Project upload specific: file size validation message */
  sizeLimitMessage?: string;
  /**
   * Real progress percent (0-100). When set and positive on a non-terminal
   * running stage, the bar renders determinately instead of shimmering.
   */
  percent?: number;
}

/**
 * Shared progress component for agent runs and project indexing.
 *
 * Pinned position, real step name from stream, elapsed time, Cancel button,
 * "stopped early" state with partial result, error state with Retry button.
 * Project upload stages: uploading, chunking, embedding, ready.
 * 50MB cap explained before file pick; error shown inline if exceeded.
 * Restrained styling — no gradients, simple indeterminate bar only.
 */
export const ProgressBar: React.FC<ProgressBarProps> = ({
  stage,
  step,
  elapsed = 0,
  onCancel,
  onRetry,
  loading = true,
  error,
  stopReason,
  showCancel = true,
  sizeLimitMessage,
  percent,
}) => {
  const formatTime = (seconds: number) => {
    const m = Math.floor(seconds / 60);
    const s = seconds % 60;
    return m > 0 ? `${m}m ${s}s` : `${s}s`;
  };

  const getStageLabel = (s: ProgressStage) => {
    switch (s) {
      case 'uploading':
        return 'Uploading archive…';
      case 'chunking':
        return 'Chunking files…';
      case 'embedding':
        return 'Embedding chunks…';
      case 'running':
        return 'Working…';
      case 'ready':
        return 'Indexing complete';
      case 'error':
        return 'Indexing failed';
      case 'stopped':
        return 'Stopped early';
      default:
        return 'Preparing…';
    }
  };

  const isTerminal = stage === 'ready' || stage === 'error' || stage === 'stopped';
  const isError = stage === 'error';
  const isStopped = stage === 'stopped';
  const isComplete = stage === 'ready';

  const showProgress = stage !== 'idle';

  // A real, positive percent makes the running bar determinate; anything
  // else (unknown progress, 0 before the first heartbeat) keeps the
  // indeterminate shimmer instead of a misleading solid bar.
  const determinatePercent =
    !isTerminal && typeof percent === 'number' && percent > 0
      ? Math.min(100, Math.round(percent))
      : null;

  if (!showProgress) return null;

  return (
    <div className={`progress-bar-root ${isTerminal ? 'terminal' : ''} ${isError ? 'error' : ''} ${isStopped ? 'stopped' : ''}`} role="status" aria-live="polite">
      <div className="progress-bar-header">
        <span className="progress-bar-title">
          {getStageLabel(stage)}
          {determinatePercent !== null ? ` ${determinatePercent}%` : ''}
        </span>
        {elapsed > 0 && <span className="progress-bar-elapsed" aria-label={`Elapsed ${formatTime(elapsed)}`}>{formatTime(elapsed)}</span>}
      </div>

      {step && <div className="progress-bar-step">{step}</div>}

      <div className="progress-bar-track" aria-hidden={!loading || isTerminal}>
        {loading && !isTerminal && determinatePercent !== null && (
          <div className="progress-bar-fill progress-bar-fill-determinate" style={{ width: `${determinatePercent}%` }} />
        )}
        {loading && !isTerminal && determinatePercent === null && (
          <div className="progress-bar-indeterminate" />
        )}
        {!loading && !isTerminal && (
          <div className="progress-bar-fill" style={{ width: '100%' }} />
        )}
        {isComplete && (
          <div className="progress-bar-fill progress-bar-fill-complete" style={{ width: '100%' }} />
        )}
        {isError && (
          <div className="progress-bar-fill progress-bar-fill-error" style={{ width: '100%' }} />
        )}
        {isStopped && (
          <div className="progress-bar-fill progress-bar-fill-stopped" style={{ width: '100%' }} />
        )}
      </div>

      {(isError || isStopped) && (
        <div className="progress-bar-message">
          {isStopped ? (
            <>
              <span className="progress-bar-icon" aria-hidden="true">{stopReason === 'cancelled' ? successIcon : errorIcon}</span>
              <span>This run was stopped early. {stopReason === 'cancelled' ? 'Here is what was found before stopping.' : 'Here is what was found so far.'}</span>
            </>
          ) : (
            <>
              <span className="progress-bar-icon" aria-hidden="true">{errorIcon}</span>
              <span>{error || 'That request could not be completed. Please try again.'}</span>
            </>
          )}
        </div>
      )}

      <div className="progress-bar-actions">
        {showCancel && loading && !isTerminal && onCancel && (
          <button type="button" className="ui-btn ui-btn-neutral ui-btn-sm" onClick={onCancel}>
            Cancel
          </button>
        )}
        {(isError || isStopped) && onRetry && (
          <button type="button" className="ui-btn ui-btn-primary ui-btn-sm" onClick={onRetry}>
            Retry
          </button>
        )}
        {isComplete && (
          <span className="progress-bar-success" aria-hidden="true">{successIcon} Done</span>
        )}
      </div>

      {sizeLimitMessage && (
        <p className="progress-bar-limit-hint" role="note">
          {sizeLimitMessage}
        </p>
      )}
    </div>
  );
};

/**
 * The three states every data screen can be in, in one place: waiting for
 * the request, nothing to show, or the request failed.
 *
 * The screens that show a list (projects, files, search hits, the files an
 * agent can pick) all had their own version of these three, and a failure
 * that had nowhere to go — a blank panel, or a spinner that never stopped.
 * One component means they now say the same thing and behave the same way:
 * a failed load always ends in an error with a Retry that re-runs the same
 * request.
 */
type StateViewVariant = 'loading' | 'empty' | 'error';

interface StateViewProps {
  variant: StateViewVariant;
  /** The one line that says what is happening, or what to do next. */
  message: string;
  /** Empty only: the way out of an empty screen. */
  action?: { label: string; onClick: () => void };
  /** Error only: re-runs the request that failed. */
  onRetry?: () => void;
}
export const StateView: React.FC<StateViewProps> = ({
  variant,
  message,
  action,
  onRetry,
}) => (
  <div
    className={`state-view state-view-${variant}`}
    role={variant === 'error' ? 'alert' : variant === 'loading' ? 'status' : undefined}
  >
    {variant === 'loading' && (
      <span className="spinner spinner-sm" aria-hidden="true" />
    )}
    <p className="state-view-message">{message}</p>
    {variant === 'error' && onRetry && (
      <button type="button" className="ui-btn ui-btn-primary ui-btn-sm" onClick={onRetry}>
        Retry
      </button>
    )}
    {variant === 'empty' && action && (
      <button
        type="button"
        className="ui-btn ui-btn-primary ui-btn-sm"
        onClick={action.onClick}
      >
        {action.label}
      </button>
    )}
  </div>
);

/**
 * First-run state for a mode. `examples` turns the empty state into a
 * starting point instead of a dead end — one click fills the form and runs it.
 */
export const PanelEmpty: React.FC<{
  icon: React.ReactNode;
  title: string;
  hint: string;
  examples?: string[];
  onExample?: (example: string) => void;
  children?: React.ReactNode;
}> = ({ icon, title, hint, examples, onExample, children }) => (
  <div className="panel-empty">
    <span className="panel-empty-icon" aria-hidden="true">
      {icon}
    </span>
    <h3 className="panel-empty-title">{title}</h3>
    <p className="panel-empty-hint">{hint}</p>
    {examples && examples.length > 0 && (
      <div className="panel-examples">
        <span className="panel-examples-label">Try one</span>
        <div className="panel-examples-list">
          {examples.map((example) => (
            <button
              key={example}
              type="button"
              className="panel-example"
              onClick={() => onExample?.(example)}
            >
              {example}
            </button>
          ))}
        </div>
      </div>
    )}
    {children}
  </div>
);

/**
 * Failure state. Says what happened, and — because a message with no way
 * forward just forces a page reload — offers a retry that re-runs the last
 * action with the inputs still filled in.
 */
export const PanelError: React.FC<{
  message: string;
  onRetry?: () => void;
  retryLabel?: string;
}> = ({ message, onRetry, retryLabel = 'Try again' }) => (
  <div className="panel-error" role="alert">
    <span className="panel-error-icon" aria-hidden="true">
      {alertIcon}
    </span>
    <div className="panel-error-body">
      <p className="panel-error-title">That didn’t work</p>
      <p className="panel-error-text">{message}</p>
    </div>
    {onRetry && (
      <button type="button" className="ui-btn ui-btn-ghost ui-btn-sm" onClick={onRetry}>
        {retryLabel}
      </button>
    )}
  </div>
);

/**
 * Live progress for a streaming agent run: a simple ordered list of the steps
 * emitted so far. Deliberately plain — the polished progress component is a
 * later step.
 */
export const AgentStepList: React.FC<{ steps: string[]; loading: boolean }> = ({
  steps,
  loading,
}) => {
  if (steps.length === 0 && !loading) return null;
  return (
    <div className="panel-card agent-steps" aria-live="polite" aria-label="Progress">
      <div className="panel-card-header">
        <h3 className="panel-section-title">Progress</h3>
        {loading && <span className="spinner spinner-xs" aria-hidden="true" />}
      </div>
      <ol className="panel-trace">
        {steps.map((step, index) => (
          <li key={index} className="panel-trace-step">
            {step}
          </li>
        ))}
      </ol>
    </div>
  );
};

/** Placeholder cards that stand in for results while a request is running. */
export const PanelSkeleton: React.FC<{ cards?: number }> = ({ cards = 3 }) => (
  <div className="panel-skeleton" aria-hidden="true">
    {Array.from({ length: cards }, (_, index) => (
      <div key={index} className="panel-skeleton-card">
        <span style={{ width: '46%' }} />
        <span style={{ width: '88%' }} />
        <span style={{ width: '72%' }} />
      </div>
    ))}
  </div>
);

/**
 * Long-running operations (agent runs, README generation) can take the best
 * part of a minute. A ticking counter is the cheapest proof that the request
 * is alive, so the wait doesn't read as a freeze.
 */
export const PanelRun: React.FC<{ label: string; hint?: string }> = ({
  label,
  hint,
}) => {
  // Stamped inside the effect rather than during render: `Date.now()` is not a
  // pure value to read while rendering.
  const startedAt = useRef(0);
  const [elapsed, setElapsed] = useState(0);

  useEffect(() => {
    startedAt.current = Date.now();
    const id = window.setInterval(
      () => setElapsed(Math.round((Date.now() - startedAt.current) / 1000)),
      1000
    );
    return () => window.clearInterval(id);
  }, []);

  return (
    <div className="panel-run" role="status">
      <span className="spinner spinner-sm panel-run-spinner" aria-hidden="true" />
      <div className="panel-run-text">
        <span className="panel-run-label">{label}</span>
        {hint && <span className="panel-run-hint">{hint}</span>}
      </div>
      <span className="panel-run-elapsed" aria-hidden="true">
        {elapsed}s
      </span>
    </div>
  );
};

/**
 * Shared renderer for review/debug findings. Both modes produce the same
 * shape, so they render the same card: severity, category, location, the
 * current code, and the suggested replacement.
 */
export const FindingsList: React.FC<{ findings: Finding[] }> = ({ findings }) => (
  <div className="finding-list">
    {findings.map((finding, index) => (
      <article className="finding-card" key={`${finding.title}-${index}`}>
        <div className="finding-header">
          <span className={`finding-badge ${finding.severity}`}>{finding.severity}</span>
          <span className="finding-badge category">{finding.category}</span>
          <span className="finding-title">{finding.title}</span>
          <span className="finding-lines">L{finding.lines}</span>
        </div>

        {finding.description && <p className="finding-text">{finding.description}</p>}
        {finding.suggestion && <p className="finding-text suggestion">{finding.suggestion}</p>}

        {finding.code_before && (
          <div className="diff-block diff-before">
            <div className="diff-label">Current</div>
            <pre>{finding.code_before}</pre>
          </div>
        )}
        {finding.code_after && (
          <div className="diff-block diff-after">
            <div className="diff-label">Suggested</div>
            <pre>{finding.code_after}</pre>
          </div>
        )}
      </article>
    ))}
  </div>
);

/**
 * File picker shared by the modes that operate on one file (Explain, Docs,
 * Review). Having one control means the three modes agree on what loading,
 * failure and "nothing indexed yet" look like — the states used to be an
 * empty dropdown with no explanation. The failure itself is reported by the
 * mode through the shared state view, so the control only has to say why it
 * is disabled.
 */
export const FilePicker: React.FC<{
  id: string;
  label: string;
  value: string;
  onChange: (value: string) => void;
  files: string[];
  loading: boolean;
  error: string;
  /** The field wrapper. Defaults to the growing field; a fixed one is narrower. */
  fieldClassName?: string;
}> = ({ id, label, value, onChange, files, loading, error, fieldClassName }) => (
  <div className={fieldClassName ?? 'panel-field panel-field-grow'}>
    <label className="panel-field-label" htmlFor={id}>
      {label}
    </label>
    <select
      id={id}
      className="ui-input ui-select"
      value={value}
      onChange={(event) => onChange(event.target.value)}
      disabled={loading || error !== '' || files.length === 0}
    >
      <option value="">
        {loading
          ? 'Loading files…'
          : error
            ? 'Could not load files…'
            : files.length === 0
              ? 'No files to analyze.'
              : 'Select a file…'}
      </option>
      {files.map((file) => (
        <option key={file} value={file}>
          {file}
        </option>
      ))}
    </select>
  </div>
);

export const CopyButton: React.FC<{
  text: string;
  label?: string;
  className?: string;
}> = ({ text, label = 'Copy', className = '' }) => {
  const [state, setState] = useState<'idle' | 'copied' | 'failed'>('idle');
  const timerRef = useRef<number | undefined>(undefined);

  useEffect(() => () => window.clearTimeout(timerRef.current), []);

  const handleCopy = async () => {
    setState((await copyText(text)) ? 'copied' : 'failed');
    window.clearTimeout(timerRef.current);
    timerRef.current = window.setTimeout(() => setState('idle'), 1800);
  };

  return (
    <button
      type="button"
      className={`panel-copy ui-btn ui-btn-ghost ui-btn-sm ${state} ${className}`}
      onClick={handleCopy}
    >
      {state === 'copied' ? 'Copied' : state === 'failed' ? 'Press Ctrl+C' : label}
    </button>
  );
};

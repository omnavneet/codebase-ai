import React, { useEffect, useRef, useState } from 'react';
import { copyText } from '../utils/clipboard';
import type { Finding } from './findings';

/* ------------------------------------------------------------------ *
 * Shared primitives for the workspace modes.
 *
 * Search, Agent, Docs, Explain, Debug and Review all need the same four
 * things around their unique content: what to show before the user has
 * acted, what to show while work is in flight, a way out of a failure, and
 * a copy action. Defining them once is what stops the six modes from
 * drifting into six slightly different visual languages.
 * ------------------------------------------------------------------ */

const alertIcon = (
  <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
    <circle cx="12" cy="12" r="10" />
    <line x1="12" y1="8" x2="12" y2="12" />
    <line x1="12" y1="16" x2="12.01" y2="16" />
  </svg>
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
 * empty dropdown with no explanation.
 */
export const FilePicker: React.FC<{
  id: string;
  label: string;
  value: string;
  onChange: (value: string) => void;
  files: string[];
  loading: boolean;
  error: string;
  onReload: () => void;
}> = ({ id, label, value, onChange, files, loading, error, onReload }) => (
  <div className="panel-field panel-field-grow">
    <label className="panel-field-label" htmlFor={id}>
      {label}
    </label>
    <select
      id={id}
      className="ui-input ui-select"
      value={value}
      onChange={(event) => onChange(event.target.value)}
      disabled={loading || files.length === 0}
    >
      <option value="">
        {loading
          ? 'Loading files…'
          : files.length === 0
            ? 'No indexed files yet'
            : 'Select a file…'}
      </option>
      {files.map((file) => (
        <option key={file} value={file}>
          {file}
        </option>
      ))}
    </select>
    {error && (
      <p className="ui-field-error" role="alert">
        {error}
        <button type="button" className="panel-inline-action" onClick={onReload}>
          Retry
        </button>
      </p>
    )}
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

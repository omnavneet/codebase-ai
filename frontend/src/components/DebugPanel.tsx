import React, { useCallback, useState } from 'react';
import apiClient from '../services/apiClient';
import {
  renderAnswerWithCitations,
  type AgentInvestigation,
} from './citationUtils';
import { type Finding } from './findings';
import { getApiErrorMessage } from '../utils/apiError';
import { CopyButton, FindingsList, PanelEmpty, PanelError, PanelRun } from './PanelKit';

interface StackFrame {
  file?: string | null;
  line?: number | null;
  symbol?: string | null;
  language?: string | null;
  raw?: string | null;
  project_path?: string;
}

interface DebugResult extends AgentInvestigation {
  findings?: Finding[];
  frames?: StackFrame[];
  project_frames?: StackFrame[];
  stack_trace_language?: string | null;
  stack_trace_parsed?: boolean;
  stack_trace_notes?: string[];
}

interface DebugPanelProps {
  projectId: string;
  onCitationClick: (filePath: string, startLine?: number, endLine?: number) => void;
}

const debugIcon = (
  <svg width="26" height="26" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
    <path d="M14.7 6.3a1 1 0 0 0 0 1.4l1.6 1.6a1 1 0 0 0 1.4 0l3.77-3.77a6 6 0 0 1-7.94 7.94l-6.91 6.91a2.12 2.12 0 0 1-3-3l6.91-6.91a6 6 0 0 1 7.94-7.94l-3.76 3.76z" />
  </svg>
);

/**
 * Debug: describe the failure, optionally paste the trace, let the agent walk
 * the code. The stack trace is the most useful thing a user can hand over, so
 * it gets a monospaced field with room to paste — but it stays optional, so
 * the mode still works from a description alone.
 */
const DebugPanel: React.FC<DebugPanelProps> = ({ projectId, onCitationClick }) => {
  const [description, setDescription] = useState('');
  const [stackTrace, setStackTrace] = useState('');
  const [filePath, setFilePath] = useState('');
  const [result, setResult] = useState<DebugResult | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');

  const runDebug = useCallback(async () => {
    if (!description.trim() || loading) return;

    setLoading(true);
    setError('');
    setResult(null);

    try {
      const response = await apiClient.post(`/projects/${projectId}/agent/debug`, {
        issueDescription: description.trim(),
        stackTrace: stackTrace.trim() || null,
        filePath: filePath.trim() || null,
      });
      setResult(response.data);
    } catch (err) {
      setError(getApiErrorMessage(err, 'The issue could not be investigated.'));
    } finally {
      setLoading(false);
    }
  }, [description, filePath, loading, projectId, stackTrace]);

  return (
    <div className="tool-panel">
      <form
        className="panel-toolbar"
        onSubmit={(event) => {
          event.preventDefault();
          void runDebug();
        }}
      >
        <div className="panel-field panel-field-grow">
          <label className="panel-field-label" htmlFor="debug-description">
            What is going wrong?
          </label>
          <textarea
            id="debug-description"
            className="ui-textarea panel-textarea"
            value={description}
            onChange={(event) => setDescription(event.target.value)}
            placeholder="Describe the failure — what you expected, and what happened instead."
            rows={3}
          />
        </div>

        <div className="panel-field panel-field-grow">
          <label className="panel-field-label" htmlFor="debug-trace">
            Stack trace (optional)
          </label>
          <textarea
            id="debug-trace"
            className="ui-textarea panel-textarea panel-textarea-mono"
            value={stackTrace}
            onChange={(event) => setStackTrace(event.target.value)}
            placeholder="Paste the error output here…"
            rows={4}
            spellCheck={false}
          />
        </div>

        <div className="panel-field panel-field-fixed">
          <label className="panel-field-label" htmlFor="debug-file">
            Suspected file (optional)
          </label>
          <input
            id="debug-file"
            className="ui-input"
            type="text"
            value={filePath}
            onChange={(event) => setFilePath(event.target.value)}
            placeholder="src/auth/AuthService.java"
            spellCheck={false}
          />
        </div>

        <button
          type="submit"
          className="ui-btn ui-btn-primary"
          disabled={!description.trim() || loading}
        >
          {loading ? (
            <>
              <span className="spinner spinner-xs" aria-hidden="true" />
              Debugging…
            </>
          ) : (
            'Debug issue'
          )}
        </button>
      </form>

      {error && <PanelError message={error} onRetry={() => void runDebug()} />}

      {loading && (
        <PanelRun
          label="Working through the failure"
          hint="Parsing the trace, then reading the code paths it points at."
        />
      )}

      {!loading && !error && !result && (
        <PanelEmpty
          icon={debugIcon}
          title="Describe a failure to get a root cause"
          hint="A description alone is enough. Adding the stack trace, or the file you suspect, makes the answer sharper."
        />
      )}

      {result && (
        <div className="panel-result">
          {result.frames && result.frames.length > 0 && (
            <section className="panel-card" aria-label="Parsed stack trace">
              <div className="panel-card-header">
                <h3 className="panel-section-title">Parsed stack trace</h3>
                {result.stack_trace_language && (
                  <span className="panel-meta">{result.stack_trace_language}</span>
                )}
              </div>
              <ol className="panel-trace">
                {result.frames.slice(0, 6).map((frame, index) => {
                  const inProject = result.project_frames?.some(
                    (candidate) => candidate.raw === frame.raw,
                  );
                  return (
                    <li key={index} className="panel-trace-step">
                      {frame.symbol || '(unknown)'} — {frame.file}
                      {frame.line ? `:${frame.line}` : ''}
                      {inProject ? ' · in project' : ''}
                    </li>
                  );
                })}
              </ol>
              {result.stack_trace_notes?.map((note, index) => (
                <p className="panel-meta" key={`note-${index}`}>
                  {note}
                </p>
              ))}
            </section>
          )}

          <section className="panel-card" aria-label="Debug trace">
            <h3 className="panel-section-title">Debug trace</h3>
            <ol className="panel-trace">
              {result.trace.map((step, index) => (
                <li key={index} className="panel-trace-step">
                  {step}
                </li>
              ))}
            </ol>
          </section>

          {result.findings && result.findings.length > 0 && (
            <section aria-label="Findings">
              <div className="panel-card-header">
                <h3 className="panel-section-title">Findings &amp; suggested fix</h3>
              </div>
              <FindingsList findings={result.findings} />
            </section>
          )}

          <section className="panel-card" aria-label="Root cause">
            <div className="panel-card-header">
              <h3 className="panel-section-title">Root cause &amp; fix</h3>
              <CopyButton text={result.answer} label="Copy" />
            </div>
            <div className="panel-answer">
              {renderAnswerWithCitations(result.answer, onCitationClick)}
            </div>
          </section>
        </div>
      )}
    </div>
  );
};

export default DebugPanel;

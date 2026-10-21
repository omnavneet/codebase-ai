import React, { useCallback, useState } from 'react';
import { type AgentInvestigation } from './citationUtils';
import MarkdownAnswer from './MarkdownAnswer';
import { type Finding } from './findings';
import { useProjectFiles } from '../hooks/useProjectFiles';
import { useAgentRun } from '../hooks/useAgentRun';
import { AgentStepList, CopyButton, FilePicker, FindingsList, PanelError, ProgressBar, StateView } from './PanelKit';

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
  /** Project file paths — citations only link when they resolve against it. */
  filePaths: string[];
}

/**
 * Debug: describe the failure, optionally paste the trace, let the agent walk
 * the code. The stack trace is the most useful thing a user can hand over, so
 * it gets a monospaced field with room to paste — but it stays optional, so
 * the mode still works from a description alone.
 */
const DebugPanel: React.FC<DebugPanelProps> = ({ projectId, onCitationClick, filePaths }) => {
  const { files, loading: filesLoading, error: filesError, reload } = useProjectFiles(projectId);
  const [description, setDescription] = useState('');
  const [stackTrace, setStackTrace] = useState('');
  const [filePath, setFilePath] = useState('');
  const [result, setResult] = useState<DebugResult | null>(null);

  const handleResult = useCallback((payload: Record<string, unknown>) => {
    const base = payload as unknown as AgentInvestigation & Record<string, unknown>;
    setResult({
      ...base,
      answer: (payload.answer as string) ?? '',
      trace: (payload.trace as string[]) ?? [],
      iterations: (payload.iterations as number) ?? 0,
      filesRead: (payload.files_read as string[]) ?? [],
      searchesPerformed: (payload.searches_performed as string[]) ?? [],
      truncated: payload.truncated as boolean | undefined,
    } as DebugResult);
  }, []);

  const { steps, loading, error, run, cancel, retry } = useAgentRun(projectId, handleResult);

  const runDebug = () => {
    if (!description.trim() || loading) return;
    void run('/debug/stream', {
      issueDescription: description.trim(),
      stackTrace: stackTrace.trim() || null,
      filePath: filePath.trim() || null,
    });
  };

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

        <FilePicker
          id="debug-file"
          label="Suspected file (optional)"
          value={filePath}
          onChange={setFilePath}
          files={files}
          loading={filesLoading}
          error={filesError}
          fieldClassName="panel-field panel-field-fixed"
        />

        <button
          type="submit"
          className="ui-btn ui-btn-primary"
          disabled={!description.trim() || loading}
        >
          {loading ? (
            <>
              <span className="spinner spinner-xs" aria-hidden="true" />
              Debugging...
            </>
          ) : (
            'Debug issue'
          )}
        </button>
      </form>

      {error && <PanelError message={error} onRetry={retry} />}

      {filesLoading && (
        <StateView variant="loading" message="Loading files…" />
      )}

      {filesError && (
        <StateView
          variant="error"
          message={filesError}
          onRetry={() => void reload()}
        />
      )}

      {!filesLoading && !filesError && files.length === 0 && (
        <StateView
          variant="empty"
          message="No files to analyze."
          action={{ label: 'Check again', onClick: () => void reload() }}
        />
      )}

      {loading && (
        <ProgressBar
          stage="running"
          step={steps[steps.length - 1]}
          onCancel={cancel}
          loading
        />
      )}
      <AgentStepList steps={steps} loading={loading} />

      {!loading && !error && !result && !filesLoading && !filesError && files.length > 0 && (
        <p className="panel-hint">Describe the failure and click Debug issue.</p>
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
              <MarkdownAnswer
                content={result.answer}
                onCitationClick={onCitationClick}
                filePaths={filePaths}
              />
            </div>
          </section>
        </div>
      )}
    </div>
  );
};

export default DebugPanel;

import React, { useState, useEffect, useRef } from 'react';
import { type AgentInvestigation } from './citationUtils';
import MarkdownAnswer from './MarkdownAnswer';
import { useProjectFiles } from '../hooks/useProjectFiles';
import { useAgentRun } from '../hooks/useAgentRun';
import { CopyButton, FilePicker, PanelError, ProgressBar, StateView } from './PanelKit';

interface ExplainPanelProps {
  projectId: string;
  onCitationClick: (filePath: string, startLine?: number, endLine?: number) => void;
  /** Project file paths — citations only link when they resolve against it. */
  filePaths: string[];
}

/**
 * Explain one file — or one symbol inside it. The form is the whole
 * interaction, so it leads; the answer is a trace plus an explanation, both of
 * which can cite the source they came from.
 */
const ExplainPanel: React.FC<ExplainPanelProps> = ({ projectId, onCitationClick, filePaths }) => {
  const { files, loading: filesLoading, error: filesError, reload } = useProjectFiles(projectId);
  const [selectedFile, setSelectedFile] = useState('');
  const [symbol, setSymbol] = useState('');
  const [result, setResult] = useState<AgentInvestigation | null>(null);

  const handleResult = React.useCallback((payload: AgentInvestigation) => {
    setResult(payload);
  }, []);

  const { steps, loading, error, run, cancel, retry, result: runResult } = useAgentRun(projectId, handleResult);

  const [elapsed, setElapsed] = useState(0);
  const startedAt = useRef(0);

  useEffect(() => {
    if (loading && !startedAt.current) {
      startedAt.current = Date.now();
      const id = window.setInterval(
        () => setElapsed(Math.round((Date.now() - startedAt.current) / 1000)),
        1000
      );
      return () => window.clearInterval(id);
    } else if (!loading && startedAt.current) {
      startedAt.current = 0;
      setElapsed(0);
    }
  }, [loading]);

  const runExplain = () => {
    if (!selectedFile || loading) return;
    void run('/explain-code/stream', {
      filePath: selectedFile,
      symbol: symbol || null,
    });
  };

  const isStopped = runResult?.truncated && runResult.stop_reason === 'cancelled';
  const isPartial = runResult?.truncated && runResult.stop_reason !== 'cancelled';

  // Prefer runResult (complete with all fields) over local result
  const displayResult = runResult ?? result;

  return (
    <div className="tool-panel">
      <div className="panel-toolbar">
        <FilePicker
          id="explain-file"
          label="File"
          value={selectedFile}
          onChange={setSelectedFile}
          files={files}
          loading={filesLoading}
          error={filesError}
        />

        <div className="panel-field panel-field-fixed">
          <label className="panel-field-label" htmlFor="explain-symbol">
            Symbol (optional)
          </label>
          <input
            id="explain-symbol"
            className="ui-input"
            type="text"
            value={symbol}
            onChange={(event) => setSymbol(event.target.value)}
            placeholder="Function or class"
          />
        </div>

        <button
          type="button"
          className="ui-btn ui-btn-primary"
          onClick={runExplain}
          disabled={!selectedFile || loading}
        >
          {loading ? (
            <>
              <span className="spinner spinner-xs" aria-hidden="true" />
              Explaining...
            </>
          ) : (
            'Explain'
          )}
        </button>
      </div>

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
          elapsed={elapsed}
          onCancel={cancel}
          loading
        />
      )}

      {isStopped && runResult && (
        <ProgressBar
          stage="stopped"
          elapsed={elapsed}
          onRetry={retry}
          stopReason="cancelled"
          loading={false}
        />
      )}

      {isPartial && runResult && (
        <ProgressBar
          stage="stopped"
          elapsed={elapsed}
          onRetry={retry}
          stopReason="timeout"
          loading={false}
        />
      )}

      {!loading && !error && !displayResult && !filesLoading && !filesError && files.length > 0 && (
        <p className="panel-hint">Pick a file and click Explain.</p>
      )}

      {displayResult && (
        <div className="panel-result">
          <section className="panel-card" aria-label="Analysis trace">
            <div className="panel-card-header">
              <h3 className="panel-section-title">Analysis trace</h3>
              <span className="panel-meta">
                {displayResult.files_read.length} files read · {displayResult.searches_performed.length} searches
              </span>
            </div>
            <ol className="panel-trace">
              {displayResult.trace.map((step, index) => (
                <li key={index} className="panel-trace-step">
                  {step}
                </li>
              ))}
            </ol>
          </section>

          <section className="panel-card" aria-label="Explanation">
            <div className="panel-card-header">
              <h3 className="panel-section-title">Explanation</h3>
              <CopyButton text={displayResult.answer} label="Copy explanation" />
            </div>
            <div className="panel-answer">
              <MarkdownAnswer
                content={displayResult.answer}
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

export default ExplainPanel;

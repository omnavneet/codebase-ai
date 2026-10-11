import React, { useState } from 'react';
import { type AgentInvestigation } from './citationUtils';
import MarkdownAnswer from './MarkdownAnswer';
import { useProjectFiles } from '../hooks/useProjectFiles';
import { useAgentRun } from '../hooks/useAgentRun';
import { AgentCancelButton, AgentStepList, CopyButton, FilePicker, PanelEmpty, PanelError, PanelRun } from './PanelKit';

interface ExplainPanelProps {
  projectId: string;
  onCitationClick: (filePath: string, startLine?: number, endLine?: number) => void;
}

const explainIcon = (
  <svg width="26" height="26" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
    <circle cx="12" cy="12" r="10" />
    <path d="M9.09 9a3 3 0 0 1 5.83 1c0 2-3 3-3 3" />
    <line x1="12" y1="17" x2="12.01" y2="17" />
  </svg>
);

/**
 * Explain one file — or one symbol inside it. The form is the whole
 * interaction, so it leads; the answer is a trace plus an explanation, both of
 * which can cite the source they came from.
 */
const ExplainPanel: React.FC<ExplainPanelProps> = ({ projectId, onCitationClick }) => {
  const { files, loading: filesLoading, error: filesError, reload } = useProjectFiles(projectId);
  const [selectedFile, setSelectedFile] = useState('');
  const [symbol, setSymbol] = useState('');
  const [result, setResult] = useState<AgentInvestigation | null>(null);

  const handleResult = React.useCallback((payload: Record<string, unknown>) => {
    setResult({
      answer: (payload.answer as string) ?? '',
      trace: (payload.trace as string[]) ?? [],
      iterations: (payload.iterations as number) ?? 0,
      filesRead: (payload.files_read as string[]) ?? [],
      searchesPerformed: (payload.searches_performed as string[]) ?? [],
      truncated: payload.truncated as boolean | undefined,
    });
  }, []);

  const { steps, loading, error, run, cancel, retry } = useAgentRun(projectId, handleResult);

  const runExplain = () => {
    if (!selectedFile || loading) return;
    void run('/explain-code/stream', {
      filePath: selectedFile,
      symbol: symbol || null,
    });
  };

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
          onReload={() => void reload()}
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
              Explaining…
            </>
          ) : (
            'Explain'
          )}
        </button>
        {loading && <AgentCancelButton onClick={cancel} />}
      </div>

      {error && <PanelError message={error} onRetry={retry} />}

      {loading && steps.length === 0 && (
        <PanelRun
          label="Reading the file in context"
          hint="Following the calls it makes before summarising — this can take a minute."
        />
      )}
      <AgentStepList steps={steps} loading={loading} />

      {!loading && !error && !result && (
        <PanelEmpty
          icon={explainIcon}
          title="Walk through a file"
          hint="Pick a file to get a grounded summary of what it does, how it is called, and what it depends on."
        />
      )}

      {result && (
        <div className="panel-result">
          <section className="panel-card" aria-label="Analysis trace">
            <div className="panel-card-header">
              <h3 className="panel-section-title">Analysis trace</h3>
              <span className="panel-meta">
                {result.filesRead.length} files read · {result.searchesPerformed.length} searches
              </span>
            </div>
            <ol className="panel-trace">
              {result.trace.map((step, index) => (
                <li key={index} className="panel-trace-step">
                  {step}
                </li>
              ))}
            </ol>
          </section>

          <section className="panel-card" aria-label="Explanation">
            <div className="panel-card-header">
              <h3 className="panel-section-title">Explanation</h3>
              <CopyButton text={result.answer} label="Copy explanation" />
            </div>
            <div className="panel-answer">
              <MarkdownAnswer content={result.answer} onCitationClick={onCitationClick} />
            </div>
          </section>
        </div>
      )}
    </div>
  );
};

export default ExplainPanel;

import React, { useCallback, useState } from 'react';
import apiClient from '../services/apiClient';
import {
  renderAnswerWithCitations,
  type AgentInvestigation,
} from './citationUtils';
import { getApiErrorMessage } from '../utils/apiError';
import { CopyButton, PanelEmpty, PanelError, PanelRun } from './PanelKit';

interface AgentPanelProps {
  projectId: string;
  onCitationClick: (filePath: string, startLine?: number, endLine?: number) => void;
}

const EXAMPLE_QUESTIONS = [
  'How does authentication flow through the request pipeline?',
  'What happens when an uploaded archive fails validation?',
  'Which services touch the database, and how?',
];

const agentIcon = (
  <svg width="26" height="26" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
    <polygon points="13 2 3 14 12 14 11 22 21 10 12 10 13 2" />
  </svg>
);

/**
 * The agent asks one question and then works through the codebase on its own.
 * Because a run can take the better part of a minute, the wait is made
 * explicit: a ticking counter, the label of the current phase, and the trace
 * of what it actually did once it is finished.
 */
const AgentPanel: React.FC<AgentPanelProps> = ({ projectId, onCitationClick }) => {
  const [question, setQuestion] = useState('');
  const [result, setResult] = useState<AgentInvestigation | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');

  const runInvestigation = useCallback(
    async (asked: string) => {
      const trimmed = asked.trim();
      if (!trimmed || loading) return;

      setLoading(true);
      setError('');
      setResult(null);

      try {
        const response = await apiClient.post(
          `/projects/${projectId}/agent/investigate`,
          { question: trimmed },
        );
        setResult(response.data);
      } catch (err) {
        setError(getApiErrorMessage(err, 'The investigation could not be completed.'));
      } finally {
        setLoading(false);
      }
    },
    [loading, projectId],
  );

  const handleKeyDown = (event: React.KeyboardEvent<HTMLTextAreaElement>) => {
    if (event.key === 'Enter' && !event.shiftKey) {
      event.preventDefault();
      void runInvestigation(question);
    }
  };

  return (
    <div className="tool-panel">
      <div className="panel-toolbar">
        <div className="panel-field panel-field-grow">
          <label className="panel-field-label" htmlFor="agent-question">
            Question for the agent
          </label>
          <textarea
            id="agent-question"
            className="ui-textarea panel-textarea"
            value={question}
            onChange={(event) => setQuestion(event.target.value)}
            onKeyDown={handleKeyDown}
            placeholder="Ask something that needs several files to answer…"
            rows={3}
            // Deliberately never disabled: composing the next question while a
            // run is in flight is normal; only starting one is blocked.
            spellCheck={false}
          />
        </div>
        <button
          type="button"
          className="ui-btn ui-btn-primary"
          onClick={() => void runInvestigation(question)}
          disabled={loading || question.trim().length === 0}
        >
          {loading ? (
            <>
              <span className="spinner spinner-xs" aria-hidden="true" />
              Investigating…
            </>
          ) : (
            'Investigate'
          )}
        </button>
      </div>

      {error && <PanelError message={error} onRetry={() => void runInvestigation(question)} />}

      {loading && (
        <PanelRun
          label="Investigating the codebase"
          hint="Reading files and running searches — this can take a minute."
        />
      )}

      {!loading && !error && !result && (
        <PanelEmpty
          icon={agentIcon}
          title="Hand a question to the agent"
          hint="The agent plans, searches and reads files on its own, then reports back with citations you can open."
          examples={EXAMPLE_QUESTIONS}
          onExample={(example) => {
            setQuestion(example);
            void runInvestigation(example);
          }}
        />
      )}

      {result && (
        <div className="panel-result">
          {result.truncated && (
            <div className="panel-warning" role="status">
              The run hit its iteration limit, so this answer may be incomplete.
            </div>
          )}

          <section className="panel-card" aria-label="Investigation trace">
            <div className="panel-card-header">
              <h3 className="panel-section-title">Investigation trace</h3>
              <span className="panel-meta">
                {result.iterations} tool calls · {result.filesRead.length} files ·{' '}
                {result.searchesPerformed.length} searches
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

          <section className="panel-card" aria-label="Answer">
            <div className="panel-card-header">
              <h3 className="panel-section-title">Answer</h3>
              <CopyButton text={result.answer} label="Copy answer" />
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

export default AgentPanel;

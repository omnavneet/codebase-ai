import React, { useState } from 'react';
import apiClient from '../services/apiClient';
import {
  renderAnswerWithCitations,
  type AgentInvestigation,
} from './citationUtils';
import './Agent.css';

interface AgentPanelProps {
  projectId: string;
  onCitationClick: (filePath: string, startLine?: number, endLine?: number) => void;
}

const AgentPanel: React.FC<AgentPanelProps> = ({ projectId, onCitationClick }) => {
  const [question, setQuestion] = useState('');
  const [result, setResult] = useState<AgentInvestigation | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');

  const handleInvestigate = async () => {
    if (!question.trim() || loading) return;

    setLoading(true);
    setError('');
    setResult(null);

    try {
      const response = await apiClient.post(
        `/projects/${projectId}/agent/investigate`,
        { question: question.trim() }
      );
      setResult(response.data);
    } catch (err: any) {
      setError(err.response?.data?.message || 'Investigation failed');
    } finally {
      setLoading(false);
    }
  };

  const handleKeyPress = (e: React.KeyboardEvent) => {
    if (e.key === 'Enter' && !e.shiftKey) {
      e.preventDefault();
      handleInvestigate();
    }
  };

  return (
    <div className="agent-panel">
      <div className="agent-input-container">
        <textarea
          className="agent-input"
          value={question}
          onChange={(e) => setQuestion(e.target.value)}
          onKeyPress={handleKeyPress}
          placeholder="Ask a complex question about the codebase..."
          rows={3}
        />
        <button
          className="agent-button"
          onClick={handleInvestigate}
          disabled={loading || !question.trim()}
        >
          {loading ? 'Investigating...' : 'Investigate'}
        </button>
      </div>

      {error && <div className="error-message">{error}</div>}

      {loading && (
        <div className="agent-loading">
          <div className="loading-spinner" />
          <span>Investigating codebase... this can take a minute</span>
        </div>
      )}

      {result && (
        <div className="agent-result">
          {result.truncated && (
            <div className="agent-warning">
              Investigation was truncated due to iteration limit
            </div>
          )}

          <div className="agent-trace">
            <h4>Investigation Trace</h4>
            {result.trace.map((step, index) => (
              <div key={index} className="trace-step">
                ✓ {step}
              </div>
            ))}
            <div className="trace-meta">
              {result.iterations} tool calls · {result.filesRead.length} files read · {result.searchesPerformed.length} searches
            </div>
          </div>

          <div className="agent-answer">
            <h4>Answer</h4>
            <div className="answer-content">
              {renderAnswerWithCitations(result.answer, onCitationClick)}
            </div>
          </div>
        </div>
      )}
    </div>
  );
};

export default AgentPanel;

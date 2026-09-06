import React, { useState } from 'react';
import apiClient from '../services/apiClient';
import './Agent.css';

interface AgentPanelProps {
  projectId: string;
  onCitationClick: (filePath: string, startLine?: number, endLine?: number) => void;
}

interface InvestigationResult {
  answer: string;
  trace: string[];
  iterations: number;
  filesRead: string[];
  searchesPerformed: string[];
  truncated: boolean;
}

const AgentPanel: React.FC<AgentPanelProps> = ({ projectId, onCitationClick }) => {
  const [question, setQuestion] = useState('');
  const [result, setResult] = useState<InvestigationResult | null>(null);
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

  // Parse citations from answer (file.tsx:42 or file.tsx:42-61 pattern)
  const renderAnswerWithCitations = (answer: string) => {
    const citationPattern = /([\w./-]+\.(?:java|py|js|ts|jsx|tsx|css|html|md|json)):(\d+)(?:-(\d+))?/g;
    const parts: React.ReactNode[] = [];
    let lastIndex = 0;
    let match;

    while ((match = citationPattern.exec(answer)) !== null) {
      // Add text before citation
      if (match.index > lastIndex) {
        parts.push(answer.substring(lastIndex, match.index));
      }

      // Add clickable citation
      const filePath = match[1];
      const startLine = parseInt(match[2]);
      const endLine = match[3] ? parseInt(match[3]) : startLine;

      parts.push(
        <button
          key={match.index}
          className="inline-citation"
          onClick={() => onCitationClick(filePath, startLine, endLine)}
        >
          {match[0]}
        </button>
      );

      lastIndex = match.index + match[0].length;
    }

    // Add remaining text
    if (lastIndex < answer.length) {
      parts.push(answer.substring(lastIndex));
    }

    return parts;
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
              {renderAnswerWithCitations(result.answer)}
            </div>
          </div>
        </div>
      )}
    </div>
  );
};

export default AgentPanel;

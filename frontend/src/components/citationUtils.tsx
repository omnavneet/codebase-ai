import React from 'react';

export type CitationClickHandler = (
  filePath: string,
  startLine?: number,
  endLine?: number,
) => void;

/** Shape returned by the agent endpoints (investigate/explain/debug). */
export interface AgentInvestigation {
  answer: string;
  trace: string[];
  iterations: number;
  filesRead: string[];
  searchesPerformed: string[];
  truncated?: boolean;
}

const CITATION_SOURCE =
  /([\w./-]+\.(?:java|py|js|ts|jsx|tsx|css|html|md|json)):(\d+)(?:-(\d+))?/.source;

/**
 * Render an LLM answer as text with clickable `file.ext:line` citations.
 * A fresh RegExp is built per call because global regexes keep lastIndex state.
 */
export const renderAnswerWithCitations = (
  answer: string,
  onCitationClick: CitationClickHandler,
): React.ReactNode[] => {
  const pattern = new RegExp(CITATION_SOURCE, 'g');
  const parts: React.ReactNode[] = [];
  let lastIndex = 0;
  let match;

  while ((match = pattern.exec(answer)) !== null) {
    if (match.index > lastIndex) {
      parts.push(answer.substring(lastIndex, match.index));
    }

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
      </button>,
    );

    lastIndex = match.index + match[0].length;
  }

  if (lastIndex < answer.length) {
    parts.push(answer.substring(lastIndex));
  }

  return parts;
};

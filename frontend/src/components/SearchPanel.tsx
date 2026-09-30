import React, { useCallback, useState } from 'react';
import { isAxiosError } from 'axios';
import apiClient from '../services/apiClient';
import { getApiErrorMessage } from '../utils/apiError';
import { PanelEmpty, PanelError } from './PanelKit';

interface SearchResult {
  fileId: string;
  filePath: string;
  startLine: number;
  endLine: number;
  content: string;
  similarity: number;
}

interface SearchPanelProps {
  projectId: string;
  onFileClick: (file: { fileId?: string }) => void;
}

/** Starter queries: they teach what "semantic" means without a paragraph. */
const EXAMPLE_QUERIES = [
  'where are requests authenticated',
  'how is the database connection configured',
  'error handling for failed uploads',
  'where is rate limiting applied',
];

const searchIcon = (
  <svg width="26" height="26" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
    <circle cx="11" cy="11" r="8" />
    <line x1="21" y1="21" x2="16.65" y2="16.65" />
  </svg>
);

/**
 * Semantic search as a mode of the workspace. The query is the point of the
 * screen, so it leads; each result is a real button, which keeps the list
 * reachable by keyboard and gives the browser the press affordances for free.
 */
const SearchPanel: React.FC<SearchPanelProps> = ({ projectId, onFileClick }) => {
  const [query, setQuery] = useState('');
  const [results, setResults] = useState<SearchResult[]>([]);
  const [loading, setLoading] = useState(false);
  const [searched, setSearched] = useState(false);
  const [error, setError] = useState('');
  // Which query produced what is on screen — so "Try again" re-runs the query
  // that failed rather than whatever happens to be in the box now.
  const [lastQuery, setLastQuery] = useState('');

  const runSearch = useCallback(
    async (term: string) => {
      const trimmed = term.trim();
      if (!trimmed || loading) return;

      setLoading(true);
      setSearched(true);
      setError('');
      setLastQuery(trimmed);

      try {
        const response = await apiClient.post(`/projects/${projectId}/search`, {
          query: trimmed,
        });
        setResults(response.data);
      } catch (err) {
        console.error('Search failed:', err);
        setResults([]);
        const notFound = isAxiosError(err) && err.response?.status === 404;
        setError(
          notFound
            ? 'This project could not be found, or you no longer have access to it.'
            : getApiErrorMessage(err, 'Search failed. The AI service may be unavailable.'),
        );
      } finally {
        setLoading(false);
      }
    },
    [loading, projectId],
  );

  const handleSubmit = (event: React.FormEvent) => {
    event.preventDefault();
    void runSearch(query);
  };

  return (
    <div className="tool-panel">
      <form className="panel-toolbar" onSubmit={handleSubmit} role="search">
        <div className="panel-field panel-field-grow">
          <input
            className="ui-input"
            type="text"
            value={query}
            onChange={(event) => setQuery(event.target.value)}
            placeholder="Describe what you are looking for…"
            aria-label="Search the codebase semantically"
            autoComplete="off"
          />
        </div>
        <button
          type="submit"
          className="ui-btn ui-btn-primary"
          disabled={loading || query.trim().length === 0}
        >
          {loading ? (
            <>
              <span className="spinner spinner-xs" aria-hidden="true" />
              Searching…
            </>
          ) : (
            'Search'
          )}
        </button>
      </form>

      {error ? (
        <PanelError message={error} onRetry={() => void runSearch(lastQuery)} />
      ) : loading ? (
        <div className="search-skeleton" aria-hidden="true">
          {Array.from({ length: 4 }, (_, index) => (
            <span key={index} />
          ))}
        </div>
      ) : !searched ? (
        <PanelEmpty
          icon={searchIcon}
          title="Search across the codebase"
          hint="Semantic search matches meaning rather than exact words, so describe the behaviour you are after."
          examples={EXAMPLE_QUERIES}
          onExample={(example) => {
            setQuery(example);
            void runSearch(example);
          }}
        />
      ) : results.length === 0 ? (
        <PanelEmpty
          icon={searchIcon}
          title={`No matches for “${lastQuery}”`}
          hint="Semantic search needs the meaning to overlap. Try describing what the code does, or simplify the phrasing."
        />
      ) : (
        <div className="panel-result">
          <p className="panel-meta">
            {results.length} {results.length === 1 ? 'match' : 'matches'} for “{lastQuery}”
          </p>
          {results.map((result, index) => (
            <button
              type="button"
              key={`${result.fileId}-${index}`}
              className="search-result-card row-enter"
              style={{ '--row-index': Math.min(index, 12) } as React.CSSProperties}
              onClick={() => onFileClick({ fileId: result.fileId })}
              title={`Open ${result.filePath}`}
            >
              <span className="search-result-head">
                <span className="search-result-path">{result.filePath}</span>
                <span className="search-result-lines">
                  L{result.startLine}–{result.endLine}
                </span>
              </span>
              <span className="search-result-snippet">{result.content}</span>
              <span className="search-result-score">
                {(result.similarity * 100).toFixed(1)}% match
              </span>
            </button>
          ))}
        </div>
      )}
    </div>
  );
};

export default SearchPanel;

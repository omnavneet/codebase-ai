import React, { useState } from 'react';
import { isAxiosError } from 'axios';
import apiClient from '../services/apiClient';
import { getApiErrorMessage } from '../utils/apiError';

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

const SearchPanel: React.FC<SearchPanelProps> = ({ projectId, onFileClick }) => {
  const [query, setQuery] = useState('');
  const [results, setResults] = useState<SearchResult[]>([]);
  const [loading, setLoading] = useState(false);
  const [searched, setSearched] = useState(false);
  const [error, setError] = useState('');

  const handleSearch = async () => {
    if (!query.trim() || loading) return;

    setLoading(true);
    setSearched(true);
    setError('');

    try {
      const response = await apiClient.post(`/projects/${projectId}/search`, {
        query: query,
      });
      setResults(response.data);
    } catch (err) {
      console.error('Search failed:', err);
      setResults([]);
      const notFound = isAxiosError(err) && err.response?.status === 404;
      setError(
        notFound
          ? 'Project not found or you do not have access to it.'
          : getApiErrorMessage(err, 'Search failed. Is the AI service running?')
      );
    } finally {
      setLoading(false);
    }
  };

  const handleKeyPress = (e: React.KeyboardEvent) => {
    if (e.key === 'Enter') {
      handleSearch();
    }
  };

  return (
    <div className="search-panel">
      <div className="search-input-container">
        <input
          className="search-input"
          type="text"
          value={query}
          onChange={(e) => setQuery(e.target.value)}
          onKeyDown={handleKeyPress}
          placeholder="Search code semantically..."
        />
        <button
          className="search-button"
          onClick={handleSearch}
          disabled={loading}
        >
          {loading ? 'Searching...' : 'Search'}
        </button>
      </div>

      <div className="search-results">
        {error ? (
          <div className="search-error">{error}</div>
        ) : !searched ? (
          <div className="search-placeholder">
            Search for functionality, not just text
          </div>
        ) : results.length === 0 ? (
          <div className="search-placeholder">
            No results found
          </div>
        ) : (
          results.map((result, index) => (
            <div
              key={index}
              className="search-result-item"
              onClick={() => onFileClick({ fileId: result.fileId })}
            >
              <div className="search-result-path">
                <span className="search-result-file">{result.filePath}</span>
                <span className="search-result-lines">
                  Lines {result.startLine}-{result.endLine}
                </span>
              </div>
              <div className="search-result-content">
                {result.content}
              </div>
              <div className="search-result-similarity">
                {(result.similarity * 100).toFixed(1)}% match
              </div>
            </div>
          ))
        )}
      </div>
    </div>
  );
};

export default SearchPanel;

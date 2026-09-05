import React, { useState } from 'react';
import apiClient from '../services/apiClient';

interface SearchPanelProps {
  projectId: string;
  onFileClick: (file: any) => void;
}

const SearchPanel: React.FC<SearchPanelProps> = ({ projectId, onFileClick }) => {
  const [query, setQuery] = useState('');
  const [results, setResults] = useState<any[]>([]);
  const [loading, setLoading] = useState(false);
  const [searched, setSearched] = useState(false);

  const handleSearch = async () => {
    if (!query.trim() || loading) return;

    setLoading(true);
    setSearched(true);

    try {
      const response = await apiClient.post(`/projects/${projectId}/search`, {
        query: query,
      });
      setResults(response.data);
    } catch (error) {
      console.error('Search failed:', error);
      setResults([]);
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
          onKeyPress={handleKeyPress}
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
        {!searched ? (
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
              onClick={() =>
                onFileClick({
                  fileId: result.fileId,
                  path: result.filePath,
                })
              }
            >
              <div className="search-result-path">
                {result.filePath}
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

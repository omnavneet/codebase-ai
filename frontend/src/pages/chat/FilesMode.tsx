import React, { useMemo, useState } from 'react';
import FileTree, { type FileNode } from '../../components/FileTree';
import { PanelEmpty, PanelError } from '../../components/PanelKit';
import { collectDirectoryPaths, collectFiles } from '../../utils/fileTree';

interface FilesModeProps {
  tree: FileNode[];
  loading: boolean;
  error: string;
  onRetry: () => void;
  onFileClick: (file: { fileId?: string }) => void;
}

/** Once this many matches are shown the list stops, to keep the DOM honest. */
const MAX_FILTER_RESULTS = 300;

const searchIcon = (
  <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
    <circle cx="11" cy="11" r="8" />
    <line x1="21" y1="21" x2="16.65" y2="16.65" />
  </svg>
);

const refreshIcon = (
  <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
    <polyline points="23 4 23 10 17 10" />
    <path d="M20.49 15a9 9 0 1 1-2.12-9.36L23 10" />
  </svg>
);

const emptyIcon = (
  <svg width="28" height="28" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
    <path d="M22 19a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h5l2 3h9a2 2 0 0 1 2 2z" />
  </svg>
);

/**
 * Files as a mode of the workspace rather than a sidebar panel: it gets a
 * toolbar, a filter, counts and its own empty/loading/error states, which is
 * what makes browsing a few thousand files possible at all.
 */
const FilesMode: React.FC<FilesModeProps> = ({
  tree,
  loading,
  error,
  onRetry,
  onFileClick,
}) => {
  const [filter, setFilter] = useState('');
  const [expandedDirs, setExpandedDirs] = useState<Set<string>>(new Set());

  const allFiles = useMemo(() => collectFiles(tree), [tree]);
  const allDirs = useMemo(() => collectDirectoryPaths(tree), [tree]);

  const isFiltering = filter.trim().length > 0;

  const matches = useMemo(() => {
    if (!isFiltering) return [];
    const needle = filter.trim().toLowerCase();
    return allFiles
      .filter((file) => file.path.toLowerCase().includes(needle))
      .slice(0, MAX_FILTER_RESULTS);
  }, [allFiles, filter, isFiltering]);

  const toggleDir = (path: string) =>
    setExpandedDirs((current) => {
      const next = new Set(current);
      if (next.has(path)) next.delete(path);
      else next.add(path);
      return next;
    });

  return (
    <div className="files-mode">
      <div className="files-toolbar">
        <div className="files-filter">
          <span className="files-filter-icon" aria-hidden="true">
            {searchIcon}
          </span>
          <input
            type="text"
            className="ui-input"
            value={filter}
            onChange={(event) => setFilter(event.target.value)}
            placeholder="Filter files by name or path"
            aria-label="Filter files"
          />
          {isFiltering && (
            <button
              type="button"
              className="ui-icon-btn ui-icon-btn-sm"
              onClick={() => setFilter('')}
              aria-label="Clear filter"
            >
              <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" aria-hidden="true">
                <line x1="18" y1="6" x2="6" y2="18" />
                <line x1="6" y1="6" x2="18" y2="18" />
              </svg>
            </button>
          )}
        </div>

        <div className="files-toolbar-actions">
          <span className="files-count">
            {allFiles.length.toLocaleString()}{' '}
            {allFiles.length === 1 ? 'file' : 'files'}
          </span>
          {!isFiltering && allFiles.length > 0 && (
            <>
              <button
                type="button"
                className="ui-btn ui-btn-ghost ui-btn-sm"
                onClick={() => setExpandedDirs(new Set(allDirs))}
              >
                Expand all
              </button>
              <button
                type="button"
                className="ui-btn ui-btn-ghost ui-btn-sm"
                onClick={() => setExpandedDirs(new Set())}
              >
                Collapse all
              </button>
            </>
          )}
          <button
            type="button"
            className="ui-btn ui-btn-ghost ui-btn-sm"
            onClick={onRetry}
            disabled={loading}
            title="Reload the file list"
          >
            {refreshIcon}
            <span>Refresh</span>
          </button>
        </div>
      </div>

      {loading ? (
        <div className="files-skeleton" aria-hidden="true">
          {Array.from({ length: 10 }, (_, index) => (
            <span key={index} style={{ width: `${34 + ((index * 29) % 55)}%` }} />
          ))}
        </div>
      ) : error ? (
        <PanelError message={error} onRetry={onRetry} retryLabel="Check again" />
      ) : allFiles.length === 0 ? (
        <PanelEmpty
          icon={emptyIcon}
          title="No files yet"
          hint="This project has not been indexed yet. Extracted source appears here as soon as indexing finishes."
        >
          <button
            type="button"
            className="ui-btn ui-btn-neutral ui-btn-sm"
            onClick={onRetry}
          >
            Check again
          </button>
        </PanelEmpty>
      ) : isFiltering ? (
        matches.length === 0 ? (
          <p className="files-no-match">
            No file matches “{filter.trim()}”. Try part of a directory name
            instead.
          </p>
        ) : (
          <ul className="files-results">
            {matches.map((file, index) => (
              <li
                key={file.path}
                className="files-result-row row-enter"
                style={{ '--row-index': Math.min(index, 12) } as React.CSSProperties}
              >
                <button
                  type="button"
                  onClick={() => onFileClick({ fileId: file.fileId })}
                  title={file.path}
                >
                  <span className="files-result-name">{file.name}</span>
                  <span className="files-result-path">
                    {file.path.slice(0, file.path.length - file.name.length - 1)}
                  </span>
                </button>
              </li>
            ))}
            {matches.length === MAX_FILTER_RESULTS && (
              <li className="files-truncated">
                Showing the first {MAX_FILTER_RESULTS} matches — refine the
                filter to narrow it down.
              </li>
            )}
          </ul>
        )
      ) : (
        <div className="files-tree">
          <FileTree
            tree={tree}
            expandedDirs={expandedDirs}
            onToggleDir={toggleDir}
            onFileClick={onFileClick}
          />
        </div>
      )}
    </div>
  );
};

export default FilesMode;

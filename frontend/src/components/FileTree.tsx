import React from 'react';

export interface FileNode {
  name: string;
  path: string;
  type: 'file' | 'directory';
  fileId?: string;
  children?: FileNode[];
}

interface FileTreeProps {
  tree: FileNode[];
  expandedDirs: Set<string>;
  onToggleDir: (path: string) => void;
  onFileClick: (file: FileNode) => void;
  level?: number;
}

/**
 * Loosely groups extensions into a handful of tones instead of one colour per
 * language. The point is to let the eye separate source from config from docs
 * at a glance, not to build a syntax highlighter.
 */
const TONE_BY_EXT: Record<string, string> = {
  ts: 'code', tsx: 'code', js: 'code', jsx: 'code', java: 'code', py: 'code',
  go: 'code', rb: 'code', rs: 'code', php: 'code', cs: 'code', c: 'code',
  cpp: 'code', h: 'code', kt: 'code', swift: 'code', scala: 'code', dart: 'code',
  css: 'style', scss: 'style', less: 'style', html: 'style', vue: 'style',
  json: 'data', yml: 'data', yaml: 'data', xml: 'data', toml: 'data',
  sql: 'data', csv: 'data', lock: 'data', properties: 'data', env: 'data',
  md: 'doc', markdown: 'doc', mdx: 'doc', txt: 'doc', rst: 'doc',
  sh: 'shell', bash: 'shell', zsh: 'shell', ps1: 'shell', bat: 'shell',
};

const toneFor = (fileName: string) => {
  const parts = fileName.split('.');
  const ext = parts.length > 1 ? parts[parts.length - 1].toLowerCase() : '';
  return TONE_BY_EXT[ext] ?? 'other';
};

const chevron = (
  <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
    <polyline points="9 18 15 12 9 6" />
  </svg>
);

const folderIcon = (
  <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
    <path d="M22 19a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h5l2 3h9a2 2 0 0 1 2 2z" />
  </svg>
);

const fileIcon = (
  <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
    <path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z" />
    <polyline points="14 2 14 8 20 8" />
  </svg>
);

/** Folders first, then files — the order people expect from a file browser. */
const sortNodes = (nodes: FileNode[]) =>
  [...nodes].sort((a, b) => {
    if (a.type !== b.type) return a.type === 'directory' ? -1 : 1;
    return a.name.localeCompare(b.name, undefined, { numeric: true });
  });

const FileTree: React.FC<FileTreeProps> = ({
  tree,
  expandedDirs,
  onToggleDir,
  onFileClick,
  level = 0,
}) => {
  if (tree.length === 0) return null;

  return (
    <div
      className={`file-tree ${level > 0 ? 'nested' : ''}`}
      style={{ '--tree-depth': level } as React.CSSProperties}
    >
      {sortNodes(tree).map((item) => {
        const isDir = item.type === 'directory';
        const isExpanded = isDir && expandedDirs.has(item.path);
        const hasChildren = !!item.children && item.children.length > 0;

        return (
          <div className="file-tree-node" key={item.path}>
            <button
              type="button"
              className={`file-tree-row ${isDir ? 'dir' : 'file'}`}
              aria-expanded={isDir ? isExpanded : undefined}
              title={item.path}
              onClick={() => (isDir ? onToggleDir(item.path) : onFileClick(item))}
            >
              <span className={`file-tree-twisty ${isExpanded ? 'open' : ''}`}>
                {isDir ? chevron : null}
              </span>
              <span
                className="file-tree-icon"
                data-tone={isDir ? 'folder' : toneFor(item.name)}
                aria-hidden="true"
              >
                {isDir ? folderIcon : fileIcon}
              </span>
              <span className="file-tree-name">{item.name}</span>
            </button>

            {isDir && isExpanded && (
              <div className="file-tree-children" role="group">
                {hasChildren ? (
                  <FileTree
                    tree={item.children!}
                    expandedDirs={expandedDirs}
                    onToggleDir={onToggleDir}
                    onFileClick={onFileClick}
                    level={level + 1}
                  />
                ) : (
                  <p className="file-tree-empty-dir">Empty</p>
                )}
              </div>
            )}
          </div>
        );
      })}
    </div>
  );
};

export default FileTree;

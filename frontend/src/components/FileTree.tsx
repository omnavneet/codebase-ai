import React from 'react';

interface FileTreeProps {
  tree: any[];
  expandedDirs: Set<string>;
  onToggleDir: (path: string) => void;
  onFileClick: (file: any) => void;
  level?: number;
}

const FileTree: React.FC<FileTreeProps> = ({ 
  tree, 
  expandedDirs, 
  onToggleDir, 
  onFileClick,
  level = 0 
}) => {
  return (
    <div>
      {tree.map((item, index) => {
        const isDir = item.type === 'directory';
        const isExpanded = expandedDirs.has(item.path);
        const paddingLeft = level * 16;
        
        return (
          <div key={index}>
            <div
              style={{
                paddingLeft: `${paddingLeft}px`,
                padding: '6px 8px',
                cursor: 'pointer',
                display: 'flex',
                alignItems: 'center',
                gap: '6px',
                fontSize: '13px',
                color: isDir ? 'var(--text-primary)' : 'var(--text-secondary)',
              }}
              onClick={() => {
                if (isDir) {
                  onToggleDir(item.path);
                } else {
                  onFileClick(item);
                }
              }}
            >
              <span>{isDir ? (isExpanded ? '📂' : '📁') : '📄'}</span>
              <span>{item.name}</span>
            </div>
            
            {isDir && isExpanded && item.children && (
              <FileTree
                tree={item.children}
                expandedDirs={expandedDirs}
                onToggleDir={onToggleDir}
                onFileClick={onFileClick}
                level={level + 1}
              />
            )}
          </div>
        );
      })}
    </div>
  );
};

export default FileTree;
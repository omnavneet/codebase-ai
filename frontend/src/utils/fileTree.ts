import type { FileNode } from '../components/FileTree';

/** Flattens a file tree into the plain list of file paths the panels need. */
export const flattenFileTree = (tree: FileNode[]): string[] => {
  const paths: string[] = [];
  const walk = (nodes: FileNode[]) => {
    nodes.forEach((node) => {
      if (node.type === 'file') {
        paths.push(node.path);
      } else if (node.children) {
        walk(node.children);
      }
    });
  };
  walk(tree);
  return paths;
};

/** Every directory path in the tree — used by "expand all" in Files mode. */
export const collectDirectoryPaths = (tree: FileNode[]): string[] => {
  const paths: string[] = [];
  const walk = (nodes: FileNode[]) => {
    nodes.forEach((node) => {
      if (node.type === 'directory') {
        paths.push(node.path);
        if (node.children) walk(node.children);
      }
    });
  };
  walk(tree);
  return paths;
};

export interface FlatFile {
  name: string;
  path: string;
  fileId?: string;
}

/**
 * Files with their ids, for the filtered Files view: once the user is typing
 * a filter the hierarchy stops mattering and a flat list is faster to scan —
 * but the id is still needed to open the file.
 */
export const collectFiles = (tree: FileNode[]): FlatFile[] => {
  const files: FlatFile[] = [];
  const walk = (nodes: FileNode[]) => {
    nodes.forEach((node) => {
      if (node.type === 'file') {
        files.push({ name: node.name, path: node.path, fileId: node.fileId });
      } else if (node.children) {
        walk(node.children);
      }
    });
  };
  walk(tree);
  return files.sort((a, b) =>
    a.path.localeCompare(b.path, undefined, { numeric: true })
  );
};

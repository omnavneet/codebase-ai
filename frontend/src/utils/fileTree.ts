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
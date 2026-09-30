import { useCallback, useEffect, useState } from 'react';
import apiClient from '../services/apiClient';
import { flattenFileTree } from '../utils/fileTree';
import { getApiErrorMessage } from '../utils/apiError';

/**
 * The project's indexed file list, shared by every mode that needs to pick a
 * file (docs, explain, review) plus the Files workspace.
 *
 * Each of those panels used to run its own copy of this request and swallow
 * failures into `console.error`, which is how they ended up showing an empty
 * dropdown with no explanation. Here the failure is part of the state so the
 * UI can say so and offer a retry.
 */
export interface ProjectFiles {
  files: string[];
  loading: boolean;
  error: string;
  reload: () => Promise<void>;
}

export function useProjectFiles(projectId: string): ProjectFiles {
  const [files, setFiles] = useState<string[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');

  const load = useCallback(async () => {
    setLoading(true);
    setError('');
    try {
      const response = await apiClient.get(`/projects/${projectId}/files`);
      setFiles(flattenFileTree(response.data));
    } catch (err) {
      console.error('Failed to fetch files:', err);
      setFiles([]);
      setError(getApiErrorMessage(err, 'Could not load the file list.'));
    } finally {
      setLoading(false);
    }
  }, [projectId]);

  const reload = useCallback(async () => {
    await load();
  }, [load]);

  useEffect(() => {
    // Async fetch — every setState here runs after the request resolves.
    // eslint-disable-next-line react-hooks/set-state-in-effect
    void load();
  }, [load]);

  return { files, loading, error, reload };
}

export default useProjectFiles;

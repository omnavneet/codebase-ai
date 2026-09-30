import React, { useCallback, useState } from 'react';
import apiClient from '../services/apiClient';
import { type Finding, sortFindings } from './findings';
import { useProjectFiles } from '../hooks/useProjectFiles';
import { getApiErrorMessage } from '../utils/apiError';
import { FilePicker, FindingsList, PanelEmpty, PanelError, PanelRun } from './PanelKit';

interface ImproveCodeResult {
  file_path: string;
  summary: string;
  findings: Finding[];
}

interface ImprovePanelProps {
  projectId: string;
}

const reviewIcon = (
  <svg width="26" height="26" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
    <polyline points="9 11 12 14 22 4" />
    <path d="M21 12v7a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h11" />
  </svg>
);

/** Findings are shown worst-first; the shared helper owns that ordering. */

/**
 * Code review for a single file. The result is a summary plus a list of
 * findings; a clean file is a valid, happy outcome, so it gets its own calm
 * state rather than an empty list.
 */
const ImprovePanel: React.FC<ImprovePanelProps> = ({ projectId }) => {
  const { files, loading: filesLoading, error: filesError, reload } = useProjectFiles(projectId);
  const [selectedFile, setSelectedFile] = useState('');
  const [result, setResult] = useState<ImproveCodeResult | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');

  const runReview = useCallback(async () => {
    if (!selectedFile || loading) return;

    setLoading(true);
    setError('');
    setResult(null);

    try {
      const response = await apiClient.post(`/projects/${projectId}/agent/improve-code`, {
        filePath: selectedFile,
      });
      setResult(response.data);
    } catch (err) {
      setError(getApiErrorMessage(err, 'This file could not be reviewed.'));
    } finally {
      setLoading(false);
    }
  }, [loading, projectId, selectedFile]);

  const sortedFindings = result ? sortFindings(result.findings) : [];

  return (
    <div className="tool-panel">
      <div className="panel-toolbar">
        <FilePicker
          id="review-file"
          label="File to review"
          value={selectedFile}
          onChange={setSelectedFile}
          files={files}
          loading={filesLoading}
          error={filesError}
          onReload={() => void reload()}
        />
        <button
          type="button"
          className="ui-btn ui-btn-primary"
          onClick={() => void runReview()}
          disabled={!selectedFile || loading}
        >
          {loading ? (
            <>
              <span className="spinner spinner-xs" aria-hidden="true" />
              Reviewing…
            </>
          ) : (
            'Review file'
          )}
        </button>
      </div>

      {error && <PanelError message={error} onRetry={() => void runReview()} />}

      {loading && (
        <PanelRun
          label="Reviewing the file"
          hint="Looking for bugs, performance, security and readability issues."
        />
      )}

      {!loading && !error && !result && (
        <PanelEmpty
          icon={reviewIcon}
          title="Review a file"
          hint="Each finding comes with the current code and a suggested replacement, so nothing is a mystery edit."
        />
      )}

      {result && (
        <div className="panel-result">
          <section className="panel-card" aria-label="Review summary">
            <div className="panel-card-header">
              <h3 className="panel-section-title">Summary</h3>
              <span className="panel-meta">
                {sortedFindings.length === 0
                  ? 'No findings'
                  : `${sortedFindings.length} ${sortedFindings.length === 1 ? 'finding' : 'findings'}`}
              </span>
            </div>
            <p className="finding-text suggestion">{result.summary}</p>
          </section>

          {sortedFindings.length === 0 ? (
            <p className="panel-success">No significant issues found in this file.</p>
          ) : (
            <FindingsList findings={sortedFindings} />
          )}
        </div>
      )}
    </div>
  );
};

export default ImprovePanel;

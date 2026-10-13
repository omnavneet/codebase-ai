import React, { useEffect, useRef, useState } from 'react';
import apiClient from '../services/apiClient';
import { getApiErrorMessage } from '../utils/apiError';
import Dialog from './Dialog';
import { ProgressBar } from './PanelKit';

interface JobStatus {
  id: string;
  projectId: string;
  status: 'queued' | 'processing' | 'ready' | 'error' | 'cancelled';
  progressPercent: number;
  attempts: number;
  errorMessage: string | null;
}

interface IndexingProgressModalProps {
  projectId: string;
  projectName: string;
  jobId: string;
  onClose: () => void;
}

/**
 * Progress popup for an indexing project. Polls the project's latest job
 * every ~2 s and renders the shared {@link ProgressBar} (determinate when a
 * real percent is known). Never navigates anywhere: closing keeps the job
 * running in the background, and the dashboard card refreshes itself.
 */
const IndexingProgressModal: React.FC<IndexingProgressModalProps> = ({
  projectId,
  projectName,
  jobId,
  onClose,
}) => {
  const [job, setJob] = useState<JobStatus | null>(null);
  const [loadError, setLoadError] = useState('');
  const [cancelRequested, setCancelRequested] = useState(false);
  const [elapsed, setElapsed] = useState(0);
  const pollTimerRef = useRef<number | undefined>(undefined);

  const terminal =
    job !== null && (job.status === 'ready' || job.status === 'error' || job.status === 'cancelled');

  // Elapsed clock — keeps ticking even between polls.
  useEffect(() => {
    const timerId = window.setInterval(() => setElapsed((value) => value + 1), 1000);
    return () => window.clearInterval(timerId);
  }, []);

  // Fetch once, then poll every 2 s while the job is not terminal.
  useEffect(() => {
    let cancelled = false;

    const fetchJob = async () => {
      try {
        const response = await apiClient.get(`/projects/${projectId}/jobs/${jobId}`);
        if (!cancelled) {
          setJob(response.data);
          setLoadError('');
        }
      } catch (err) {
        if (!cancelled) {
          setLoadError(getApiErrorMessage(err, 'Could not read indexing progress.'));
        }
      }
    };

    void fetchJob();

    if (job === null || !terminal) {
      pollTimerRef.current = window.setInterval(() => {
        void fetchJob();
      }, 2000);
    }

    return () => {
      cancelled = true;
      window.clearInterval(pollTimerRef.current);
    };
    // Re-arm only when a terminal state is left/entered, not on every tick.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [terminal, projectId, jobId]);

  const handleCancel = async () => {
    if (!job || terminal || cancelRequested) return;
    setCancelRequested(true);
    try {
      await apiClient.post(`/projects/${projectId}/jobs/${jobId}/cancel`);
    } catch (err) {
      setLoadError(getApiErrorMessage(err, 'Failed to cancel indexing job'));
      setCancelRequested(false);
    }
  };

  const stage =
    job === null
      ? 'running'
      : job.status === 'ready'
        ? 'ready'
        : job.status === 'error' || job.status === 'cancelled'
          ? 'error'
          : 'running';

  const step =
    job === null
      ? 'Starting…'
      : job.status === 'queued'
        ? `Queued${job.attempts > 0 ? ` (retry ${job.attempts})` : ''}`
        : job.status === 'processing'
          ? 'Reading and embedding the code'
          : undefined;

  return (
    <Dialog labelledBy="indexing-progress-title" onClose={onClose}>
      <div className="modal-header">
        <h2 className="modal-title" id="indexing-progress-title">
          Indexing {projectName}
        </h2>
        <p className="modal-subtitle">
          This keeps running in the background. You can close this and come back later.
        </p>
      </div>

      {loadError && (
        <p className="ui-field-error" role="alert">
          {loadError}
        </p>
      )}

      <ProgressBar
        stage={stage}
        step={step}
        percent={job?.progressPercent ?? 0}
        elapsed={elapsed}
        loading={!terminal}
        onCancel={handleCancel}
        showCancel={!terminal}
        error={
          job?.status === 'error' || job?.status === 'cancelled'
            ? job.errorMessage || 'Indexing failed'
            : undefined
        }
      />

      <div className="modal-actions">
        <button type="button" className="ui-btn ui-btn-neutral" onClick={onClose}>
          {terminal ? 'Close' : 'Keep it running'}
        </button>
      </div>
    </Dialog>
  );
};

export default IndexingProgressModal;

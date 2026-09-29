import React, { useCallback, useEffect, useRef, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { useAuth } from '../context/AuthContext';
import { useToast } from '../context/ToastContext';
import apiClient from '../services/apiClient';
import { getApiErrorMessage } from '../utils/apiError';
import NewProjectModal from '../components/NewProjectModal';
import './Dashboard.css';

interface Project {
  id: string;
  name: string;
  status: 'pending' | 'processing' | 'ready' | 'error';
  fileCount?: number;
  errorMessage?: string | null;
  createdAt: string;
}

const DashboardPage: React.FC = () => {
  const { user, logout } = useAuth();
  const { toast } = useToast();
  const navigate = useNavigate();
  const [projects, setProjects] = useState<Project[]>([]);
  const [loading, setLoading] = useState(true);
  const [showModal, setShowModal] = useState(false);
  const [menuOpen, setMenuOpen] = useState(false);
  const [deleteProjectId, setDeleteProjectId] = useState<string | null>(null);

  // A failed load must never be reported as "no projects yet".
  const [loadError, setLoadError] = useState<string | null>(null);
  // Id of the project whose delete is in flight — blocks double deletion.
  const [deletingProjectId, setDeletingProjectId] = useState<string | null>(null);
  // True once a list has been received; keeps a failed background poll from
  // replacing good data with an error panel.
  const hasProjectsRef = useRef(false);

  
  const menuRef = useRef<HTMLDivElement>(null);

  // Stable identity so polling effects can depend on it safely.
  /**
   * `silent` is used by the polling loop. Polling must not flip the list back
   * to skeletons: while a project is still indexing the dashboard would then
   * flicker every few seconds.
   */
  const fetchProjects = useCallback(async (options?: { silent?: boolean }) => {
    if (!options?.silent) setLoading(true);
    try {
      const res = await apiClient.get('/projects');
      setProjects(res.data);
      hasProjectsRef.current = true;
      setLoadError(null);
    } catch (err) {
      // Only report a load failure when there is nothing on screen to keep.
      if (!hasProjectsRef.current) {
        setLoadError(
          getApiErrorMessage(err, 'Something went wrong while loading your projects.'),
        );
      }
    } finally {
      if (!options?.silent) setLoading(false);
    }
  }, []);

  useEffect(() => {
    // Async fetch — setState only runs after the request resolves.
    // eslint-disable-next-line react-hooks/set-state-in-effect
    fetchProjects();

    const handleClickOutside = (event: MouseEvent) => {
      if (menuRef.current && !menuRef.current.contains(event.target as Node)) {
        setMenuOpen(false);
      }
      // Close any row delete confirmation when clicking elsewhere
      const target = event.target as Element;
      if (!target.closest('.project-row-confirm, .project-row-menu')) {
        setDeleteProjectId(null);
      }
    };
    
    const handleKeyDown = (event: KeyboardEvent) => {
      if (event.key !== 'Escape') return;
      // Escape backs out of the most transient layer first.
      setMenuOpen(false);
      setDeleteProjectId(null);
    };

    document.addEventListener('mousedown', handleClickOutside);
    document.addEventListener('keydown', handleKeyDown);
    return () => {
      document.removeEventListener('mousedown', handleClickOutside);
      document.removeEventListener('keydown', handleKeyDown);
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  // Poll while any project is still indexing. The effect depends on the
  // boolean, not the array — otherwise every fetch re-arms the interval.
  const hasPendingProjects = projects.some(
    (project) => project.status === 'processing' || project.status === 'pending'
  );

  useEffect(() => {
    if (!hasPendingProjects) return;

    const intervalId = window.setInterval(
      () => fetchProjects({ silent: true }),
      5000,
    );
    return () => window.clearInterval(intervalId);
  }, [hasPendingProjects, fetchProjects]);

  /**
   * Optimistic delete: the row leaves immediately, and is restored in place
   * with an explanation (plus a retry) if the server refuses.
   */
  const deleteProject = async (project: Project) => {
    if (deletingProjectId) return;

    const index = projects.findIndex(item => item.id === project.id);
    setDeletingProjectId(project.id);
    setDeleteProjectId(null);
    setProjects(current => current.filter(item => item.id !== project.id));

    try {
      await apiClient.delete(`/projects/${project.id}`);
      toast({
        tone: 'success',
        title: 'Project deleted',
        description: `${project.name} and its indexed files were removed.`,
      });
    } catch (error) {
      setProjects(current => {
        if (current.some(item => item.id === project.id)) return current;
        const restored = [...current];
        restored.splice(Math.min(index, restored.length), 0, project);
        return restored;
      });
      toast({
        tone: 'error',
        title: 'Could not delete the project',
        description: getApiErrorMessage(
          error,
          'Nothing was changed. Please try again.',
        ),
        action: { label: 'Retry', onSelect: () => void deleteProject(project) },
      });
    } finally {
      setDeletingProjectId(null);
    }
  };

  const getStatusText = (status: string) => {
    switch (status) {
      case 'ready': return 'Ready';
      case 'processing': return 'Processing';
      case 'error': return 'Error';
      case 'pending': return 'Pending';
      default: return 'Unknown';
    }
  };

  const formatDate = (dateString: string) => {
    return new Date(dateString).toLocaleDateString(undefined, {
      year: 'numeric',
      month: 'short',
      day: 'numeric'
    });
  };

  return (
    <div className="dashboard-page">
      <header className="dashboard-header">
        <div className="dashboard-logo">
          <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
            <polyline points="16 18 22 12 16 6"></polyline>
            <polyline points="8 6 2 12 8 18"></polyline>
          </svg>
          Codebase AI
        </div>
        
        <div className="user-menu" ref={menuRef}>
          <button
            type="button"
            className="user-avatar"
            onClick={() => setMenuOpen(open => !open)}
            aria-haspopup="menu"
            aria-expanded={menuOpen}
            aria-label={`Account menu for ${user?.email ?? 'your account'}`}
          >
            {user?.email?.charAt(0).toUpperCase() || 'U'}
          </button>
          
          {menuOpen && (
            <div className="user-dropdown" role="menu">
              <div className="user-dropdown-email" title={user?.email}>
                {user?.email}
              </div>
              <button
                type="button"
                className="user-dropdown-item"
                role="menuitem"
                onClick={() => navigate('/settings')}
              >
                Settings
              </button>
              <button
                type="button"
                className="user-dropdown-item"
                role="menuitem"
                onClick={logout}
              >
                Sign out
              </button>
            </div>
          )}
        </div>
      </header>

      <main className="dashboard-main">
        <div className="dashboard-title-row">
          <div>
            <h1 className="dashboard-title">Projects</h1>
            <p className="dashboard-subtitle">Manage and explore your codebases</p>
          </div>
          <button className="btn-primary" onClick={() => setShowModal(true)}>
            <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
              <line x1="12" y1="5" x2="12" y2="19"></line>
              <line x1="5" y1="12" x2="19" y2="12"></line>
            </svg>
            New Project
          </button>
        </div>

        {loading ? (
          <div className="project-list">
            {[0, 1, 2].map(i => (
              <div key={i} className="skeleton-row" />
            ))}
          </div>
        ) : loadError ? (
          <div className="empty-state" role="alert">
            <svg className="empty-state-icon" width="32" height="32" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round">
              <path d="M10.29 3.86 1.82 18a2 2 0 0 0 1.71 3h16.94a2 2 0 0 0 1.71-3L13.71 3.86a2 2 0 0 0-3.42 0z"></path>
              <line x1="12" y1="9" x2="12" y2="13"></line>
              <line x1="12" y1="17" x2="12.01" y2="17"></line>
            </svg>
            <h2 className="empty-state-title">Couldn’t load your projects</h2>
            <p className="empty-state-text">{loadError}</p>
            <button
              type="button"
              className="btn-primary"
              onClick={() => fetchProjects()}
            >
              Try again
            </button>
          </div>
        ) : projects.length === 0 ? (
          <div className="empty-state">
            <svg className="empty-state-icon" width="32" height="32" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round">
              <path d="M22 19a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h5l2 3h9a2 2 0 0 1 2 2z"></path>
            </svg>
            <h2 className="empty-state-title">No projects yet</h2>
            <p className="empty-state-text">
              Create a project and upload your codebase to start asking questions about it.
            </p>
            <button className="btn-primary" onClick={() => setShowModal(true)}>
              Create Project
            </button>
          </div>
        ) : (
          <div className="project-list">
            {projects.map((project, index) => (
              <div
                key={project.id}
                className="project-row"
                style={{ '--row-index': index } as React.CSSProperties}
                onClick={() => navigate(`/projects/${project.id}`)}
              >
                <div className="project-row-icon">
                  <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
                    <path d="M22 19a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h5l2 3h9a2 2 0 0 1 2 2z"></path>
                  </svg>
                </div>

                <div className="project-row-info">
                  {/* The row is clickable for convenience; this button is the
                      accessible, keyboard-reachable control. */}
                  <button
                    type="button"
                    className="project-row-name"
                    onClick={event => {
                      event.stopPropagation();
                      navigate(`/projects/${project.id}`);
                    }}
                  >
                    {project.name}
                  </button>
                  <div className="project-row-meta">
                    {project.status !== 'ready' && (
                      <span
                        className={`status-pill status-${project.status}`}
                        title={project.errorMessage || undefined}
                      >
                        {getStatusText(project.status)}
                      </span>
                    )}
                    <span>{project.fileCount || 0} files</span>
                    <span>Created {formatDate(project.createdAt)}</span>
                  </div>
                </div>

                {deleteProjectId === project.id ? (
                  <div
                    className="project-row-confirm"
                    role="group"
                    aria-label={`Confirm deleting ${project.name}`}
                    onClick={event => event.stopPropagation()}
                  >
                    <span>Delete permanently?</span>
                    <button
                      type="button"
                      className="confirm"
                      disabled={deletingProjectId === project.id}
                      aria-busy={deletingProjectId === project.id}
                      onClick={() => deleteProject(project)}
                    >
                      {deletingProjectId === project.id ? (
                        <>
                          <span className="spinner spinner-xs" aria-hidden="true" />
                          Deleting…
                        </>
                      ) : (
                        'Delete'
                      )}
                    </button>
                    <button
                      type="button"
                      className="cancel"
                      onClick={() => setDeleteProjectId(null)}
                    >
                      Cancel
                    </button>
                  </div>
                ) : (
                  <button
                    type="button"
                    className="project-row-menu"
                    aria-label={`Delete ${project.name}`}
                    title="Delete project"
                    disabled={deletingProjectId === project.id}
                    onClick={event => {
                      event.stopPropagation();
                      setDeleteProjectId(project.id);
                    }}
                  >
                    <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
                      <polyline points="3 6 5 6 21 6"></polyline>
                      <path d="M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6m3 0V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2"></path>
                    </svg>
                  </button>
                )}
              </div>
            ))}
          </div>
        )}
      </main>

      {showModal && (
        <NewProjectModal onClose={() => setShowModal(false)} />
      )}
    </div>
  );
};

export default DashboardPage;
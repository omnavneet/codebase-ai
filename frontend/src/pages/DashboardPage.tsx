import React, { useState, useEffect, useRef } from 'react';
import { useNavigate } from 'react-router-dom';
import { useAuth } from '../context/AuthContext';
import apiClient from '../services/apiClient';
import NewProjectModal from '../components/NewProjectModal';
import './Dashboard.css';

interface Project {
  id: string;
  name: string;
  status: 'pending' | 'processing' | 'ready' | 'error';
  fileCount?: number;
  createdAt: string;
}

const DashboardPage: React.FC = () => {
  const { user, logout } = useAuth();
  const navigate = useNavigate();
  const [projects, setProjects] = useState<Project[]>([]);
  const [loading, setLoading] = useState(true);
  const [showModal, setShowModal] = useState(false);
  const [menuOpen, setMenuOpen] = useState(false);
  const [deleteProjectId, setDeleteProjectId] = useState<string | null>(null);
  
  const menuRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    fetchProjects();

    const handleClickOutside = (event: MouseEvent) => {
      if (menuRef.current && !menuRef.current.contains(event.target as Node)) {
        setMenuOpen(false);
      }
    };
    
    document.addEventListener('mousedown', handleClickOutside);
    return () => {
      document.removeEventListener('mousedown', handleClickOutside);
    };
  }, []);

  const fetchProjects = async () => {
    try {
      setLoading(true);
      const res = await apiClient.get('/projects');
      setProjects(res.data);
    } catch (err) {
      console.error('Failed to fetch projects', err);
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    if (!projects.some(project => project.status === 'processing' || project.status === 'pending')) {
      return;
    }

    const intervalId = window.setInterval(fetchProjects, 5000);
    return () => window.clearInterval(intervalId);
  }, [projects]);

  const deleteProject = async (projectId: string) => {
    try {
      await apiClient.delete(`/projects/${projectId}`);
      setProjects(currentProjects => currentProjects.filter(project => project.id !== projectId));
      setDeleteProjectId(null);
    } catch (error) {
      console.error('Failed to delete project:', error);
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
          <div className="user-avatar" onClick={() => setMenuOpen(!menuOpen)}>
            {user?.email?.charAt(0).toUpperCase() || 'U'}
          </div>
          
          <div className={`user-dropdown ${menuOpen ? 'open' : ''}`}>
            <div className="user-dropdown-email">{user?.email}</div>
            <button className="user-dropdown-item" onClick={() => navigate('/settings')}>
              Settings
            </button>
            <button className="user-dropdown-item" onClick={logout}>
              Sign out
            </button>
          </div>
        </div>
      </header>

      <main className="dashboard-main">
        <div className="dashboard-title-row">
          <h1 className="dashboard-title">Your Projects</h1>
          <button className="btn-primary" onClick={() => setShowModal(true)}>
            + New Project
          </button>
        </div>

        {loading ? (
          <div className="project-grid">
            {[0, 1, 2].map(i => (
              <div key={i} className="skeleton-project-card" style={{ animationDelay: `${i * 100}ms` }}>
                <div className="skeleton skeleton-heading" />
                <div className="skeleton skeleton-text medium" />
                <div style={{ display: 'flex', gap: '12px', marginTop: '12px' }}>
                  <div className="skeleton skeleton-badge" />
                  <div className="skeleton skeleton-text short" style={{ flex: 1 }} />
                </div>
              </div>
            ))}
          </div>
        ) : projects.length === 0 ? (
          <div className="empty-state">
            <div className="empty-state-icon-container">📦</div>
            <h2 className="empty-state-title">No projects yet</h2>
            <p className="empty-state-text">Upload your first codebase to start chatting with it</p>
            <button className="btn-primary" onClick={() => setShowModal(true)}>
              Create Your First Project
            </button>
          </div>
        ) : (
          <div className="project-grid">
            {projects.map((project, index) => (
              <div 
                key={project.id} 
                className="project-card"
                style={{ '--card-index': index } as React.CSSProperties}
                onClick={() => navigate(`/projects/${project.id}`)}
              >
                <div className="project-card-name">{project.name}</div>
                <div className="project-card-meta">
                  <span className={`status-pill status-${project.status}`}>
                    {getStatusText(project.status)}
                  </span>
                  <span>{project.fileCount || 0} files</span>
                  <span>{formatDate(project.createdAt)}</span>
                </div>
                {deleteProjectId === project.id ? (
                  <div className="project-delete-actions" onClick={event => event.stopPropagation()}>
                    <span>Delete this project?</span>
                    <button type="button" className="project-delete-confirm" onClick={() => deleteProject(project.id)}>
                      Confirm
                    </button>
                    <button type="button" className="project-delete-cancel" onClick={() => setDeleteProjectId(null)}>
                      Cancel
                    </button>
                  </div>
                ) : (
                  <button
                    type="button"
                    className="project-delete-button"
                    onClick={event => {
                      event.stopPropagation();
                      setDeleteProjectId(project.id);
                    }}
                  >
                    Delete
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
import React, { useState, useEffect } from 'react';
import { useNavigate } from 'react-router-dom';
import { useAuth } from '../context/AuthContext';
import apiClient from '../services/apiClient';
import NewProjectModal from '../components/NewProjectModal';
import './Dashboard.css';

interface Project {
  id: string;
  name: string;
  status: string;
  fileCount: number;
  totalSizeBytes: number;
  createdAt: string;
}

const DashboardPage: React.FC = () => {
  const [projects, setProjects] = useState<Project[]>([]);
  const [loading, setLoading] = useState(true);
  const [showModal, setShowModal] = useState(false);
  const { user, logout } = useAuth();
  const navigate = useNavigate();

  useEffect(() => {
    fetchProjects();
  }, []);

  const fetchProjects = async () => {
    try {
      const response = await apiClient.get('/projects');
      setProjects(response.data);
    } catch (error) {
      console.error('Failed to fetch projects:', error);
    } finally {
      setLoading(false);
    }
  };

  const handleLogout = async () => {
    await logout();
    navigate('/login');
  };

  const formatDate = (dateString: string) => {
    const date = new Date(dateString);
    return date.toLocaleDateString('en-US', {
      month: 'short',
      day: 'numeric',
      year: 'numeric',
    });
  };

  const getStatusClass = (status: string) => {
    switch (status) {
      case 'ready': return 'status-ready';
      case 'processing': return 'status-processing';
      case 'error': return 'status-error';
      default: return 'status-pending';
    }
  };

  return (
    <div className="dashboard-container">
      <header className="dashboard-header">
        <div className="dashboard-logo">Codebase AI</div>
        <div className="dashboard-user">
          <span className="user-email">{user?.email}</span>
          <button className="logout-button" onClick={handleLogout}>
            Logout
          </button>
        </div>
      </header>

      <main className="dashboard-main">
        <div className="dashboard-title-row">
          <h1 className="dashboard-title">Your Projects</h1>
          <button 
            className="new-project-button"
            onClick={() => setShowModal(true)}
          >
            + New Project
          </button>
        </div>

        {loading ? (
          <div>Loading projects...</div>
        ) : projects.length === 0 ? (
          <div className="empty-state">
            <div className="empty-state-icon">📦</div>
            <h2 className="empty-state-title">No projects yet</h2>
            <p className="empty-state-text">
              Upload your first codebase to start chatting with it
            </p>
            <button 
              className="new-project-button"
              onClick={() => setShowModal(true)}
            >
              Create Your First Project
            </button>
          </div>
        ) : (
          <div className="project-grid">
            {projects.map((project) => (
              <div
                key={project.id}
                className="project-card"
                onClick={() => navigate(`/projects/${project.id}`)}
              >
                <h3 className="project-card-name">{project.name}</h3>
                <div className="project-card-meta">
                  <span className={`project-status ${getStatusClass(project.status)}`}>
                    {project.status}
                  </span>
                  <span>{project.fileCount} files</span>
                  <span>{formatDate(project.createdAt)}</span>
                </div>
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
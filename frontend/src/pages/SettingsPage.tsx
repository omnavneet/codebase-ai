import React, { useEffect, useRef, useState } from 'react';
import { useNavigate, Link } from 'react-router-dom';
import { useAuth } from '../context/AuthContext';
import apiClient from '../services/apiClient';
import './Settings.css';

const SettingsPage: React.FC = () => {
  const { user, logout } = useAuth();
  const navigate = useNavigate();

  const [currentPassword, setCurrentPassword] = useState('');
  const [newPassword, setNewPassword] = useState('');
  const [confirmPassword, setConfirmPassword] = useState('');
  
  const [passwordSuccess, setPasswordSuccess] = useState('');
  const [passwordError, setPasswordError] = useState('');
  
  const [showDeleteConfirm, setShowDeleteConfirm] = useState(false);
  const [menuOpen, setMenuOpen] = useState(false);
  const [notificationsEnabled, setNotificationsEnabled] = useState(true);
  const [themeEnabled, setThemeEnabled] = useState(false);
  const menuRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    const handleClickOutside = (event: MouseEvent) => {
      if (menuRef.current && !menuRef.current.contains(event.target as Node)) {
        setMenuOpen(false);
      }
    };

    document.addEventListener('mousedown', handleClickOutside);
    return () => document.removeEventListener('mousedown', handleClickOutside);
  }, []);

  const handleUpdatePassword = async (e: React.FormEvent) => {
    e.preventDefault();
    setPasswordSuccess('');
    setPasswordError('');

    if (newPassword !== confirmPassword) {
      setPasswordError('New passwords do not match');
      return;
    }

    try {
      await apiClient.put('/user/password', { currentPassword, newPassword });
      setPasswordSuccess('Password updated successfully');
      setCurrentPassword('');
      setNewPassword('');
      setConfirmPassword('');
    } catch (err: any) {
      setPasswordError(err.response?.data?.message || 'Failed to update password');
    }
  };

  const handleDeleteAccount = () => {
    setShowDeleteConfirm(true);
  };

  const confirmDeleteAccount = () => {
    // In a real app, we'd call an API to delete the account and log out
    alert('Account deleted!');
    setShowDeleteConfirm(false);
  };

  const userInitial = user?.email ? user.email.charAt(0).toUpperCase() : 'U';

  return (
    <div className="settings-page">
      <header className="settings-header">
        <Link to="/dashboard" className="settings-logo">
          <svg viewBox="0 0 24 24">
            <path d="M9.4 16.6L4.8 12l4.6-4.6L8 6l-6 6 6 6 1.4-1.4zm5.2 0l4.6-4.6-4.6-4.6L16 6l6 6-6 6-1.4-1.4z" />
          </svg>
          Codebase AI
        </Link>
        <div className="settings-user-menu" ref={menuRef}>
          <button
            type="button"
            className="settings-user-avatar"
            aria-label="Open user menu"
            aria-expanded={menuOpen}
            onClick={() => setMenuOpen(!menuOpen)}
          >
            {userInitial}
          </button>
          <div className={`settings-user-dropdown ${menuOpen ? 'open' : ''}`}>
            <div className="settings-user-email">{user?.email}</div>
            <button type="button" onClick={() => navigate('/dashboard')}>Dashboard</button>
            <button type="button" onClick={logout}>Sign out</button>
          </div>
        </div>
      </header>

      <main className="settings-main">
        <div 
          className="back-link" 
          onClick={() => navigate('/dashboard')}
        >
          &larr; Back to Dashboard
        </div>
        
        <h1 className="settings-title">Settings</h1>

        <section className="settings-section">
          <div className="section-header">
            <svg className="section-icon" viewBox="0 0 24 24" fill="currentColor">
              <path d="M12 12c2.21 0 4-1.79 4-4s-1.79-4-4-4-4 1.79-4 4 1.79 4 4 4zm0 2c-2.67 0-8 1.34-8 4v2h16v-2c0-2.66-5.33-4-8-4z" />
            </svg>
            <h2 className="section-title">Account</h2>
          </div>
          
          <div className="settings-row">
            <div>
              <div className="settings-label">Email</div>
              <div className="settings-value">{user?.email || 'user@example.com'}</div>
            </div>
          </div>
          
          <div className="settings-divider"></div>
          
          <form onSubmit={handleUpdatePassword}>
            <div className="settings-label" style={{ marginBottom: '16px' }}>Change Password</div>
            
            <div className="form-field">
              <input
                id="currentPassword"
                type="password"
                placeholder=" "
                required
                value={currentPassword}
                onChange={e => setCurrentPassword(e.target.value)}
              />
              <label htmlFor="currentPassword">Current Password</label>
            </div>
            
            <div className="form-field">
              <input
                id="newPassword"
                type="password"
                placeholder=" "
                required
                value={newPassword}
                onChange={e => setNewPassword(e.target.value)}
              />
              <label htmlFor="newPassword">New Password</label>
            </div>
            
            <div className="form-field">
              <input
                id="confirmPassword"
                type="password"
                placeholder=" "
                required
                value={confirmPassword}
                onChange={e => setConfirmPassword(e.target.value)}
              />
              <label htmlFor="confirmPassword">Confirm New Password</label>
            </div>
            
            {passwordSuccess && <div className="success-message">{passwordSuccess}</div>}
            {passwordError && <div className="error-msg">{passwordError}</div>}
            
            <button type="submit" className="btn-primary">Update Password</button>
          </form>
        </section>

        <section className="settings-section">
          <div className="section-header">
            <svg className="section-icon" viewBox="0 0 24 24" fill="currentColor">
              <path d="M19.14 12.94c.04-.3.06-.61.06-.94 0-.32-.02-.64-.06-.94l2.03-1.58c.18-.14.23-.41.12-.61l-1.92-3.32c-.12-.22-.37-.29-.59-.22l-2.39.96c-.5-.38-1.03-.7-1.62-.94l-.36-2.54c-.04-.24-.24-.41-.48-.41h-3.84c-.24 0-.43.17-.47.41l-.36 2.54c-.59.24-1.13.56-1.62.94l-2.39-.96c-.22-.08-.47 0-.59.22L2.73 8.87c-.12.21-.08.47.12.61l2.03 1.58c-.05.3-.09.63-.09.94s.02.64.06.94l-2.03 1.58c-.18.14-.23.41-.12.61l1.92 3.32c.12.22.37.29.59.22l2.39-.96c.5.38 1.03.7 1.62.94l.36 2.54c.05.24.24.41.48.41h3.84c.24 0 .43-.17.47-.41l.36-2.54c.59-.24 1.13-.56 1.62-.94l2.39.96c.22.08.47 0 .59-.22l1.92-3.32c.12-.22.07-.49-.12-.61l-2.01-1.58zM12 15.6c-1.98 0-3.6-1.62-3.6-3.6s1.62-3.6 3.6-3.6 3.6 1.62 3.6 3.6-1.62 3.6-3.6 3.6z" />
            </svg>
            <h2 className="section-title">Preferences</h2>
          </div>
          
          <div className="settings-row">
            <div>
              <div className="settings-label">Email notifications</div>
              <div className="settings-description">Receive email updates about your projects</div>
            </div>
            
            <label className="toggle-switch">
              <input
                type="checkbox"
                checked={notificationsEnabled}
                onChange={e => setNotificationsEnabled(e.target.checked)}
              />
              <span className="toggle-track">
                <span className="toggle-thumb"></span>
              </span>
            </label>
          </div>

          <div className="settings-row">
            <div>
              <div className="settings-label">Use dark theme</div>
              <div className="settings-description">Theme switching will be available soon</div>
            </div>
            <label className="toggle-switch">
              <input
                type="checkbox"
                checked={themeEnabled}
                onChange={e => setThemeEnabled(e.target.checked)}
                disabled
              />
              <span className="toggle-track">
                <span className="toggle-thumb"></span>
              </span>
            </label>
          </div>
        </section>

        <section className="settings-section danger-section">
          <div className="section-header">
            <h2 className="section-title">Danger Zone</h2>
          </div>
          
          <div className="settings-description" style={{ marginBottom: '16px' }}>
            Delete your account and all associated data. This action cannot be undone.
          </div>
          
          {!showDeleteConfirm ? (
            <button className="btn-danger-outlined" onClick={handleDeleteAccount}>
              Delete Account
            </button>
          ) : (
            <div className="confirm-delete">
              <span>Are you sure? This cannot be undone.</span>
              <button className="btn-danger-filled" onClick={confirmDeleteAccount}>
                Yes, delete
              </button>
              <button className="btn-outlined" onClick={() => setShowDeleteConfirm(false)}>
                Cancel
              </button>
            </div>
          )}
        </section>
      </main>
    </div>
  );
};

export default SettingsPage;

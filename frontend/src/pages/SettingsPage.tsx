import React, { useEffect, useRef, useState } from 'react';
import { useNavigate, Link } from 'react-router-dom';
import { useAuth } from '../context/AuthContext';
import apiClient from '../services/apiClient';
import { getApiErrorMessage } from '../utils/apiError';
import './Settings.css';

const SettingsPage: React.FC = () => {
  const { user, logout } = useAuth();
  const navigate = useNavigate();

  const [currentPassword, setCurrentPassword] = useState('');
  const [newPassword, setNewPassword] = useState('');
  const [confirmPassword, setConfirmPassword] = useState('');
  
  const [passwordSuccess, setPasswordSuccess] = useState('');
  const [passwordError, setPasswordError] = useState('');
  // Field-level validation lives apart from the form-level error so each
  // message can be attached to the input that produced it.
  const [passwordFieldErrors, setPasswordFieldErrors] = useState<{
    currentPassword?: string;
    newPassword?: string;
    confirmPassword?: string;
  }>({});
  // Blocks double submission and drives the button's loading state.
  const [savingPassword, setSavingPassword] = useState(false);
  
  const [menuOpen, setMenuOpen] = useState(false);
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
    if (savingPassword) return;

    setPasswordSuccess('');
    setPasswordError('');

    const nextErrors = {
      currentPassword: currentPassword ? undefined : 'Enter your current password.',
      newPassword: newPassword ? undefined : 'Enter a new password.',
      confirmPassword:
        newPassword === confirmPassword
          ? undefined
          : 'Passwords do not match.',
    };
    setPasswordFieldErrors(nextErrors);
    if (nextErrors.currentPassword || nextErrors.newPassword || nextErrors.confirmPassword) return;

    setSavingPassword(true);
    try {
      await apiClient.put('/user/password', { currentPassword, newPassword });
      setPasswordSuccess('Password updated successfully');
      setCurrentPassword('');
      setNewPassword('');
      setConfirmPassword('');
    } catch (err) {
      setPasswordError(getApiErrorMessage(err, 'Failed to update password'));
    } finally {
      setSavingPassword(false);
    }
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
          {menuOpen && (
            <div className="settings-user-dropdown open" role="menu">
              <div className="settings-user-email">{user?.email}</div>
              <button type="button" role="menuitem" onClick={() => navigate('/dashboard')}>
                Dashboard
              </button>
              <button type="button" role="menuitem" onClick={logout}>
                Sign out
              </button>
            </div>
          )}
        </div>
      </header>

      <main className="settings-main">
        <button type="button" className="back-link" onClick={() => navigate('/dashboard')}>
          <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
            <line x1="19" y1="12" x2="5" y2="12" />
            <polyline points="12 19 5 12 12 5" />
          </svg>
          Back to projects
        </button>
        
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
          
          <form className="settings-form" onSubmit={handleUpdatePassword} noValidate>
            <div className="settings-label" style={{ marginBottom: '16px' }}>Change Password</div>

            <div className="form-field">
              <input
                id="currentPassword"
                type="password"
                placeholder=" "
                required
                autoComplete="current-password"
                value={currentPassword}
                onChange={e => {
                  setCurrentPassword(e.target.value);
                  if (passwordFieldErrors.currentPassword) {
                    setPasswordFieldErrors(prev => ({ ...prev, currentPassword: undefined }));
                  }
                }}
                aria-invalid={passwordFieldErrors.currentPassword ? true : undefined}
                aria-describedby={passwordFieldErrors.currentPassword ? 'currentPassword-error' : undefined}
              />
              <label htmlFor="currentPassword">Current Password</label>
            </div>
            {passwordFieldErrors.currentPassword && (
              <p className="ui-field-error" id="currentPassword-error" role="alert">
                {passwordFieldErrors.currentPassword}
              </p>
            )}

            <div className="form-field">
              <input
                id="newPassword"
                type="password"
                placeholder=" "
                required
                autoComplete="new-password"
                value={newPassword}
                onChange={e => {
                  setNewPassword(e.target.value);
                  if (passwordFieldErrors.newPassword || passwordFieldErrors.confirmPassword) {
                    setPasswordFieldErrors(prev => ({
                      ...prev,
                      newPassword: undefined,
                      confirmPassword: undefined,
                    }));
                  }
                }}
                aria-invalid={passwordFieldErrors.newPassword ? true : undefined}
                aria-describedby={passwordFieldErrors.newPassword ? 'newPassword-error' : undefined}
              />
              <label htmlFor="newPassword">New Password</label>
            </div>
            {passwordFieldErrors.newPassword && (
              <p className="ui-field-error" id="newPassword-error" role="alert">
                {passwordFieldErrors.newPassword}
              </p>
            )}

            <div className="form-field">
              <input
                id="confirmPassword"
                type="password"
                placeholder=" "
                required
                autoComplete="new-password"
                value={confirmPassword}
                onChange={e => {
                  setConfirmPassword(e.target.value);
                  if (passwordFieldErrors.confirmPassword) {
                    setPasswordFieldErrors(prev => ({ ...prev, confirmPassword: undefined }));
                  }
                }}
                aria-invalid={passwordFieldErrors.confirmPassword ? true : undefined}
                aria-describedby={passwordFieldErrors.confirmPassword ? 'confirmPassword-error' : undefined}
              />
              <label htmlFor="confirmPassword">Confirm New Password</label>
            </div>
            {passwordFieldErrors.confirmPassword && (
              <p className="ui-field-error" id="confirmPassword-error" role="alert">
                {passwordFieldErrors.confirmPassword}
              </p>
            )}

            {passwordSuccess && <div className="success-message">{passwordSuccess}</div>}
            {passwordError && <div className="error-msg" role="alert">{passwordError}</div>}

            <button
              type="submit"
              className="ui-btn ui-btn-primary"
              disabled={savingPassword}
              aria-busy={savingPassword}
              title={savingPassword ? 'Updating…' : 'Update your password'}
            >
              {savingPassword ? (
                <>
                  <span className="spinner spinner-xs" aria-hidden="true" />
                  Updating…
                </>
              ) : (
                'Update password'
              )}
            </button>
          </form>
        </section>
      </main>
    </div>
  );
};

export default SettingsPage;

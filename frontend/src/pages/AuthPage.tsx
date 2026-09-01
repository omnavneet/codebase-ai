import React, { useState, useEffect } from 'react';
import { useNavigate, useSearchParams } from 'react-router-dom';
import { useAuth } from '../context/AuthContext';
import './Auth.css';

interface AuthPageProps {
  initialMode?: 'login' | 'register';
}

const AuthPage: React.FC<AuthPageProps> = ({ initialMode = 'login' }) => {
  const [searchParams, setSearchParams] = useSearchParams();
  const navigate = useNavigate();
  const { login, register, isAuthenticated, loading } = useAuth();
  
  const queryMode = searchParams.get('mode');
  const startMode = (queryMode === 'register' || queryMode === 'login') ? queryMode : initialMode;
  const [mode, setMode] = useState<'login' | 'register'>(startMode);
  
  // Login State
  const [loginEmail, setLoginEmail] = useState('');
  const [loginPassword, setLoginPassword] = useState('');
  const [loginError, setLoginError] = useState('');
  
  // Register State
  const [registerEmail, setRegisterEmail] = useState('');
  const [registerPassword, setRegisterPassword] = useState('');
  const [registerConfirm, setRegisterConfirm] = useState('');
  const [registerError, setRegisterError] = useState('');

  useEffect(() => {
    if (isAuthenticated) {
      navigate('/dashboard');
    }
  }, [isAuthenticated, navigate]);

  const handleModeSwitch = (newMode: 'login' | 'register') => {
    setMode(newMode);
    setSearchParams({ mode: newMode });
    setLoginError('');
    setRegisterError('');
  };

  const handleLogin = async (e: React.FormEvent) => {
    e.preventDefault();
    setLoginError('');
    try {
      await login(loginEmail, loginPassword);
      // navigation handled by useEffect
    } catch (err: any) {
      setLoginError(err.message || 'Login failed');
    }
  };

  const handleRegister = async (e: React.FormEvent) => {
    e.preventDefault();
    setRegisterError('');
    if (registerPassword !== registerConfirm) {
      setRegisterError('Passwords do not match');
      return;
    }
    try {
      await register(registerEmail, registerPassword);
      // navigation handled by useEffect
    } catch (err: any) {
      setRegisterError(err.message || 'Registration failed');
    }
  };

  const isLoginActive = mode === 'login';

  return (
    <div className="auth-page">
      <div className="auth-branding">
        <svg xmlns="http://www.w3.org/2000/svg" width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" className="auth-logo-icon">
          <polyline points="16 18 22 12 16 6"></polyline>
          <polyline points="8 6 2 12 8 18"></polyline>
        </svg>
        <span className="auth-logo-text">Codebase AI</span>
      </div>

      <div className="auth-card">
        <div className="auth-tabs">
          <button
            type="button"
            className={`auth-tab ${isLoginActive ? 'active' : ''}`}
            onClick={() => handleModeSwitch('login')}
          >
            Sign in
          </button>
          <button
            type="button"
            className={`auth-tab ${!isLoginActive ? 'active' : ''}`}
            onClick={() => handleModeSwitch('register')}
          >
            Create account
          </button>
          <div 
            className="auth-tab-indicator" 
            style={{ 
              left: isLoginActive ? '0%' : '50%',
              width: '50%'
            }} 
          />
        </div>

        <div className="auth-forms-container">
          <div 
            className="auth-forms-slider"
            style={{ transform: `translateX(${isLoginActive ? '0%' : '-50%'})` }}
          >
            {/* Login Form */}
            <form className="auth-form" onSubmit={handleLogin}>
              <div className="form-field">
                <input
                  id="login-email"
                  type="email"
                  placeholder=" "
                  required
                  value={loginEmail}
                  onChange={e => setLoginEmail(e.target.value)}
                  className={loginError ? 'error-input' : ''}
                />
                <label htmlFor="login-email">Email</label>
              </div>
              <div className="form-field">
                <input
                  id="login-password"
                  type="password"
                  placeholder=" "
                  required
                  value={loginPassword}
                  onChange={e => setLoginPassword(e.target.value)}
                  className={loginError ? 'error-input' : ''}
                />
                <label htmlFor="login-password">Password</label>
              </div>
              
              {loginError && <div className="auth-error">{loginError}</div>}
              
              <button type="submit" className="auth-submit" disabled={loading}>
                {loading ? (
                  <span className="loading-dots">
                    <span className="dot">.</span><span className="dot">.</span><span className="dot">.</span>
                  </span>
                ) : 'Sign In'}
              </button>

              <div className="auth-footer">
                Don't have an account?{' '}
                <button type="button" className="auth-footer-link" onClick={() => handleModeSwitch('register')}>
                  Create one
                </button>
              </div>
            </form>

            {/* Register Form */}
            <form className="auth-form" onSubmit={handleRegister}>
              <div className="form-field">
                <input
                  id="register-email"
                  type="email"
                  placeholder=" "
                  required
                  value={registerEmail}
                  onChange={e => setRegisterEmail(e.target.value)}
                  className={registerError ? 'error-input' : ''}
                />
                <label htmlFor="register-email">Email</label>
              </div>
              <div className="form-field">
                <input
                  id="register-password"
                  type="password"
                  placeholder=" "
                  required
                  value={registerPassword}
                  onChange={e => setRegisterPassword(e.target.value)}
                  className={registerError ? 'error-input' : ''}
                />
                <label htmlFor="register-password">Password</label>
              </div>
              <div className="form-field">
                <input
                  id="register-confirm"
                  type="password"
                  placeholder=" "
                  required
                  value={registerConfirm}
                  onChange={e => setRegisterConfirm(e.target.value)}
                  className={registerError ? 'error-input' : ''}
                />
                <label htmlFor="register-confirm">Confirm Password</label>
              </div>

              {registerError && <div className="auth-error">{registerError}</div>}

              <button type="submit" className="auth-submit" disabled={loading}>
                {loading ? (
                  <span className="loading-dots">
                    <span className="dot">.</span><span className="dot">.</span><span className="dot">.</span>
                  </span>
                ) : 'Create Account'}
              </button>

              <div className="auth-footer">
                Already have an account?{' '}
                <button type="button" className="auth-footer-link" onClick={() => handleModeSwitch('login')}>
                  Sign in
                </button>
              </div>
            </form>
          </div>
        </div>
      </div>
    </div>
  );
};

export default AuthPage;

import React, { useState, useEffect } from 'react';
import { useNavigate, useSearchParams } from 'react-router-dom';
import { useAuth } from '../context/AuthContext';
import { getApiErrorMessage } from '../utils/apiError';
import './Auth.css';

interface AuthPageProps {
  initialMode?: 'login' | 'register';
}

/**
 * Shape check only. Anything stricter rejects addresses that are actually
 * deliverable, and the backend is the authority on whether one exists.
 */
const EMAIL_PATTERN = /^[^\s@]+@[^\s@]+\.[^\s@]{2,}$/;


const AuthPage: React.FC<AuthPageProps> = ({ initialMode = 'login' }) => {
  const [searchParams, setSearchParams] = useSearchParams();
  const navigate = useNavigate();
  const { login, register, resendVerification, isAuthenticated } = useAuth();
  
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

  // Set once registration succeeds: the account exists but is not usable yet, so the
  // form is replaced by a "check your email" panel with a resend action.
  const [verificationEmail, setVerificationEmail] = useState('');
  const [verificationMessage, setVerificationMessage] = useState('');
  const [resendNotice, setResendNotice] = useState('');
  const [resending, setResending] = useState(false);

  /**
   * Which submit is currently in flight. Drives the button's loading state and
   * blocks double submissions. Note this is deliberately NOT `useAuth().loading`
   * — that flag only covers restoring an existing session, so reusing it left
   * the submit button live and silent during a login request.
   */
  const [submitting, setSubmitting] = useState<'login' | 'register' | null>(null);

  // Field-level validation lives apart from the form-level error so each
  // message can be attached to the input that produced it.
  const [loginFieldErrors, setLoginFieldErrors] = useState<{
    email?: string;
    password?: string;
  }>({});
  const [registerFieldErrors, setRegisterFieldErrors] = useState<{
    email?: string;
    password?: string;
    confirm?: string;
  }>({});


  useEffect(() => {
    if (isAuthenticated) {
      navigate('/dashboard');
    }
  }, [isAuthenticated, navigate]);

  const handleModeSwitch = (newMode: 'login' | 'register') => {
    // Switching panels invalidates every message from the previous one,
    // otherwise an error left over from one form lingers under the other.
    setMode(newMode);
    setSearchParams({ mode: newMode });
    setLoginError('');
    setRegisterError('');
    setLoginFieldErrors({});
    setRegisterFieldErrors({});
    setVerificationEmail('');
    setResendNotice('');
  };

  const handleLogin = async (e: React.FormEvent) => {
    e.preventDefault();
    if (submitting) return;

    const nextErrors = {
      email: EMAIL_PATTERN.test(loginEmail.trim())
        ? undefined
        : 'Enter a valid email address.',
      password: loginPassword ? undefined : 'Enter your password.',
    };
    setLoginFieldErrors(nextErrors);
    setLoginError('');
    if (nextErrors.email || nextErrors.password) return;

    setSubmitting('login');
    try {
      await login(loginEmail.trim(), loginPassword);
      // Success keeps the button in its loading state: the redirect above
      // unmounts this page, so clearing it here would only cause a flash.
    } catch (err) {
      setLoginError(
        getApiErrorMessage(err, 'Sign in failed. Check your email and password.'),
      );
      setSubmitting(null);
    }
  };

  const handleRegister = async (e: React.FormEvent) => {
    e.preventDefault();
    if (submitting) return;

    const nextErrors = {
      email: EMAIL_PATTERN.test(registerEmail.trim())
        ? undefined
        : 'Enter a valid email address.',
      password: registerPassword ? undefined : 'Choose a password.',
      confirm:
        registerPassword === registerConfirm
          ? undefined
          : 'Passwords do not match.',
    };
    setRegisterFieldErrors(nextErrors);
    setRegisterError('');
    if (nextErrors.email || nextErrors.password || nextErrors.confirm) return;

    setSubmitting('register');
    try {
      const result = await register(registerEmail.trim(), registerPassword);
      // No session yet: the account becomes usable once the emailed link is opened.
      setVerificationEmail(result.email);
      setVerificationMessage(result.message);
      setResendNotice('');
    } catch (err) {
      setRegisterError(
        getApiErrorMessage(err, 'Could not create your account.'),
      );
    } finally {
      setSubmitting(null);
    }
  };

  const handleResendVerification = async (email: string) => {
    setResending(true);
    setResendNotice('');
    try {
      setResendNotice(await resendVerification(email));
    } catch (err) {
      setResendNotice(getApiErrorMessage(err, 'Could not send the verification email'));
    } finally {
      setResending(false);
    }
  };

  // A 403 on login means the credentials were right but the address is unverified.
  const loginNeedsVerification = loginError.toLowerCase().includes('verification');

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
            <form className="auth-form" onSubmit={handleLogin} noValidate>
              <div className="auth-field-group">
                <div className="form-field">
                  <input
                    id="login-email"
                    type="email"
                    placeholder=" "
                    required
                    autoComplete="email"
                    autoFocus
                    value={loginEmail}
                    onChange={e => {
                      setLoginEmail(e.target.value);
                      if (loginFieldErrors.email) {
                        setLoginFieldErrors(prev => ({ ...prev, email: undefined }));
                      }
                    }}
                    aria-invalid={loginFieldErrors.email ? true : undefined}
                    aria-describedby={loginFieldErrors.email ? 'login-email-error' : undefined}
                    className={loginFieldErrors.email ? 'error-input' : ''}
                  />
                  <label htmlFor="login-email">Email</label>
                </div>
                {loginFieldErrors.email && (
                  <p className="ui-field-error" id="login-email-error" role="alert">
                    {loginFieldErrors.email}
                  </p>
                )}
              </div>
              <div className="auth-field-group">
                <div className="form-field">
                  <input
                    id="login-password"
                    type="password"
                    placeholder=" "
                    required
                    autoComplete="current-password"
                    value={loginPassword}
                    onChange={e => {
                      setLoginPassword(e.target.value);
                      if (loginFieldErrors.password) {
                        setLoginFieldErrors(prev => ({ ...prev, password: undefined }));
                      }
                    }}
                    aria-invalid={loginFieldErrors.password ? true : undefined}
                    aria-describedby={loginFieldErrors.password ? 'login-password-error' : undefined}
                    className={loginFieldErrors.password ? 'error-input' : ''}
                  />
                  <label htmlFor="login-password">Password</label>
                </div>
                {loginFieldErrors.password && (
                  <p className="ui-field-error" id="login-password-error" role="alert">
                    {loginFieldErrors.password}
                  </p>
                )}
              </div>
              
              {loginError && (
                <div className="auth-error" role="alert">
                  {loginError}
                </div>
              )}

              {loginNeedsVerification && (
                <button
                  type="button"
                  className="auth-resend-link"
                  disabled={resending}
                  onClick={() => handleResendVerification(loginEmail.trim())}
                >
                  {resending ? (
                    <>
                      <span className="spinner spinner-xs" aria-hidden="true" />
                      Sending…
                    </>
                  ) : (
                    'Resend verification email'
                  )}
                </button>
              )}

              {resendNotice && <div className="auth-notice">{resendNotice}</div>}

              <button
                type="submit"
                className="auth-submit"
                disabled={submitting !== null}
                aria-busy={submitting === 'login'}
              >
                {submitting === 'login' ? (
                  <>
                    <span className="spinner spinner-sm" aria-hidden="true" />
                    <span>Signing in…</span>
                  </>
                ) : (
                  'Sign In'
                )}
              </button>

              <div className="auth-footer">
                Don't have an account?{' '}
                <button type="button" className="auth-footer-link" onClick={() => handleModeSwitch('register')}>
                  Create one
                </button>
              </div>
            </form>

            {/* Register Form */}
            {verificationEmail ? (
              <div className="auth-form">
                <div className="auth-notice">
                  <strong>{verificationMessage || 'Check your email'}</strong>
                  <p>
                    We sent a verification link to <b>{verificationEmail}</b>. Open it to
                    activate your account — the link expires in 24 hours.
                  </p>
                </div>

                {resendNotice && <div className="auth-notice">{resendNotice}</div>}

                <button
                  type="button"
                  className="auth-submit"
                  disabled={resending}
                  aria-busy={resending}
                  onClick={() => handleResendVerification(verificationEmail)}
                >
                  {resending ? (
                    <>
                      <span className="spinner spinner-sm" aria-hidden="true" />
                      <span>Sending…</span>
                    </>
                  ) : (
                    'Resend verification email'
                  )}
                </button>

                <div className="auth-footer">
                  Already verified?{' '}
                  <button type="button" className="auth-footer-link" onClick={() => handleModeSwitch('login')}>
                    Sign in
                  </button>
                </div>
              </div>
            ) : (
            <form className="auth-form" onSubmit={handleRegister} noValidate>
              <div className="auth-field-group">
                <div className="form-field">
                  <input
                    id="register-email"
                    type="email"
                    placeholder=" "
                    required
                    autoComplete="email"
                    value={registerEmail}
                    onChange={e => {
                      setRegisterEmail(e.target.value);
                      if (registerFieldErrors.email) {
                        setRegisterFieldErrors(prev => ({ ...prev, email: undefined }));
                      }
                    }}
                    aria-invalid={registerFieldErrors.email ? true : undefined}
                    aria-describedby={registerFieldErrors.email ? 'register-email-error' : undefined}
                    className={registerFieldErrors.email ? 'error-input' : ''}
                  />
                  <label htmlFor="register-email">Email</label>
                </div>
                {registerFieldErrors.email && (
                  <p className="ui-field-error" id="register-email-error" role="alert">
                    {registerFieldErrors.email}
                  </p>
                )}
              </div>
              <div className="auth-field-group">
                <div className="form-field">
                  <input
                    id="register-password"
                    type="password"
                    placeholder=" "
                    required
                    autoComplete="new-password"
                    value={registerPassword}
                    onChange={e => {
                      setRegisterPassword(e.target.value);
                      if (registerFieldErrors.password || registerFieldErrors.confirm) {
                        setRegisterFieldErrors(prev => ({
                          ...prev,
                          password: undefined,
                          confirm: undefined,
                        }));
                      }
                    }}
                    aria-invalid={registerFieldErrors.password ? true : undefined}
                    aria-describedby={registerFieldErrors.password ? 'register-password-error' : undefined}
                    className={registerFieldErrors.password ? 'error-input' : ''}
                  />
                  <label htmlFor="register-password">Password</label>
                </div>
                {registerFieldErrors.password && (
                  <p className="ui-field-error" id="register-password-error" role="alert">
                    {registerFieldErrors.password}
                  </p>
                )}
              </div>
              <div className="auth-field-group">
                <div className="form-field">
                  <input
                    id="register-confirm"
                    type="password"
                    placeholder=" "
                    required
                    autoComplete="new-password"
                    value={registerConfirm}
                    onChange={e => {
                      setRegisterConfirm(e.target.value);
                      if (registerFieldErrors.confirm) {
                        setRegisterFieldErrors(prev => ({ ...prev, confirm: undefined }));
                      }
                    }}
                    aria-invalid={registerFieldErrors.confirm ? true : undefined}
                    aria-describedby={registerFieldErrors.confirm ? 'register-confirm-error' : undefined}
                    className={registerFieldErrors.confirm ? 'error-input' : ''}
                  />
                  <label htmlFor="register-confirm">Confirm Password</label>
                </div>
                {registerFieldErrors.confirm && (
                  <p className="ui-field-error" id="register-confirm-error" role="alert">
                    {registerFieldErrors.confirm}
                  </p>
                )}
              </div>

              {registerError && (
                <div className="auth-error" role="alert">
                  {registerError}
                </div>
              )}

              <button
                type="submit"
                className="auth-submit"
                disabled={submitting !== null}
                aria-busy={submitting === 'register'}
              >
                {submitting === 'register' ? (
                  <>
                    <span className="spinner spinner-sm" aria-hidden="true" />
                    <span>Creating account…</span>
                  </>
                ) : (
                  'Create Account'
                )}
              </button>

              <div className="auth-footer">
                Already have an account?{' '}
                <button type="button" className="auth-footer-link" onClick={() => handleModeSwitch('login')}>
                  Sign in
                </button>
              </div>
            </form>
            )}
          </div>
        </div>
      </div>
    </div>
  );
};

export default AuthPage;

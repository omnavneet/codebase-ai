import React, { useEffect, useRef, useState } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import { useAuth } from '../context/AuthContext';
import apiClient from '../services/apiClient';
import { getApiErrorMessage } from '../utils/apiError';
import './Auth.css';

type VerificationState = 'verifying' | 'success' | 'error';

/**
 * Landing page for the link in the verification email. The token is consumed through
 * the API and immediately stripped from the address bar, so it cannot leak through a
 * Referer header or the browser history. Living in the SPA (rather than pointing the
 * email straight at the API) also keeps mail scanners that prefetch links from
 * consuming a single-use token before the user clicks it.
 */
const VerifyEmailPage: React.FC = () => {
  const [searchParams, setSearchParams] = useSearchParams();
  const { resendVerification } = useAuth();

  // Captured once on mount: the effect below removes the token from the URL.
  const tokenRef = useRef<string | null>(searchParams.get('token'));
  const verificationStartedRef = useRef(false);
  const [state, setState] = useState<VerificationState>('verifying');
  const [message, setMessage] = useState('');
  const [email, setEmail] = useState('');
  const [resendNotice, setResendNotice] = useState('');
  const [resending, setResending] = useState(false);

  useEffect(() => {
    const token = tokenRef.current;
    setSearchParams({}, { replace: true });

    if (!token) {
      setState('error');
      setMessage('This link is missing its verification token. Request a new email below.');
      return;
    }

    // React StrictMode re-runs effects in development. Verification is a
    // single-use server operation, so never submit the same token twice.
    if (verificationStartedRef.current) {
      return;
    }
    verificationStartedRef.current = true;

    let cancelled = false;

    apiClient
      .get('/auth/verify-email', { params: { token } })
      .then(response => {
        if (cancelled) return;
        setState('success');
        setMessage(response.data?.message ?? 'Email verified. You can now sign in.');
      })
      .catch(error => {
        if (cancelled) return;
        setState('error');
        setMessage(getApiErrorMessage(error, 'This verification link is invalid or has expired.'));
      });

    return () => {
      cancelled = true;
    };
  }, [setSearchParams]);

  const handleResend = async (event: React.FormEvent) => {
    event.preventDefault();
    setResending(true);
    setResendNotice('');
    try {
      setResendNotice(await resendVerification(email));
    } catch (error) {
      setResendNotice(getApiErrorMessage(error, 'Could not send the verification email.'));
    } finally {
      setResending(false);
    }
  };

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
        {state === 'verifying' && (
          <div className="auth-notice">
            <strong>Verifying your email</strong>
            <p>One moment while we confirm your link.</p>
          </div>
        )}

        {state === 'success' && (
          <div className="auth-notice success">
            <strong>Email verified</strong>
            <p>{message}</p>
          </div>
        )}

        {state === 'error' && (
          <>
            <div className="auth-notice error">
              <strong>Verification failed</strong>
              <p>{message}</p>
            </div>

            <form className="auth-verify-form" onSubmit={handleResend}>
              <div className="form-field">
                <input
                  id="resend-email"
                  type="email"
                  placeholder=" "
                  required
                  value={email}
                  onChange={event => setEmail(event.target.value)}
                />
                <label htmlFor="resend-email">Email</label>
              </div>

              {resendNotice && <div className="auth-notice">{resendNotice}</div>}

              <button type="submit" className="auth-submit" disabled={resending}>
                {resending ? (
                  <span className="loading-dots">
                    <span className="dot">.</span><span className="dot">.</span><span className="dot">.</span>
                  </span>
                ) : 'Send a new verification link'}
              </button>
            </form>
          </>
        )}

        <div className="auth-footer">
          <Link className="auth-footer-link" to="/auth?mode=login">Go to sign in</Link>
        </div>
      </div>
    </div>
  );
};

export default VerifyEmailPage;

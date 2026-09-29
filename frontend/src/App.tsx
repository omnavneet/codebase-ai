import { BrowserRouter, Routes, Route, Navigate } from 'react-router-dom';
import { AuthProvider, useAuth } from './context/AuthContext';
import { ToastProvider } from './context/ToastContext';
import AuthPage from './pages/AuthPage';
import VerifyEmailPage from './pages/VerifyEmailPage';
import DashboardPage from './pages/DashboardPage';
import ChatPage from './pages/ChatPage';
import SettingsPage from './pages/SettingsPage';
import NotFoundPage from './pages/NotFoundPage';
import TopProgressBar from './components/TopProgressBar';

/**
 * Shown while the session is being restored. It mirrors the dashboard shell
 * (header + content column) rather than centring a spinner, so the swap to
 * real content reads as the same page filling in instead of a page change.
 */
const SessionSkeleton: React.FC = () => (
  <div className="app-boot">
    <TopProgressBar visible />
    <div className="app-boot-header">
      <div className="skeleton skeleton-brand" />
      <div className="skeleton skeleton-circle" style={{ width: 32, height: 32 }} />
    </div>
    <div className="app-boot-main">
      <div className="skeleton skeleton-heading" />
      <div className="skeleton skeleton-text medium" />
      <div className="app-boot-rows">
        {[0, 1, 2, 3].map((row) => (
          <div key={row} className="skeleton skeleton-row" />
        ))}
      </div>
    </div>
  </div>
);

const ProtectedRoute: React.FC<{ children: React.ReactNode }> = ({ children }) => {
  const { isAuthenticated, loading } = useAuth();

  if (loading) return <SessionSkeleton />;

  return isAuthenticated ? <>{children}</> : <Navigate to="/login" />;
};

function App() {
  return (
    <ToastProvider>
      <AuthProvider>
        <BrowserRouter>
          <Routes>
            <Route path="/login" element={<Navigate to="/auth?mode=login" replace />} />
            <Route path="/register" element={<Navigate to="/auth?mode=register" replace />} />
            <Route path="/auth" element={<AuthPage />} />
            {/* Target of the link in the verification email; the token is consumed here. */}
            <Route path="/auth/verify-email" element={<VerifyEmailPage />} />
            <Route
              path="/dashboard"
              element={
                <ProtectedRoute>
                  <DashboardPage />
                </ProtectedRoute>
              }
            />
            <Route
              path="/settings"
              element={
                <ProtectedRoute>
                  <SettingsPage />
                </ProtectedRoute>
              }
            />
            <Route
              path="/projects/:projectId"
              element={
                <ProtectedRoute>
                  <ChatPage />
                </ProtectedRoute>
              }
            />
            <Route path="/" element={<Navigate to="/dashboard" replace />} />
            <Route path="*" element={<NotFoundPage />} />
          </Routes>
        </BrowserRouter>
      </AuthProvider>
    </ToastProvider>
  );
}

export default App;

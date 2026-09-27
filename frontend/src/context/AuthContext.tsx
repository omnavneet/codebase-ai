import React, { createContext, useContext, useState, useEffect } from 'react';
import apiClient from '../services/apiClient';

interface User {
  email: string;
  userId: string;
}

interface AuthContextType {
  isAuthenticated: boolean;
  user: User | null;
  loading: boolean;
  login: (email: string, password: string) => Promise<void>;
  register: (email: string, password: string) => Promise<void>;
  logout: () => Promise<void>;
}

const AuthContext = createContext<AuthContextType | undefined>(undefined);

export const AuthProvider: React.FC<{ children: React.ReactNode }> = ({ children }) => {
  const [user, setUser] = useState<User | null>(null);
  const [isAuthenticated, setIsAuthenticated] = useState(false);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    const initAuth = async () => {
      const token = localStorage.getItem('access_token');

      if (!token) {
        setLoading(false);
        return;
      }

      // The request interceptor injects the bearer token from localStorage;
      // there is no separate default header to maintain.
      try {
        // Fetch user info
        const response = await apiClient.get('/user/me');
        setUser({
          email: response.data.email,
          userId: response.data.id
        });
        setIsAuthenticated(true);
      } catch {
        // Token expired or invalid, try refresh
        try {
          const refreshResponse = await apiClient.post('/auth/refresh');
          const newToken = refreshResponse.data.accessToken;

          localStorage.setItem('access_token', newToken);

          const userResponse = await apiClient.get('/user/me');
          setUser({
            email: userResponse.data.email,
            userId: userResponse.data.id
          });
          setIsAuthenticated(true);
        } catch {
          // Refresh failed, clear everything
          localStorage.removeItem('access_token');
          setUser(null);
          setIsAuthenticated(false);
        }
      } finally {
        setLoading(false);
      }
    };

    initAuth();
  }, []);

  const login = async (email: string, password: string) => {
    const response = await apiClient.post('/auth/login', { email, password });
    const token = response.data.accessToken;

    localStorage.setItem('access_token', token);

    setUser({ email: response.data.email, userId: response.data.userId });
    setIsAuthenticated(true);
  };

  const register = async (email: string, password: string) => {
    const response = await apiClient.post('/auth/register', { email, password });
    const token = response.data.accessToken;

    localStorage.setItem('access_token', token);

    setUser({ email: response.data.email, userId: response.data.userId });
    setIsAuthenticated(true);
  };

  const logout = async () => {
    try {
      await apiClient.post('/auth/logout');
    } finally {
      localStorage.removeItem('access_token');
      setUser(null);
      setIsAuthenticated(false);
    }
  };

  return (
    <AuthContext.Provider value={{ 
      isAuthenticated, 
      user, 
      loading,
      login, 
      register, 
      logout 
    }}>
      {children}
    </AuthContext.Provider>
  );
};

// The context file also exports the useAuth hook; the Fast-Refresh rule
// only allows component exports, so it is disabled for this single export.
// eslint-disable-next-line react-refresh/only-export-components
export const useAuth = () => {
  const context = useContext(AuthContext);
  if (!context) {
    throw new Error('useAuth must be used within AuthProvider');
  }
  return context;
};
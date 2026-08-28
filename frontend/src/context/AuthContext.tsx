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

      try {
        // Set token in axios headers
        apiClient.defaults.headers.common['Authorization'] = `Bearer ${token}`;
        
        // Fetch user info
        const response = await apiClient.get('/user/me');
        setUser({ 
          email: response.data.email, 
          userId: response.data.id 
        });
        setIsAuthenticated(true);
      } catch (error) {
        // Token expired or invalid, try refresh
        try {
          const refreshResponse = await apiClient.post('/auth/refresh');
          const newToken = refreshResponse.data.accessToken;
          
          localStorage.setItem('access_token', newToken);
          apiClient.defaults.headers.common['Authorization'] = `Bearer ${newToken}`;
          
          const userResponse = await apiClient.get('/user/me');
          setUser({ 
            email: userResponse.data.email, 
            userId: userResponse.data.id 
          });
          setIsAuthenticated(true);
        } catch (refreshError) {
          // Refresh failed, clear everything
          localStorage.removeItem('access_token');
          delete apiClient.defaults.headers.common['Authorization'];
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
    apiClient.defaults.headers.common['Authorization'] = `Bearer ${token}`;
    
    setUser({ email: response.data.email, userId: response.data.userId });
    setIsAuthenticated(true);
  };

  const register = async (email: string, password: string) => {
    const response = await apiClient.post('/auth/register', { email, password });
    const token = response.data.accessToken;
    
    localStorage.setItem('access_token', token);
    apiClient.defaults.headers.common['Authorization'] = `Bearer ${token}`;
    
    setUser({ email: response.data.email, userId: response.data.userId });
    setIsAuthenticated(true);
  };

  const logout = async () => {
    try {
      await apiClient.post('/auth/logout');
    } finally {
      localStorage.removeItem('access_token');
      delete apiClient.defaults.headers.common['Authorization'];
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

export const useAuth = () => {
  const context = useContext(AuthContext);
  if (!context) {
    throw new Error('useAuth must be used within AuthProvider');
  }
  return context;
};
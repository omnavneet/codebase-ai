import axios from 'axios';

const apiClient = axios.create({
  baseURL: '/api',
  headers: {
    'Content-Type': 'application/json',
  },
  withCredentials: true, // Important for cookies
});

// Request interceptor
apiClient.interceptors.request.use((config) => {
  const token = localStorage.getItem('access_token');
  if (token) {
    config.headers.Authorization = `Bearer ${token}`;
  }
  return config;
});

/**
 * Exchange the refresh cookie for a fresh access token and persist it.
 * Exported so non-axios consumers (the SSE stream client) can reuse it.
 */
export const refreshAccessToken = async (): Promise<string> => {
  const response = await axios.post(
    '/api/auth/refresh',
    {},
    { withCredentials: true }
  );
  const newToken: string | undefined = response.data?.accessToken;
  if (!newToken) {
    throw new Error('Token refresh returned no access token');
  }
  localStorage.setItem('access_token', newToken);
  return newToken;
};

// Single-flight refresh: when several requests fail with 401 simultaneously,
// they all await the same refresh call instead of racing each other (which
// would rotate the refresh cookie out from under the concurrent requests).
let refreshInFlight: Promise<string> | null = null;

// Response interceptor for token refresh
apiClient.interceptors.response.use(
  (response) => response,
  async (error) => {
    const originalRequest = error.config ?? {};

    const isAuthEndpoint =
      typeof originalRequest.url === 'string' &&
      originalRequest.url.includes('/auth/');
    const shouldRefresh =
      (error.response?.status === 401 || error.response?.status === 403) &&
      !isAuthEndpoint &&
      !originalRequest._retry;

    if (shouldRefresh) {
      originalRequest._retry = true;

      try {
        refreshInFlight = refreshInFlight ?? refreshAccessToken();
        const newToken = await refreshInFlight;
        originalRequest.headers.Authorization = `Bearer ${newToken}`;
        return apiClient(originalRequest);
      } catch (refreshError) {
        localStorage.removeItem('access_token');
        // Hard reset to the auth screen (the /login route is a redirect stub).
        window.location.href = '/auth?mode=login';
        return Promise.reject(refreshError);
      } finally {
        refreshInFlight = null;
      }
    }

    return Promise.reject(error);
  }
);

export default apiClient;
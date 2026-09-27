import { isAxiosError } from 'axios';

/**
 * Extract a human-readable message from an API error. Handles axios error
 * responses (`{ message }` bodies), native Errors, and unknown throwables so
 * `catch` blocks never need `any`.
 */
export const getApiErrorMessage = (
  error: unknown,
  fallback: string,
): string => {
  if (isAxiosError(error)) {
    const data: unknown = error.response?.data;
    if (
      data &&
      typeof data === 'object' &&
      'message' in data &&
      typeof (data as { message: unknown }).message === 'string'
    ) {
      const message = (data as { message: string }).message;
      if (message) return message;
    }
    if (error.message) return error.message;
  }
  if (error instanceof Error && error.message) return error.message;
  return fallback;
};

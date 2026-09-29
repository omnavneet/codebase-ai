import React, {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useRef,
  useState,
} from 'react';
import Toaster from '../components/Toaster';

export type ToastTone = 'success' | 'error' | 'info';

/** Optional inline action, e.g. "Retry" or "Undo". */
export interface ToastAction {
  label: string;
  onSelect: () => void;
}

export interface ToastOptions {
  title: string;
  description?: string;
  tone?: ToastTone;
  /**
   * Auto-dismiss delay in ms. `0` keeps the toast until it is dismissed by
   * the user — reserve that for failures the user must acknowledge.
   */
  duration?: number;
  action?: ToastAction;
}

/** A toast after the provider has resolved its tone and duration. */
export interface ResolvedToast extends ToastOptions {
  id: number;
  tone: ToastTone;
  duration: number;
}

interface ToastContextValue {
  toast: (options: ToastOptions) => number;
  dismiss: (id: number) => void;
  /** Pause auto-dismissal, e.g. while the pointer rests on the toast. */
  pause: (id: number) => void;
  /** Re-arm auto-dismissal after a pause. */
  resume: (id: number, duration: number) => void;
}

const ToastContext = createContext<ToastContextValue | undefined>(undefined);

/**
 * Never stack more than a few: past this the notification area starts
 * covering the work surface, which is worse than losing the oldest message.
 */
const MAX_VISIBLE = 3;

/** Must stay in sync with the exit transition in Toast.css. */
export const TOAST_EXIT_MS = 160;

const DEFAULT_DURATION: Record<ToastTone, number> = {
  success: 4000,
  info: 5000,
  // Errors linger: they usually carry the only explanation of what went wrong.
  error: 6500,
};

export const ToastProvider: React.FC<{ children: React.ReactNode }> = ({
  children,
}) => {
  const [toasts, setToasts] = useState<ResolvedToast[]>([]);
  const [leavingIds, setLeavingIds] = useState<number[]>([]);
  const nextIdRef = useRef(1);
  // One pending auto-dismiss timer per toast id.
  const timersRef = useRef(new Map<number, number>());

  const clearTimer = useCallback((id: number) => {
    const handle = timersRef.current.get(id);
    if (handle !== undefined) {
      window.clearTimeout(handle);
      timersRef.current.delete(id);
    }
  }, []);

  const remove = useCallback((id: number) => {
    setToasts((current) => current.filter((item) => item.id !== id));
    setLeavingIds((current) => current.filter((item) => item !== id));
  }, []);

  /**
   * Dismissing is two-phase so the exit transition can play: the toast is
   * flagged as leaving (which triggers the CSS state) and only unmounted
   * after the transition has finished.
   */
  const dismiss = useCallback(
    (id: number) => {
      clearTimer(id);
      setLeavingIds((current) =>
        current.includes(id) ? current : [...current, id],
      );
      window.setTimeout(() => remove(id), TOAST_EXIT_MS);
    },
    [clearTimer, remove],
  );

  const toast = useCallback(
    (options: ToastOptions) => {
      const id = nextIdRef.current++;
      const tone = options.tone ?? 'info';
      const duration = options.duration ?? DEFAULT_DURATION[tone];

      setToasts((current) =>
        // Drop the oldest silently rather than animating: it is the message
        // the user has had longest to read.
        [...current, { ...options, id, tone, duration }].slice(-MAX_VISIBLE),
      );

      if (duration > 0) {
        timersRef.current.set(
          id,
          window.setTimeout(() => dismiss(id), duration),
        );
      }

      return id;
    },
    [dismiss],
  );

  const pause = useCallback((id: number) => clearTimer(id), [clearTimer]);

  const resume = useCallback(
    (id: number, duration: number) => {
      if (duration > 0) {
        timersRef.current.set(
          id,
          window.setTimeout(() => dismiss(id), duration),
        );
      }
    },
    [dismiss],
  );

  useEffect(() => {
    // Timers outlive renders; clear them all when the provider unmounts.
    const timers = timersRef.current;
    return () => {
      timers.forEach((handle) => window.clearTimeout(handle));
      timers.clear();
    };
  }, []);

  const value = useMemo<ToastContextValue>(
    () => ({ toast, dismiss, pause, resume }),
    [toast, dismiss, pause, resume],
  );

  return (
    <ToastContext.Provider value={value}>
      {children}
      <Toaster
        toasts={toasts}
        leavingIds={leavingIds}
        onDismiss={dismiss}
        onPause={pause}
        onResume={resume}
      />
    </ToastContext.Provider>
  );
};

// The context file also exports the hook; the Fast-Refresh rule only allows
// component exports, so it is disabled for this single export.
// eslint-disable-next-line react-refresh/only-export-components
export const useToast = () => {
  const context = useContext(ToastContext);
  if (!context) {
    throw new Error('useToast must be used within ToastProvider');
  }
  return context;
};

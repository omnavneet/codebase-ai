import React from 'react';
import type { ResolvedToast } from '../context/ToastContext';
import './Toast.css';

interface ToasterProps {
  toasts: ResolvedToast[];
  leavingIds: number[];
  onDismiss: (id: number) => void;
  onPause: (id: number) => void;
  onResume: (id: number, duration: number) => void;
}

const CheckIcon = () => (
  <svg width="11" height="11" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="3" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
    <polyline points="20 6 9 17 4 12" />
  </svg>
);

const AlertIcon = () => (
  <svg width="11" height="11" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="3" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
    <line x1="12" y1="8" x2="12" y2="13" />
    <line x1="12" y1="17" x2="12.01" y2="17" />
  </svg>
);

const InfoIcon = () => (
  <svg width="11" height="11" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="3" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
    <line x1="12" y1="11" x2="12" y2="16" />
    <line x1="12" y1="7" x2="12.01" y2="7" />
  </svg>
);

const CloseIcon = () => (
  <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
    <line x1="18" y1="6" x2="6" y2="18" />
    <line x1="6" y1="6" x2="18" y2="18" />
  </svg>
);

const TONE_ICON = {
  success: <CheckIcon />,
  error: <AlertIcon />,
  info: <InfoIcon />,
};

/**
 * Renders the notification stack. Toasts are announced politely, except
 * failures, which use `role="alert"` so assistive tech interrupts for them.
 * Auto-dismissal pauses while the pointer or focus rests inside a toast, so
 * a message can never disappear mid-read.
 */
const Toaster: React.FC<ToasterProps> = ({
  toasts,
  leavingIds,
  onDismiss,
  onPause,
  onResume,
}) => {
  if (toasts.length === 0) return null;

  return (
    <div className="toaster" role="region" aria-label="Notifications">
      {toasts.map((item) => (
        <div
          key={item.id}
          className="toast"
          data-tone={item.tone}
          data-leaving={leavingIds.includes(item.id) || undefined}
          role={item.tone === 'error' ? 'alert' : 'status'}
          onMouseEnter={() => onPause(item.id)}
          onMouseLeave={() => onResume(item.id, item.duration)}
          onFocus={() => onPause(item.id)}
          onBlur={() => onResume(item.id, item.duration)}
        >
          <span className="toast-icon">{TONE_ICON[item.tone]}</span>

          <div className="toast-body">
            <div className="toast-title">{item.title}</div>
            {item.description && (
              <div className="toast-description">{item.description}</div>
            )}

            {item.action && (
              <div className="toast-footer">
                <button
                  type="button"
                  className="toast-action"
                  onClick={() => {
                    item.action?.onSelect();
                    onDismiss(item.id);
                  }}
                >
                  {item.action.label}
                </button>
              </div>
            )}
          </div>

          <button
            type="button"
            className="toast-dismiss"
            aria-label={`Dismiss: ${item.title}`}
            onClick={() => onDismiss(item.id)}
          >
            <CloseIcon />
          </button>
        </div>
      ))}
    </div>
  );
};

export default Toaster;

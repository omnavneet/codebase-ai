import React, { useEffect, useRef } from 'react';
import './Modal.css';

interface DialogProps {
  /** id of the element inside `children` that titles the dialog. */
  labelledBy: string;
  onClose: () => void;
  children: React.ReactNode;
  size?: 'default' | 'wide';
}

const FOCUSABLE =
  'button:not([disabled]), [href], input:not([disabled]), select:not([disabled]), textarea:not([disabled]), [tabindex]:not([tabindex="-1"])';

/**
 * The accessible shell every dialog in the app should use.
 *
 * Both existing modals rendered a clickable overlay with no dialog semantics:
 * no Escape to dismiss, no focus management, background content still
 * scrollable and still tabbable. This centralises all of it so the modals only
 * have to describe their content.
 */
const Dialog: React.FC<DialogProps> = ({
  labelledBy,
  onClose,
  children,
  size = 'default',
}) => {
  const panelRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    const opener = document.activeElement as HTMLElement | null;
    const panel = panelRef.current;
    panel?.focus();

    // Lock the page behind the dialog so scrolling can't strand the panel.
    const previousOverflow = document.body.style.overflow;
    document.body.style.overflow = 'hidden';

    const handleKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape') {
        onClose();
        return;
      }
      if (event.key !== 'Tab' || !panel) return;

      const focusable = Array.from(panel.querySelectorAll<HTMLElement>(FOCUSABLE));
      if (focusable.length === 0) return;
      const first = focusable[0];
      const last = focusable[focusable.length - 1];
      const active = document.activeElement;

      // Keep the tab order inside the panel while it is open.
      if (event.shiftKey && (active === first || !panel.contains(active))) {
        event.preventDefault();
        last.focus();
      } else if (!event.shiftKey && active === last) {
        event.preventDefault();
        first.focus();
      }
    };

    document.addEventListener('keydown', handleKeyDown);
    return () => {
      document.removeEventListener('keydown', handleKeyDown);
      document.body.style.overflow = previousOverflow;
      // Return the caret to whatever opened the dialog.
      opener?.focus?.();
    };
  }, [onClose]);

  return (
    <div
      className="modal-overlay"
      onMouseDown={(event) => {
        // Only a press on the backdrop itself closes it — never a drag that
        // happens to end outside the panel.
        if (event.target === event.currentTarget) onClose();
      }}
    >
      <div
        ref={panelRef}
        className={`modal-content modal-${size}`}
        role="dialog"
        aria-modal="true"
        aria-labelledby={labelledBy}
        tabIndex={-1}
      >
        {children}
      </div>
    </div>
  );
};

export default Dialog;

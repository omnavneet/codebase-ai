import React, { useEffect, useRef } from "react"

interface ChatComposerProps {
  value: string
  onChange: (value: string) => void
  onSend: () => void
  loading: boolean
}

const ChatComposer: React.FC<ChatComposerProps> = ({
  value,
  onChange,
  onSend,
  loading,
}) => {
  const textareaRef = useRef<HTMLTextAreaElement>(null)

  // Auto-resize textarea
  useEffect(() => {
    if (textareaRef.current) {
      textareaRef.current.style.height = "auto"
      textareaRef.current.style.height = `${Math.min(
        textareaRef.current.scrollHeight,
        180,
      )}px`
    }
  }, [value])

  const handleKeyDown = (e: React.KeyboardEvent) => {
    if (e.key === "Enter" && !e.shiftKey) {
      e.preventDefault()
      onSend()
    }
  }

  return (
    <div className="chat-composer-wrapper">
      <div className="chat-composer-card">
        <textarea
          ref={textareaRef}
          className="chat-composer-input"
          value={value}
          onChange={(e) => onChange(e.target.value)}
          onKeyDown={handleKeyDown}
          placeholder="Ask anything…"
          rows={1}
        />

        <div className="composer-bottom-bar">
          <div className="composer-hints"></div>

          <div className="composer-actions">
            <span className="keyboard-shortcut-hint">
              {loading ? (
                "Generating answer…"
              ) : (
                <>
                  Press <kbd>↵</kbd> to send
                </>
              )}
            </span>
            <button
              className="btn-send-message"
              onClick={onSend}
              disabled={!value.trim() || loading}
              aria-label={loading ? "Generating answer" : "Send message"}
              title={loading ? "Generating answer…" : "Send message"}
            >
              {loading ? (
                <span className="loading-spinner-tiny"></span>
              ) : (
                <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round" strokeLinejoin="round">
                  <line x1="12" y1="19" x2="12" y2="5"></line>
                  <polyline points="5 12 12 5 19 12"></polyline>
                </svg>
              )}
            </button>
          </div>
        </div>
      </div>
    </div>
  )
}

export default ChatComposer

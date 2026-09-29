import React, { useEffect, useRef } from 'react'

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
  const canSend = value.trim().length > 0 && !loading

  // Auto-resize: the box grows with the question, up to a readable maximum,
  // then scrolls internally.
  useEffect(() => {
    const node = textareaRef.current
    if (!node) return
    node.style.height = 'auto'
    node.style.height = `${Math.min(node.scrollHeight, 180)}px`
  }, [value])

  const handleKeyDown = (event: React.KeyboardEvent<HTMLTextAreaElement>) => {
    if (event.key === 'Enter' && !event.shiftKey) {
      event.preventDefault()
      // Enter on an empty box does nothing rather than sending whitespace.
      if (canSend) onSend()
      return
    }
    // Escape drops focus without discarding what was typed.
    if (event.key === 'Escape') {
      event.currentTarget.blur()
    }
  }

  return (
    <div className="chat-composer-wrapper">
      <div className="chat-composer-card">
        <textarea
          ref={textareaRef}
          className="chat-composer-input"
          value={value}
          onChange={(event) => onChange(event.target.value)}
          onKeyDown={handleKeyDown}
          placeholder="Ask anything about this codebase…"
          aria-label="Ask a question about this codebase"
          rows={1}
          // Deliberately never disabled: typing the next question while an
          // answer streams is normal, only sending is blocked.
          spellCheck={false}
        />

        <div className="composer-bottom-bar">
          <div className="composer-hints">
            {loading ? (
              // Replaces the static hint in place, so the row never changes
              // height when an answer starts streaming.
              <span className="composer-status" role="status">
                <span className="spinner spinner-xs" aria-hidden="true" />
                Generating…
              </span>
            ) : (
              <span className="composer-context-hint">
                Answers cite files from this project
              </span>
            )}
          </div>

          <div className="composer-actions">
            <span className="keyboard-shortcut-hint">
              <kbd className="ui-kbd">↵</kbd> send · <kbd className="ui-kbd">⇧</kbd>
              <kbd className="ui-kbd">↵</kbd> new line
            </span>
            <button
              type="button"
              className="btn-send-message"
              onClick={onSend}
              disabled={!canSend}
              aria-label={loading ? 'Generating answer' : 'Send message'}
              title={loading ? 'Generating answer…' : 'Send message'}
            >
              {loading ? (
                <span className="loading-spinner-tiny" aria-hidden="true"></span>
              ) : (
                <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
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

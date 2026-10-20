import React, { useEffect, useRef } from 'react'

interface ChatComposerProps {
  value: string
  onChange: (value: string) => void
  onSend: () => void
  /** Aborts the in-flight stream; the composer unlocks immediately. */
  onStop: () => void
  loading: boolean
  deepInvestigate: boolean
  onToggleDeep: () => void
  /** null when unknown; otherwise estimated percentage of context budget used. */
  contextPercent: number | null
}

const ChatComposer: React.FC<ChatComposerProps> = ({
  value,
  onChange,
  onSend,
  onStop,
  loading,
  deepInvestigate,
  onToggleDeep,
  contextPercent,
}) => {
  const textareaRef = useRef<HTMLTextAreaElement>(null)
  const canSend = value.trim().length > 0 && !loading
  // Tracks the loading edge so focus can follow the conversation: typing
  // resumes in the textarea as soon as a send starts (and after Stop).
  const wasLoadingRef = useRef(false)

  // Auto-resize: the box grows with the question, up to about six lines,
  // then scrolls internally.
  useEffect(() => {
    const node = textareaRef.current
    if (!node) return
    node.style.height = 'auto'
    const lineHeight = parseFloat(window.getComputedStyle(node).lineHeight) || 21
    const verticalPadding = 22 // padding: 14px top + 8px bottom (Chat.css)
    node.style.height = `${Math.min(node.scrollHeight, lineHeight * 6 + verticalPadding)}px`
  }, [value])

  // Focus returns to the textarea whenever a run starts — whether the send
  // came from Enter, the Send button, a quick prompt or Regenerate.
  useEffect(() => {
    if (loading && !wasLoadingRef.current) {
      textareaRef.current?.focus()
    }
    wasLoadingRef.current = loading
  }, [loading])

  // Determine context color and class
  const contextClass = contextPercent !== null && contextPercent >= 85 ? "critical" : ""
  const contextPercentDisplay = contextPercent !== null ? `${contextPercent}%` : ""

  const handleSend = () => {
    // Enter on an empty (or whitespace-only) box does nothing.
    if (!canSend) return
    onSend()
  }

  const handleStop = () => {
    onStop()
    // The composer unlocks immediately; put the caret back for the next line.
    textareaRef.current?.focus()
  }

  const handleKeyDown = (event: React.KeyboardEvent<HTMLTextAreaElement>) => {
    if (event.key === 'Enter' && !event.shiftKey) {
      event.preventDefault()
      handleSend()
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
                {deepInvestigate ? 'Investigating…' : 'Generating…'}
              </span>
            ) : (
              <span className="composer-context-hint">
                Answers cite files from this project
              </span>
            )}
          </div>

          <div className="composer-actions">
            <button
              type="button"
              className={`deep-toggle ${deepInvestigate ? "active" : ""}`}
              onClick={onToggleDeep}
              aria-pressed={deepInvestigate}
              title="Deep investigate: multi-step agent run with tool calls"
            >
              <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
                <polygon points="13 2 3 14 12 14 11 22 21 10 12 10 13 2" />
              </svg>
              <span>Deep investigate</span>
            </button>
            <span className="keyboard-shortcut-hint">
              <kbd className="ui-kbd">↵</kbd> send · <kbd className="ui-kbd">⇧</kbd>
              <kbd className="ui-kbd">↵</kbd> new line
            </span>
            {loading ? (
              // The Send button becomes Stop while an answer streams: same
              // slot, so the row never changes height.
              <button
                type="button"
                className="btn-send-message btn-stop"
                onClick={handleStop}
                aria-label="Stop generating"
                title="Stop generating"
              >
                <svg width="12" height="12" viewBox="0 0 24 24" fill="currentColor" aria-hidden="true">
                  <rect x="6" y="6" width="12" height="12" rx="2" />
                </svg>
              </button>
            ) : (
              <button
                type="button"
                className="btn-send-message"
                onClick={handleSend}
                disabled={!canSend}
                aria-label="Send message"
                title="Send message"
              >
                <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
                  <line x1="12" y1="19" x2="12" y2="5"></line>
                  <polyline points="5 12 12 5 19 12"></polyline>
                </svg>
              </button>
            )}
          </div>
        </div>

        {/* Context indicator */}
        {contextPercent !== null && (
          <span className={`composer-context-indicator ${contextClass}`} title="Older messages are summarized automatically">
            Context {contextPercentDisplay}
          </span>
        )}
      </div>
    </div>
  )
}

export default ChatComposer

import React, { useEffect, useRef, useState } from 'react'
import type { Citation, Message } from './types'

interface MessageListProps {
  messages: Message[]
  loading: boolean
  onCitationClick: (citation: Citation) => void
  /**
   * Scroll container owned by the workspace. Passing it in lets auto-scroll
   * respect where the user has scrolled to instead of yanking the view down
   * on every streamed token.
   */
  scrollContainerRef: React.RefObject<HTMLDivElement | null>
}

/** How close to the bottom the user must be for auto-scroll to keep following. */
const STICK_THRESHOLD_PX = 120

const formatMessageTime = (iso: string) => {
  const date = new Date(iso)
  if (Number.isNaN(date.getTime())) return ''
  return date.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })
}

const FileIcon = () => (
  <svg width="11" height="11" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
    <path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z" />
    <polyline points="14 2 14 8 20 8" />
  </svg>
)

const CopyIcon = () => (
  <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
    <rect x="9" y="9" width="13" height="13" rx="2" ry="2" />
    <path d="M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1" />
  </svg>
)

const CheckIcon = () => (
  <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
    <polyline points="20 6 9 17 4 12" />
  </svg>
)

/**
 * The conversation stream. The optimistic assistant placeholder doubles as the
 * typing indicator: while its content is empty it renders the dots INSIDE its
 * own bubble — never as a separate row — so only one ✦ block can ever exist per
 * answer. Once tokens start arriving the dots give way to text plus a caret, so
 * the move from "thinking" to "writing" is continuous.
 */
const MessageList: React.FC<MessageListProps> = ({
  messages,
  loading,
  onCitationClick,
  scrollContainerRef,
}) => {
  const endRef = useRef<HTMLDivElement>(null)
  // Whether the view should keep following the answer as it grows.
  const stickToBottomRef = useRef(true)
  const [copiedId, setCopiedId] = useState<string | null>(null)

  // Track the user's scroll position: only auto-scroll while they are already
  // near the bottom, so reading back through an answer is never interrupted.
  useEffect(() => {
    const container = scrollContainerRef.current
    if (!container) return

    const handleScroll = () => {
      const distanceFromBottom =
        container.scrollHeight - container.scrollTop - container.clientHeight
      stickToBottomRef.current = distanceFromBottom < STICK_THRESHOLD_PX
    }

    container.addEventListener('scroll', handleScroll, { passive: true })
    handleScroll()
    return () => container.removeEventListener('scroll', handleScroll)
  }, [scrollContainerRef])

  useEffect(() => {
    if (!stickToBottomRef.current) return

    const reduceMotion = window.matchMedia('(prefers-reduced-motion: reduce)').matches
    const streaming = loading && messages[messages.length - 1]?.role === 'assistant'

    // Instant while tokens stream (a smooth animation per token would queue
    // dozens of competing scrolls and stutter); smooth otherwise.
    endRef.current?.scrollIntoView({
      behavior: reduceMotion || streaming ? 'auto' : 'smooth',
      block: 'end',
    })
  }, [messages, loading])

  const copyMessage = async (message: Message) => {
    try {
      await navigator.clipboard.writeText(message.content)
      setCopiedId(message.id)
      window.setTimeout(
        () => setCopiedId(current => (current === message.id ? null : current)),
        1600,
      )
    } catch {
      // Clipboard access can be blocked (insecure origin, denied permission).
      // There is nothing to retry, so stay quiet rather than alarm the user.
    }
  }

  return (
    <div className="messages-stream">
      {/* Streaming status for assistive tech, kept out of the visual flow. */}
      <div className="sr-only" role="status" aria-live="polite">
        {loading ? 'Generating an answer' : ''}
      </div>
      {messages.map((message, index) => {
        const isLast = index === messages.length - 1
        const isTyping =
          loading && message.role === 'assistant' && message.content === '' && isLast
        const isStreaming =
          loading && message.role === 'assistant' && message.content !== '' && isLast
        const canCopy = !isTyping && message.content !== ''

        return (
          <div key={message.id} className={`message-row ${message.role}`}>
            <div className="message-avatar" aria-hidden="true">
              {message.role === 'assistant' ? '✦' : '👤'}
            </div>

            <div className="message-bubble">
              {isTyping ? (
                <div className="typing-bubble">
                  <span className="typing-dot"></span>
                  <span className="typing-dot"></span>
                  <span className="typing-dot"></span>
                </div>
              ) : (
                <div className="message-content">
                  {message.content}
                  {isStreaming && <span className="stream-caret" aria-hidden="true" />}
                </div>
              )}

              <div className="message-footer">
                {!isTyping && message.createdAt && (
                  <span className="message-time">
                    {formatMessageTime(message.createdAt)}
                  </span>
                )}

                {/* Contextual actions: revealed on hover or keyboard focus, so
                    a long answer is never padded out with permanent buttons.
                    The reserved row height means revealing them never shifts
                    the message. */}
                {canCopy && (
                  <div className="message-actions">
                    <button
                      type="button"
                      className="message-action"
                      onClick={() => copyMessage(message)}
                      aria-label={
                        copiedId === message.id
                          ? 'Answer copied to clipboard'
                          : 'Copy answer to clipboard'
                      }
                    >
                      {copiedId === message.id ? <CheckIcon /> : <CopyIcon />}
                      <span>{copiedId === message.id ? 'Copied' : 'Copy'}</span>
                    </button>
                  </div>
                )}
              </div>
              {message.citations && message.citations.length > 0 && (
                <div className="citations-container">
                  <span className="citations-header">Sources</span>
                  <div className="citations-list">
                    {message.citations.map((citation, idx) => (
                      <button
                        type="button"
                        key={`${message.id}-citation-${idx}`}
                        className="citation-chip"
                        onClick={() => onCitationClick(citation)}
                        title={`View ${citation.file_path || 'source file'} in context`}
                      >
                        <span className="citation-icon">
                          <FileIcon />
                        </span>
                        <span className="citation-path">
                          {citation.file_path || 'source file'}
                        </span>
                        <span className="citation-lines">
                          L{citation.start_line}–{citation.end_line}
                        </span>
                      </button>
                    ))}
                  </div>
                </div>
              )}
            </div>
          </div>
        )
      })}
      <div ref={endRef} />
    </div>
  )
}

export default MessageList

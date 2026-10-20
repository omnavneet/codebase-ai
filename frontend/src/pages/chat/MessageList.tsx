import React, { useEffect, useRef, useState } from 'react'
import type { Citation, Message } from './types'
import MarkdownAnswer from '../../components/MarkdownAnswer'
import type { CitationClickHandler } from '../../components/citationUtils'
import { copyText } from '../../utils/clipboard'

interface MessageListProps {
  messages: Message[]
  loading: boolean
  /** Terminal reason from a failed stream; rendered with a Retry action. */
  error?: string
  /** Sources chips under an answer: open the snippet preview (unchanged). */
  onCitationClick: (citation: Citation) => void
  /** Inline `file.ext:line` citations in the answer text: open the file. */
  onOpenCitation: CitationClickHandler
  /**
   * The project's file paths — inline citations only become links when they
   * resolve against this list.
   */
  filePaths: string[]
  /**
   * Scroll container owned by the workspace. Passing it in lets auto-scroll
   * respect where the user has scrolled to instead of yanking the view down
   * on every streamed token.
   */
  scrollContainerRef: React.RefObject<HTMLDivElement | null>
  /** Re-sends the last question — backs both Retry and Regenerate. */
  onRetry: () => void
  onRegenerate: () => void
}

/** How close to the bottom the user must be for auto-scroll to keep following. */
const STICK_THRESHOLD_PX = 80

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
  error,
  onCitationClick,
  onOpenCitation,
  filePaths,
  scrollContainerRef,
  onRetry,
  onRegenerate,
}) => {
  const endRef = useRef<HTMLDivElement>(null)
  // Whether the view should keep following the answer as it grows.
  const stickToBottomRef = useRef(true)
  // True only while the "Jump to latest" control should be visible.
  const [showJump, setShowJump] = useState(false)
  // Watched on each message/error change so fresh content can force-follow
  // even when the user had scrolled away earlier.
  const prevCountRef = useRef(0)
  const prevErrorRef = useRef(false)
  const [copiedId, setCopiedId] = useState<string | null>(null)

  // Track the user's scroll position: only auto-scroll while they are already
  // near the bottom, so reading back through an answer is never interrupted.
  // Scrolling away raises the jump control instead of yanking the view down.
  useEffect(() => {
    const container = scrollContainerRef.current
    if (!container) return

    const handleScroll = () => {
      const distanceFromBottom =
        container.scrollHeight - container.scrollTop - container.clientHeight
      const nearBottom = distanceFromBottom < STICK_THRESHOLD_PX
      stickToBottomRef.current = nearBottom
      setShowJump(!nearBottom)
    }

    // Listener only — never invoked synchronously here; the force-follow
    // below establishes the initial position after content arrives.
    container.addEventListener('scroll', handleScroll, { passive: true })
    return () => container.removeEventListener('scroll', handleScroll)
  }, [scrollContainerRef])

  // Follow the newest text only while the user is near the bottom. New
  // content (a sent message, a loaded session, a stream error) always pulls
  // the view down; scrolling up pauses following until they jump back.
  // Scrolling is coalesced into ONE instant jump per animation frame — a
  // smooth animation per token queues dozens of competing scrolls and
  // jitters. The scroll event this raises then re-syncs showJump.
  useEffect(() => {
    const isNewContent =
      messages.length !== prevCountRef.current ||
      (Boolean(error) && !prevErrorRef.current)
    prevCountRef.current = messages.length
    prevErrorRef.current = Boolean(error)
    if (isNewContent) {
      stickToBottomRef.current = true
    }
    if (!stickToBottomRef.current) return

    const frame = window.requestAnimationFrame(() => {
      endRef.current?.scrollIntoView({ block: 'end' })
    })
    return () => window.cancelAnimationFrame(frame)
  }, [messages, error])

  const jumpToLatest = () => {
    stickToBottomRef.current = true
    setShowJump(false)
    endRef.current?.scrollIntoView({ block: 'end' })
  }

  const copyMessage = async (message: Message) => {
    // Raw markdown, via navigator.clipboard with a textarea fallback for
    // plain-HTTP dev servers; "Copied" shows for about 1.5s.
    if (await copyText(message.content)) {
      setCopiedId(message.id)
      window.setTimeout(
        () => setCopiedId(current => (current === message.id ? null : current)),
        1500,
      )
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
        // Regenerate exists on the LAST answer only, and only at rest —
        // re-asking while a stream is running would race it.
        const canRegenerate = message.role === 'assistant' && isLast && !loading

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
                  {message.role === 'assistant' ? (
                    <MarkdownAnswer
                      content={message.content}
                      onCitationClick={onOpenCitation}
                      filePaths={filePaths}
                    />
                  ) : (
                    message.content
                  )}
                  {isStreaming && <span className="stream-caret" aria-hidden="true" />}
                </div>
              )}

              <div className="message-footer">
                {!isTyping && message.createdAt && (
                  <span className="message-time">
                    {formatMessageTime(message.createdAt)}
                  </span>
                )}
                {message.stopped && message.role === 'assistant' && !loading && (
                  <span className="stopped-note">Stopped</span>
                )}

                {/* Contextual actions: revealed on hover or keyboard focus, so
                    a long answer is never padded out with permanent buttons.
                    The reserved row height means revealing them never shifts
                    the message. */}
                {(canCopy || canRegenerate) && (
                  <div className="message-actions">
                    {canCopy && (
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
                    )}
                    {canRegenerate && (
                      <button
                        type="button"
                        className="message-action"
                        onClick={onRegenerate}
                        aria-label="Regenerate this answer"
                        title="Re-ask the last question"
                      >
                        <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
                          <polyline points="23 4 23 10 17 10" />
                          <path d="M20.49 15a9 9 0 1 1-2.12-9.36L23 10" />
                        </svg>
                        <span>Regenerate</span>
                      </button>
                    )}
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
              {message.investigation && (
                <div className="investigation-inline" aria-label="Investigation trace">
                  {message.investigation.truncated && (
                    <div className="panel-warning" role="status">
                      The run hit its iteration limit, so this answer may be incomplete.
                    </div>
                  )}
                  <div className="investigation-meta">
                    <span className="citations-header">Investigation trace</span>
                    <span className="panel-meta">
                      {message.investigation.iterations} tool calls ·{' '}
                      {message.investigation.filesRead.length} files ·{' '}
                      {message.investigation.searchesPerformed.length} searches
                    </span>
                  </div>
                  <ol className="panel-trace">
                    {message.investigation.trace.map((step, idx) => (
                      <li key={idx} className="panel-trace-step">
                        {step}
                      </li>
                    ))}
                  </ol>
                </div>
              )}
            </div>
          </div>
        )
      })}
      {showJump && (
        <div className="chat-jump-wrap">
          <button type="button" className="chat-jump" onClick={jumpToLatest}>
            Jump to latest
            <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
              <line x1="12" y1="5" x2="12" y2="19" />
              <polyline points="19 12 12 19 5 12" />
            </svg>
          </button>
        </div>
      )}
      {error && (
        <div className="chat-error-bar" role="alert">
          <span className="chat-error-text">{error}</span>
          <button type="button" className="chat-retry-btn" onClick={onRetry}>
            Retry
          </button>
        </div>
      )}
      <div ref={endRef} />
    </div>
  )
}

export default MessageList

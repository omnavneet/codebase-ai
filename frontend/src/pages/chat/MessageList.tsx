import React, { useEffect, useRef } from "react"
import type { Citation, Message } from "./types"

interface MessageListProps {
  messages: Message[]
  loading: boolean
  onCitationClick: (citation: Citation) => void
}

const formatMessageTime = (iso: string) => {
  const date = new Date(iso)
  if (Number.isNaN(date.getTime())) return ""
  return date.toLocaleTimeString([], { hour: "2-digit", minute: "2-digit" })
}

/**
 * The conversation stream. The optimistic assistant placeholder doubles as
 * the typing indicator: while its content is empty it renders the dots
 * INSIDE its own bubble — never as a separate row — so only one ✦ block can
 * ever exist per answer.
 */
const MessageList: React.FC<MessageListProps> = ({
  messages,
  loading,
  onCitationClick,
}) => {
  const messagesEndRef = useRef<HTMLDivElement>(null)

  useEffect(() => {
    messagesEndRef.current?.scrollIntoView({ behavior: "smooth" })
  }, [messages])

  return (
    <div className="messages-stream">
      {messages.map((message, index) => {
        const isTyping =
          loading &&
          message.role === "assistant" &&
          message.content === "" &&
          index === messages.length - 1
        return (
          <div key={message.id} className={`message-row ${message.role}`}>
            <div className="message-avatar">
              {message.role === "assistant" ? "✦" : "👤"}
            </div>
            <div className="message-bubble">
              {isTyping ? (
                <div className="typing-bubble">
                  <span className="typing-dot"></span>
                  <span className="typing-dot"></span>
                  <span className="typing-dot"></span>
                </div>
              ) : (
                <div className="message-content">{message.content}</div>
              )}
              {!isTyping && message.createdAt && (
                <span className="message-time">
                  {formatMessageTime(message.createdAt)}
                </span>
              )}
              {message.citations && message.citations.length > 0 && (
                <div className="citations-container">
                  <span className="citations-header">Sources &amp; Citations:</span>
                  <div className="citations-list">
                    {message.citations.map((citation, idx) => (
                      <button
                        key={`${message.id}-citation-${idx}`}
                        className="citation-chip"
                        onClick={() => onCitationClick(citation)}
                        title="Click to view file snippet"
                      >
                        <span className="citation-icon">📄</span>
                        <span className="citation-path">
                          {citation.file_path || "source file"}
                        </span>
                        <span className="citation-lines">
                          L{citation.start_line}-{citation.end_line}
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
      <div ref={messagesEndRef} />
    </div>
  )
}

export default MessageList

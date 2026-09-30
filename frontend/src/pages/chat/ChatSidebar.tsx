import React from "react"
import type { Session } from "./types"

interface ChatSidebarProps {
  sessions: Session[]
  activeSession: string | null
  onSelectSession: (sessionId: string) => void
  onCreateSession: () => void
  onDeleteSession: (sessionId: string) => void
  renamingSessionId: string | null
  renameTitle: string
  onRenameTitleChange: (title: string) => void
  onStartRenaming: (session: Session) => void
  onSaveRenaming: (sessionId: string) => void
  onCancelRenaming: () => void
  // Sidebar geometry — owned by ChatPage so the header toggle and the
  // drag-to-resize handle can drive the same width/collapsed state.
  collapsed: boolean
  width: number
  resizeHandleRef: React.RefObject<HTMLDivElement | null>
}

const formatDate = (dateString: string) => {
  const date = new Date(dateString)
  return date.toLocaleDateString("en-US", {
    month: "short",
    day: "numeric",
  })
}

const chatIcon = (
  <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
    <path d="M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z"></path>
  </svg>
)

const trashIcon = (
  <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
    <polyline points="3 6 5 6 21 6"></polyline>
    <path d="M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6m3 0V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2"></path>
  </svg>
)

/**
 * The sidebar's only job is conversation history.
 *
 * Files, search, agent and the review tools used to be mounted in here, which
 * meant the navigation column changed shape every time the user switched
 * modes — the list of chats would disappear, a form would appear, and the
 * "back" gesture was a guess. Those panels now live in the main workspace, so
 * this stays exactly what the header says it is: a list of chats.
 */
const ChatSidebar: React.FC<ChatSidebarProps> = ({
  sessions,
  activeSession,
  onSelectSession,
  onCreateSession,
  onDeleteSession,
  renamingSessionId,
  renameTitle,
  onRenameTitleChange,
  onStartRenaming,
  onSaveRenaming,
  onCancelRenaming,
  collapsed,
  width,
  resizeHandleRef,
}) => {
  return (
    <aside
      className={`chat-sidebar ${collapsed ? "collapsed" : ""}`}
      style={{ width: collapsed ? 0 : width }}
      aria-label="Chat history"
    >
      {/* Drag-to-resize handle on the right edge */}
      <div className="sidebar-resize-handle" ref={resizeHandleRef} />

      <div className="sidebar-header">
        <div className="sidebar-header-left">
          <span className="sidebar-title">Chats</span>
          {sessions.length > 0 && (
            <span className="session-count-badge">{sessions.length}</span>
          )}
        </div>
        <button
          type="button"
          className="btn-new-chat"
          onClick={onCreateSession}
          title="Start a new chat"
        >
          <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
            <line x1="12" y1="5" x2="12" y2="19"></line>
            <line x1="5" y1="12" x2="19" y2="12"></line>
          </svg>
          <span>New</span>
        </button>
      </div>

      <div className="sidebar-content">
        {sessions.length === 0 ? (
          <div className="sidebar-empty-hint">
            <span className="sidebar-empty-icon" aria-hidden="true">
              {chatIcon}
            </span>
            <p className="sidebar-empty-title">No conversations yet</p>
            <p className="sidebar-empty-text">
              Ask a question to start one — it is saved here.
            </p>
            <button
              type="button"
              className="btn-start-chat-hint"
              onClick={onCreateSession}
            >
              Start a chat
            </button>
          </div>
        ) : (
          <div className="session-list">
            {sessions.map((session) => (
              <div
                key={session.id}
                className={`session-item ${session.id === activeSession ? "active" : ""}`}
              >
                {/* The row's clickable region is a real button so the list is
                    reachable by keyboard. The delete action sits beside it
                    rather than inside it — nested buttons are invalid. */}
                {renamingSessionId === session.id ? (
                  <div className="session-open">
                    <span className="session-icon">{chatIcon}</span>
                    <span className="session-info">
                      <input
                        className="session-title-input"
                        value={renameTitle}
                        autoFocus
                        aria-label="Chat title"
                        onChange={(event) => onRenameTitleChange(event.target.value)}
                        onKeyDown={(event) => {
                          if (event.key === "Enter") onSaveRenaming(session.id)
                          if (event.key === "Escape") onCancelRenaming()
                        }}
                        onBlur={() => onCancelRenaming()}
                      />
                      <span className="session-date">
                        {formatDate(session.updatedAt)}
                      </span>
                    </span>
                  </div>
                ) : (
                  <button
                    type="button"
                    className="session-open"
                    onClick={() => onSelectSession(session.id)}
                    onDoubleClick={() => onStartRenaming(session)}
                    title={`${session.title} — double-click to rename`}
                    aria-current={session.id === activeSession ? "true" : undefined}
                  >
                    <span className="session-icon">{chatIcon}</span>
                    <span className="session-info">
                      <span className="session-title">{session.title}</span>
                      <span className="session-date">
                        {formatDate(session.updatedAt)}
                      </span>
                    </span>
                  </button>
                )}
                <div className="session-actions">
                  <button
                    type="button"
                    className="session-action-btn delete"
                    aria-label={`Delete ${session.title}`}
                    title="Delete chat"
                    onClick={(event) => {
                      event.stopPropagation()
                      onDeleteSession(session.id)
                    }}
                  >
                    {trashIcon}
                  </button>
                </div>
              </div>
            ))}
          </div>
        )}
      </div>
    </aside>
  )
}

export default ChatSidebar

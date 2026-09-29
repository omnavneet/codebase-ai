import React from "react"
import FileTree, { type FileNode } from "../../components/FileTree"
import SearchPanel from "../../components/SearchPanel"
import AgentPanel from "../../components/AgentPanel"
import DocsPanel from "../../components/DocsPanel"
import ExplainPanel from "../../components/ExplainPanel"
import DebugPanel from "../../components/DebugPanel"
import ImprovePanel from "../../components/ImprovePanel"
import type { Session, TabId } from "./types"

interface ChatSidebarProps {
  projectId: string
  activeTab: TabId
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
  // Files tab
  fileTree: FileNode[]
  expandedDirs: Set<string>
  onToggleDir: (path: string) => void
  // Minimal shape both FileTree items and search results satisfy; consumers
  // fetch the full content by fileId.
  onFileClick: (file: { fileId?: string }) => void
  onCitationClick: (filePath: string) => void
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

const getSidebarTitle = (tab: TabId) => {
  switch (tab) {
    case "docs":
      return "Documentation Generator"
    case "explain":
      return "Code Explainer"
    case "debug":
      return "Debug Assistant"
    case "improve":
      return "Code Reviewer"
    default:
      return "Tools"
  }
}

const ChatSidebar: React.FC<ChatSidebarProps> = ({
  projectId,
  activeTab,
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
  fileTree,
  expandedDirs,
  onToggleDir,
  onFileClick,
  onCitationClick,
  collapsed,
  width,
  resizeHandleRef,
}) => {
  return (
    <aside
      className={`chat-sidebar ${collapsed ? "collapsed" : ""}`}
      style={{ width: collapsed ? 0 : width }}
    >
      {/* Drag-to-resize handle on the right edge */}
      <div className="sidebar-resize-handle" ref={resizeHandleRef} />
      <div className="sidebar-header">
        <div className="sidebar-header-left">
          <span className="sidebar-title">
            {activeTab === "chat"
              ? "Chats"
              : activeTab === "files"
              ? "Files"
              : activeTab === "search"
              ? "Search"
              : activeTab === "agent"
              ? "Agent"
              : getSidebarTitle(activeTab)}
          </span>
          {activeTab === "chat" && sessions.length > 0 && (
            <span className="session-count-badge">{sessions.length}</span>
          )}
        </div>
        {activeTab === "chat" && (
          <button
            type="button"
            className="btn-new-chat"
            onClick={onCreateSession}
            title="Start a new chat"
          >
            <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round" strokeLinejoin="round">
              <line x1="12" y1="5" x2="12" y2="19"></line>
              <line x1="5" y1="12" x2="19" y2="12"></line>
            </svg>
            <span>New</span>
          </button>
        )}
      </div>

      <div className="sidebar-content">
        {activeTab === "chat" ? (
<>
            {sessions.length === 0 ? (
              <div className="sidebar-empty-hint">
                <span className="sidebar-empty-icon" aria-hidden="true">
                  <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round">
                    <path d="M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z"></path>
                  </svg>
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
              sessions.map((session) => (
                <div
                  key={session.id}
                  className={`session-item ${session.id === activeSession ? "active" : ""}`}
                >
                  {/* The row's clickable region is a real button so the list is
                      reachable by keyboard. The delete action sits beside it
                      rather than inside it — nested buttons are invalid. */}
                  {renamingSessionId === session.id ? (
                    <div className="session-open">
                      <span className="session-icon">
                        <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
                          <path d="M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z"></path>
                        </svg>
                      </span>
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
                        <span className="session-date">{formatDate(session.updatedAt)}</span>
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
                      <span className="session-icon">
                        <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
                          <path d="M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z"></path>
                        </svg>
                      </span>
                      <span className="session-info">
                        <span className="session-title">{session.title}</span>
                        <span className="session-date">{formatDate(session.updatedAt)}</span>
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
                      <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
                        <polyline points="3 6 5 6 21 6"></polyline>
                        <path d="M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6m3 0V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2"></path>
                      </svg>
                    </button>
                  </div>
                </div>
              ))
            )}
          </>
        ) : activeTab === "files" ? (
<div className="file-tree-container">
              <FileTree
                tree={fileTree}
                expandedDirs={expandedDirs}
                onToggleDir={onToggleDir}
                onFileClick={onFileClick}
              />
            </div>
          ) : activeTab === "search" ? (
            <SearchPanel projectId={projectId} onFileClick={onFileClick} />
          ) : activeTab === "agent" ? (
            <AgentPanel projectId={projectId} onCitationClick={onCitationClick} />
          ) : activeTab === "docs" ? (
            <DocsPanel projectId={projectId} />
          ) : activeTab === "explain" ? (
            <ExplainPanel projectId={projectId} onCitationClick={onCitationClick} />
          ) : activeTab === "debug" ? (
            <DebugPanel projectId={projectId} onCitationClick={onCitationClick} />
          ) : (
            <ImprovePanel projectId={projectId} />
          )}
      </div>
    </aside>
  )
}

export default ChatSidebar
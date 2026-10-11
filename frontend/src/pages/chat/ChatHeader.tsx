import React from "react"
import type { TabId } from "./types"

interface ChatHeaderProps {
  projectName: string
  projectStatus: string
  activeTab: TabId
  onSelectTab: (tab: TabId) => void
  sidebarCollapsed: boolean
  onToggleSidebar: () => void
  /** False in modes that have no sidebar to show or hide. */
  showSidebarToggle: boolean
  onBack: () => void
}

/** Human labels for the indexing states the API reports. */
const PROJECT_STATUS_LABELS: Record<string, string> = {
  pending: "Queued",
  processing: "Indexing",
  error: "Indexing failed",
}

const ChatHeader: React.FC<ChatHeaderProps> = ({
  projectName,
  projectStatus,
  activeTab,
  onSelectTab,
  sidebarCollapsed,
  onToggleSidebar,
  showSidebarToggle,
  onBack,
}) => {

  return (
    <header className="chat-header">
      <div className="header-left">
        <button
          type="button"
          className="btn-header-back"
          onClick={onBack}
          title="Return to projects dashboard"
          aria-label="Back to projects"
        >
          <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
            <line x1="19" y1="12" x2="5" y2="12"></line>
            <polyline points="12 19 5 12 12 5"></polyline>
          </svg>
          <span>Projects</span>
        </button>

        <span className="header-divider">/</span>

        <div className="project-breadcrumb">
          <span className="project-icon">
            <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
              <path d="M21 16V8a2 2 0 0 0-1-1.73l-7-4a2 2 0 0 0-2 0l-7 4A2 2 0 0 0 3 8v8a2 2 0 0 0 1 1.73l7 4a2 2 0 0 0 2 0l7-4A2 2 0 0 0 21 16z"></path>
              <polyline points="3.27 6.96 12 12.01 20.73 6.96"></polyline>
              <line x1="12" y1="22.08" x2="12" y2="12"></line>
            </svg>
          </span>
          <span className="project-title-text" title={projectName}>
            {projectName || "Loading project…"}
          </span>
          {/* Only non-ready states are worth surfacing: a permanent "ready"
              badge is pure noise once indexing has finished. Previously the
              class produced `status-processing` while the CSS expected
              `.processing`, so the pill was always rendered neutral. */}
          {projectStatus && projectStatus !== "ready" && (
            <span
              className={`project-status-pill ${projectStatus}`}
              title={
                projectStatus === "error"
                  ? "Indexing failed for this project"
                  : "Indexing in progress"
              }
            >
              <span className="ui-dot ui-dot-pulse" aria-hidden="true" />
              {PROJECT_STATUS_LABELS[projectStatus] ?? projectStatus}
            </span>
          )}
        </div>
      </div>

      {/* Unified Navigation Segments */}
      <div className="header-nav">
        <button
          type="button"
          className={`nav-pill ${activeTab === "chat" ? "active" : ""}`}
          onClick={() => onSelectTab("chat")}
          aria-current={activeTab === "chat" ? "page" : undefined}
        >
          <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
            <path d="M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z"></path>
          </svg>
          <span>Chat</span>
        </button>

        <button
          type="button"
          className={`nav-pill ${activeTab === "files" ? "active" : ""}`}
          onClick={() => onSelectTab("files")}
          aria-current={activeTab === "files" ? "page" : undefined}
        >
          <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
            <path d="M22 19a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h5l2 3h9a2 2 0 0 1 2 2z"></path>
          </svg>
          <span>Files</span>
        </button>

        <button
          type="button"
          className={`nav-pill ${activeTab === "search" ? "active" : ""}`}
          onClick={() => onSelectTab("search")}
          aria-current={activeTab === "search" ? "page" : undefined}
        >
          <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
            <circle cx="11" cy="11" r="8"></circle>
            <line x1="21" y1="21" x2="16.65" y2="16.65"></line>
          </svg>
          <span>Search</span>
        </button>

        <button
          type="button"
          className={`nav-pill ${activeTab === "analyze" ? "active" : ""}`}
          onClick={() => onSelectTab("analyze")}
          aria-current={activeTab === "analyze" ? "page" : undefined}
        >
          <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
            <circle cx="12" cy="12" r="10"></circle>
            <path d="M9.09 9a3 3 0 0 1 5.83 1c0 2-3 3-3 3"></path>
            <line x1="12" y1="17" x2="12.01" y2="17"></line>
          </svg>
          <span>Analyze</span>
        </button>
      </div>

      <div className="header-right">
        {/* Only modes that actually own a sidebar get a control for it. */}
        {showSidebarToggle && (
          <button
            type="button"
            className="btn-icon-header"
            onClick={onToggleSidebar}
            aria-pressed={!sidebarCollapsed}
            title={sidebarCollapsed ? "Show chat list" : "Hide chat list"}
            aria-label={sidebarCollapsed ? "Show chat list" : "Hide chat list"}
          >
            <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
              <rect x="3" y="3" width="18" height="18" rx="2" ry="2"></rect>
              <line x1="9" y1="3" x2="9" y2="21"></line>
            </svg>
          </button>
        )}
      </div>
    </header>
  )
}

export default ChatHeader
import React, { useEffect, useRef, useState } from "react"
import type { TabId } from "./types"

interface ChatHeaderProps {
  projectName: string
  projectStatus: string
  activeTab: TabId
  onSelectTab: (tab: TabId) => void
  sidebarCollapsed: boolean
  onToggleSidebar: () => void
  onBack: () => void
}

const TOOLS: TabId[] = ["docs", "explain", "debug", "improve"]

const TOOL_META: Record<
  string,
  { name: string; desc: string; icon: React.ReactNode }
> = {
  docs: {
    name: "Documentation",
    desc: "Generate docs & README",
    icon: (
      <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
        <path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"></path>
        <polyline points="14 2 14 8 20 8"></polyline>
        <line x1="16" y1="13" x2="8" y2="13"></line>
        <line x1="16" y1="17" x2="8" y2="17"></line>
        <polyline points="10 9 9 9 8 9"></polyline>
      </svg>
    ),
  },
  explain: {
    name: "Explain Code",
    desc: "Understand complex functions",
    icon: (
      <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
        <circle cx="12" cy="12" r="10"></circle>
        <path d="M9.09 9a3 3 0 0 1 5.83 1c0 2-3 3-3 3"></path>
        <line x1="12" y1="17" x2="12.01" y2="17"></line>
      </svg>
    ),
  },
  debug: {
    name: "Debug Assistant",
    desc: "Trace errors & stack traces",
    icon: (
      <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
        <path d="M14.7 6.3a1 1 0 0 0 0 1.4l1.6 1.6a1 1 0 0 0 1.4 0l3.77-3.77a6 6 0 0 1-7.94 7.94l-6.91 6.91a2.12 2.12 0 0 1-3-3l6.91-6.91a6 6 0 0 1 7.94-7.94l-3.76 3.76z"></path>
      </svg>
    ),
  },
  improve: {
    name: "Code Review",
    desc: "Find bugs & improvements",
    icon: (
      <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
        <polyline points="9 11 12 14 22 4"></polyline>
        <path d="M21 12v7a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h11"></path>
      </svg>
    ),
  },
}

const getToolTitle = (tab: TabId) => {
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

const ChatHeader: React.FC<ChatHeaderProps> = ({
  projectName,
  projectStatus,
  activeTab,
  onSelectTab,
  sidebarCollapsed,
  onToggleSidebar,
  onBack,
}) => {
  const [toolsMenuOpen, setToolsMenuOpen] = useState(false)
  const toolsMenuRef = useRef<HTMLDivElement>(null)

  const isToolActive = TOOLS.includes(activeTab)

  // Close the tools dropdown on click outside or Escape
  useEffect(() => {
    if (!toolsMenuOpen) return

    const handleClickOutside = (event: MouseEvent) => {
      if (
        toolsMenuRef.current &&
        !toolsMenuRef.current.contains(event.target as Node)
      ) {
        setToolsMenuOpen(false)
      }
    }
    const handleKeyDown = (event: KeyboardEvent) => {
      if (event.key === "Escape") setToolsMenuOpen(false)
    }

    document.addEventListener("mousedown", handleClickOutside)
    document.addEventListener("keydown", handleKeyDown)
    return () => {
      document.removeEventListener("mousedown", handleClickOutside)
      document.removeEventListener("keydown", handleKeyDown)
    }
  }, [toolsMenuOpen])

  return (
    <header className="chat-header">
      <div className="header-left">
        <button
          className="btn-header-back"
          onClick={onBack}
          title="Return to projects dashboard"
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
            {projectName || "Loading project..."}
          </span>
          {projectStatus && (
            <span
              className={`project-status-pill ${
                projectStatus !== "ready" ? `status-${projectStatus}` : ""
              }`}
            >
              {projectStatus}
            </span>
          )}
        </div>
      </div>

      {/* Unified Navigation Segments */}
      <div className="header-nav">
        <button
          className={`nav-pill ${activeTab === "chat" ? "active" : ""}`}
          onClick={() => onSelectTab("chat")}
        >
          <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
            <path d="M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z"></path>
          </svg>
          <span>Chat</span>
        </button>

        <button
          className={`nav-pill ${activeTab === "files" ? "active" : ""}`}
          onClick={() => onSelectTab("files")}
        >
          <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
            <path d="M22 19a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h5l2 3h9a2 2 0 0 1 2 2z"></path>
          </svg>
          <span>Files</span>
        </button>

        <button
          className={`nav-pill ${activeTab === "search" ? "active" : ""}`}
          onClick={() => onSelectTab("search")}
        >
          <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
            <circle cx="11" cy="11" r="8"></circle>
            <line x1="21" y1="21" x2="16.65" y2="16.65"></line>
          </svg>
          <span>Search</span>
        </button>

        <button
          className={`nav-pill ${activeTab === "agent" ? "active" : ""}`}
          onClick={() => onSelectTab("agent")}
        >
          <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
            <polygon points="13 2 3 14 12 14 11 22 21 10 12 10 13 2"></polygon>
          </svg>
          <span>Agent</span>
        </button>

        {/* Contextual Tools Dropdown */}
        <div className="tools-dropdown-wrapper" ref={toolsMenuRef}>
          <button
            className={`nav-pill tools-trigger ${isToolActive ? "active" : ""}`}
            onClick={() => setToolsMenuOpen(!toolsMenuOpen)}
            aria-haspopup="true"
            aria-expanded={toolsMenuOpen}
          >
            <span>{isToolActive ? getToolTitle(activeTab) : "Tools"}</span>
            <svg
              className={`chevron-icon ${toolsMenuOpen ? "open" : ""}`}
              width="12"
              height="12"
              viewBox="0 0 24 24"
              fill="none"
              stroke="currentColor"
              strokeWidth="2"
              strokeLinecap="round"
              strokeLinejoin="round"
            >
              <polyline points="6 9 12 15 18 9"></polyline>
            </svg>
          </button>

          {toolsMenuOpen && (
            <div className="tools-menu" role="menu">
              {TOOLS.map((tab) => {
                const meta = TOOL_META[tab]
                return (
                  <button
                    key={tab}
                    className={`tools-menu-item ${activeTab === tab ? "active" : ""}`}
                    role="menuitem"
                    onClick={() => {
                      onSelectTab(tab)
                      setToolsMenuOpen(false)
                    }}
                  >
                    <span className="tool-menu-icon">{meta.icon}</span>
                    <div className="tool-menu-text">
                      <span className="tool-menu-name">{meta.name}</span>
                      <span className="tool-menu-desc">{meta.desc}</span>
                    </div>
                  </button>
                )
              })}
            </div>
          )}
        </div>
      </div>

      <div className="header-right">
        <button
          className="btn-icon-header"
          onClick={onToggleSidebar}
          title={sidebarCollapsed ? "Expand sidebar" : "Collapse sidebar"}
          aria-label={sidebarCollapsed ? "Expand sidebar" : "Collapse sidebar"}
        >
          <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
            <rect x="3" y="3" width="18" height="18" rx="2" ry="2"></rect>
            <line x1="9" y1="3" x2="9" y2="21"></line>
          </svg>
        </button>
      </div>
    </header>
  )
}

export default ChatHeader
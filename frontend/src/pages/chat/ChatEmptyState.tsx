import React from "react"
import type { TabId } from "./types"

interface ChatEmptyStateProps {
  projectName: string
  onPrompt: (prompt: string) => void
  onNavigate: (tab: TabId) => void
}

// Capability-based starter actions that work for any codebase.
const quickPrompts = [
  {
    title: "Understand the architecture",
    desc: "How the main components fit together",
    prompt:
      "Explain the overall architecture of this codebase and how the main components interact.",
    icon: (
      <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
        <polygon points="12 2 2 7 12 12 22 7 12 2"></polygon>
        <polyline points="2 17 12 22 22 17"></polyline>
        <polyline points="2 12 12 17 22 12"></polyline>
      </svg>
    ),
  },
  {
    title: "Trace a request flow",
    desc: "From entry point to response",
    prompt:
      "Trace how a typical request flows through this application, from the entry point to the response.",
    icon: (
      <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
        <circle cx="6" cy="6" r="3"></circle>
        <circle cx="18" cy="18" r="3"></circle>
        <path d="M6 9v3a6 6 0 0 0 6 6h3"></path>
      </svg>
    ),
  },
  {
    title: "Find where things live",
    desc: "Locate the key logic and files",
    prompt:
      "Where is the main business logic of this project implemented? Walk me through the key files.",
    icon: (
      <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
        <polyline points="16 18 22 12 16 6"></polyline>
        <polyline points="8 6 2 12 8 18"></polyline>
      </svg>
    ),
  },
  {
    title: "Core APIs & services",
    desc: "Key endpoints and business services",
    prompt:
      "List the primary API endpoints and explain what services they interact with.",
    icon: (
      <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
        <polygon points="13 2 3 14 12 14 11 22 21 10 12 10 13 2"></polygon>
      </svg>
    ),
  },
]

const ChatEmptyState: React.FC<ChatEmptyStateProps> = ({
  projectName,
  onPrompt,
  onNavigate,
}) => {
  return (
    <div className="empty-chat-container">
      <div className="empty-chat-hero">
        <div className="empty-hero-badge">
          <span className="badge-spark">✦</span>
          <span>Codebase Intelligence</span>
        </div>
        <h2 className="empty-hero-title">
          Chat with{" "}
          <span className="project-highlight">
            {projectName || "this codebase"}
          </span>
        </h2>
        <p className="empty-hero-subtitle">
          Ask architecture questions, trace method flows, inspect security
          logic, or search code semantics.
        </p>
      </div>

      {/* Purposeful Quick Prompts */}
      <div className="empty-prompts-grid">
        {quickPrompts.map((item) => (
          <button
            key={item.title}
            className="prompt-card"
            onClick={() => onPrompt(item.prompt)}
          >
            <div className="prompt-card-top">
              <span className="prompt-card-icon">{item.icon}</span>
              <span className="prompt-card-arrow">↗</span>
            </div>
            <span className="prompt-card-title">{item.title}</span>
            <span className="prompt-card-desc">{item.desc}</span>
          </button>
        ))}
      </div>

      {/* Quick Feature Chips */}
      <div className="empty-features-strip">
        <span className="features-label">Contextual Tools:</span>
        <button className="feature-chip" onClick={() => onNavigate("files")}>
          📁 Browse Files
        </button>
        <button className="feature-chip" onClick={() => onNavigate("search")}>
          🔍 Code Search
        </button>
        <button className="feature-chip" onClick={() => onNavigate("agent")}>
          ⚡ Deep Agent
        </button>
        <button className="feature-chip" onClick={() => onNavigate("docs")}>
          📝 Generate README
        </button>
      </div>
    </div>
  )
}

export default ChatEmptyState

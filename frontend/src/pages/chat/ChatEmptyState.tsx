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

      {/* Quick Feature Chips — SVG icons rather than emoji so they inherit
          the text colour and stay legible at small sizes. */}
      <div className="empty-features-strip">
        <span className="features-label">Go to</span>
        <button type="button" className="feature-chip" onClick={() => onNavigate("files")}>
          <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
            <path d="M22 19a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h5l2 3h9a2 2 0 0 1 2 2z"></path>
          </svg>
          Files
        </button>
        <button type="button" className="feature-chip" onClick={() => onNavigate("search")}>
          <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
            <circle cx="11" cy="11" r="8"></circle>
            <line x1="21" y1="21" x2="16.65" y2="16.65"></line>
          </svg>
          Semantic search
        </button>
        <button type="button" className="feature-chip" onClick={() => onNavigate("agent")}>
          <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
            <polygon points="13 2 3 14 12 14 11 22 21 10 12 10 13 2"></polygon>
          </svg>
          Agent
        </button>
        <button type="button" className="feature-chip" onClick={() => onNavigate("docs")}>
          <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
            <path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"></path>
            <polyline points="14 2 14 8 20 8"></polyline>
            <line x1="16" y1="13" x2="8" y2="13"></line>
            <line x1="16" y1="17" x2="8" y2="17"></line>
          </svg>
          Generate README
        </button>
      </div>
    </div>
  )
}

export default ChatEmptyState

import React, { useState, useEffect, useRef } from "react"
import { useParams, useNavigate } from "react-router-dom"
import apiClient from "../services/apiClient"
import { streamChatMessage } from "../services/streamChat"
import CitationModal from "../components/CitationModal"
import FileTree from "../components/FileTree"
import FilePreview from "../components/FilePreview"
import SearchPanel from "../components/SearchPanel"
import AgentPanel from "../components/AgentPanel"
import DocsPanel from "../components/DocsPanel"
import ExplainPanel from "../components/ExplainPanel"
import DebugPanel from "../components/DebugPanel"
import ImprovePanel from "../components/ImprovePanel"
import "./Chat.css"

interface Session {
  id: string
  title: string
  updatedAt: string
}

interface Citation {
  file_path?: string
  start_line: number
  end_line: number
  content?: string
}

interface Message {
  id: string
  role: "user" | "assistant"
  content: string
  citations?: Citation[]
  createdAt: string
}

type TabId =
  | "chat"
  | "files"
  | "search"
  | "agent"
  | "docs"
  | "explain"
  | "debug"
  | "improve"

const ChatPage: React.FC = () => {
  const { projectId } = useParams<{ projectId: string }>()
  const navigate = useNavigate()

  const [sessions, setSessions] = useState<Session[]>([])
  const [activeSession, setActiveSession] = useState<string | null>(null)
  const [messages, setMessages] = useState<Message[]>([])
  const [input, setInput] = useState("")
  const [loading, setLoading] = useState(false)
  const [projectName, setProjectName] = useState("")
  const [projectStatus, setProjectStatus] = useState("")
  const [selectedCitation, setSelectedCitation] = useState<Citation | null>(
    null,
  )
  const [renamingSessionId, setRenamingSessionId] = useState<string | null>(
    null,
  )
  const [renameTitle, setRenameTitle] = useState("")
  const [activeTab, setActiveTab] = useState<TabId>("chat")
  const [fileTree, setFileTree] = useState<any[]>([])
  const [selectedFile, setSelectedFile] = useState<{
    path: string
    content: string
  } | null>(null)
  const [expandedDirs, setExpandedDirs] = useState<Set<string>>(new Set())
  const [sidebarCollapsed, setSidebarCollapsed] = useState(
    // Start collapsed on small screens where the sidebar overlays content
    typeof window !== "undefined" &&
      window.matchMedia("(max-width: 900px)").matches,
  )
  const [toolsMenuOpen, setToolsMenuOpen] = useState(false)
  const [sidebarWidth, setSidebarWidth] = useState(260)
  const sidebarRef = useRef<HTMLElement>(null)
  const isResizingRef = useRef(false)
  const resizeHandleRef = useRef<HTMLDivElement>(null)
  const toolsMenuRef = useRef<HTMLDivElement>(null)
  const messagesEndRef = useRef<HTMLDivElement>(null)
  const textareaRef = useRef<HTMLTextAreaElement>(null)
  // Tracks the in-flight chat stream so it can be cancelled when leaving the
  // page or switching projects.
  const streamAbortRef = useRef<AbortController | null>(null)

  useEffect(() => {
    if (!projectId) return
    fetchProjectInfo()
    fetchSessions()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [projectId])

  useEffect(() => {
    if (activeTab === "files" && projectId) {
      fetchFileTree()
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [activeTab, projectId])

  useEffect(() => {
    scrollToBottom()
  }, [messages])

  // Close tools dropdown on click outside or Escape
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

  // Abort any in-flight chat stream when leaving the page or switching to a
  // different project, so streamed tokens never reach an unmounted component.
  useEffect(() => {
    return () => {
      streamAbortRef.current?.abort()
    }
  }, [projectId])

  // Sidebar drag-to-resize
  useEffect(() => {
    const handle = resizeHandleRef.current
    if (!handle) return

    const MIN = 180
    const MAX = 520

    const onMouseDown = (e: MouseEvent) => {
      e.preventDefault()
      isResizingRef.current = true
      handle.classList.add("dragging")
      document.body.style.cursor = "col-resize"
      document.body.style.userSelect = "none"
    }

    const onMouseMove = (e: MouseEvent) => {
      if (!isResizingRef.current || !sidebarRef.current) return
      const containerLeft = sidebarRef.current.parentElement?.getBoundingClientRect().left ?? 0
      const newWidth = Math.min(MAX, Math.max(MIN, e.clientX - containerLeft))
      setSidebarWidth(newWidth)
    }

    const onMouseUp = () => {
      if (!isResizingRef.current) return
      isResizingRef.current = false
      handle.classList.remove("dragging")
      document.body.style.cursor = ""
      document.body.style.userSelect = ""
    }

    handle.addEventListener("mousedown", onMouseDown)
    document.addEventListener("mousemove", onMouseMove)
    document.addEventListener("mouseup", onMouseUp)

    return () => {
      handle.removeEventListener("mousedown", onMouseDown)
      document.removeEventListener("mousemove", onMouseMove)
      document.removeEventListener("mouseup", onMouseUp)
    }
  }, [])

  // Auto-resize textarea
  useEffect(() => {
    if (textareaRef.current) {
      textareaRef.current.style.height = "auto"
      textareaRef.current.style.height = `${Math.min(
        textareaRef.current.scrollHeight,
        180,
      )}px`
    }
  }, [input])

  const scrollToBottom = () => {
    messagesEndRef.current?.scrollIntoView({ behavior: "smooth" })
  }

  const fetchProjectInfo = async () => {
    try {
      const response = await apiClient.get(`/projects/${projectId}`)
      setProjectName(response.data.name)
      setProjectStatus(response.data.status)
    } catch (error) {
      console.error("Failed to fetch project:", error)
    }
  }

  const fetchFileTree = async () => {
    try {
      const response = await apiClient.get(`/projects/${projectId}/files`)
      setFileTree(response.data)
    } catch (error) {
      console.error("Failed to fetch file tree:", error)
    }
  }

  const handleFileClick = async (file: any) => {
    try {
      const response = await apiClient.get(
        `/projects/${projectId}/files/${file.fileId}/content`,
      )
      setSelectedFile({
        path: response.data.path,
        content: response.data.content,
      })
    } catch (error) {
      console.error("Failed to fetch file content:", error)
    }
  }

  const toggleDir = (path: string) => {
    setExpandedDirs((currentExpanded) => {
      const nextExpanded = new Set(currentExpanded)
      if (nextExpanded.has(path)) {
        nextExpanded.delete(path)
      } else {
        nextExpanded.add(path)
      }
      return nextExpanded
    })
  }

  const handleCitationClick = (filePath: string) => {
    apiClient
      .get(`/projects/${projectId}/files/by-path`, { params: { path: filePath } })
      .then((response) => {
        setSelectedFile({ path: filePath, content: response.data.content })
      })
      .catch((error) => console.error("Failed to fetch file:", error))
  }

  const fetchSessions = async () => {
    try {
      const response = await apiClient.get(`/projects/${projectId}/sessions`)
      setSessions(response.data)
    } catch (error) {
      console.error("Failed to fetch sessions:", error)
    }
  }

  const createNewSession = async () => {
    try {
      const response = await apiClient.post(`/projects/${projectId}/sessions`)
      const newSession = response.data
      setSessions((currentSessions) => [newSession, ...currentSessions])
      setActiveSession(newSession.id)
      setMessages([])
      setActiveTab("chat")
      if (sidebarCollapsed) setSidebarCollapsed(false)
    } catch (error) {
      console.error("Failed to create session:", error)
    }
  }

  const selectSession = async (sessionId: string) => {
    setActiveSession(sessionId)
    try {
      const response = await apiClient.get(`/sessions/${sessionId}/messages`)
      setMessages(
        response.data.map(
          (message: Message & { citations?: string | Citation[] }) => ({
            ...message,
            citations: parseCitations(message.citations),
          }),
        ),
      )
      setActiveTab("chat")
    } catch (error) {
      console.error("Failed to fetch messages:", error)
    }
  }

  const deleteSession = async (sessionId: string) => {
    try {
      await apiClient.delete(`/sessions/${sessionId}`)
      setSessions((currentSessions) =>
        currentSessions.filter((session) => session.id !== sessionId),
      )
      if (activeSession === sessionId) {
        setActiveSession(null)
        setMessages([])
      }
    } catch (error) {
      console.error("Failed to delete session:", error)
    }
  }

  const startRenaming = (session: Session) => {
    setRenamingSessionId(session.id)
    setRenameTitle(session.title)
  }

  const saveSessionTitle = async (sessionId: string) => {
    const title = renameTitle.trim()
    if (!title) return

    try {
      const response = await apiClient.patch(`/sessions/${sessionId}`, {
        title,
      })
      setSessions((currentSessions) =>
        currentSessions.map((session) =>
          session.id === sessionId ? response.data : session,
        ),
      )
      setRenamingSessionId(null)
    } catch (error) {
      console.error("Failed to rename session:", error)
    }
  }

  const parseCitations = (
    citations: string | Citation[] | undefined,
  ): Citation[] | undefined => {
    if (!citations) return undefined
    if (Array.isArray(citations)) return citations

    try {
      const parsed: unknown = JSON.parse(citations)
      return Array.isArray(parsed) ? (parsed as Citation[]) : undefined
    } catch {
      return undefined
    }
  }

  // Returns the active session id, transparently creating a session for the
  // project when none exists yet. Returns null when creation fails.
  const ensureSession = async (): Promise<string | null> => {
    if (activeSession) return activeSession
    if (!projectId) return null
    try {
      const response = await apiClient.post(`/projects/${projectId}/sessions`)
      const newSession = response.data
      setSessions((prev) => [newSession, ...prev])
      setActiveSession(newSession.id)
      return newSession.id
    } catch (error) {
      console.error("Failed to create session:", error)
      return null
    }
  }

  const sendQuery = async (queryText: string) => {
    if (!queryText.trim() || loading) return

    const currentSessionId = await ensureSession()
    if (!currentSessionId) return

    const userMessageText = queryText.trim()
    setInput("")
    setLoading(true)

    // Optimistically add the user message plus an empty assistant message
    // that the stream fills in token by token.
    const tempUserMessage: Message = {

      id: "temp-user-" + Date.now(),
      role: "user",
      content: userMessageText,
      createdAt: new Date().toISOString(),
    }
    const assistantId = "temp-assistant-" + Date.now()
    const assistantPlaceholder: Message = {
      id: assistantId,
      role: "assistant",
      content: "",
      createdAt: new Date().toISOString(),
    }
    setMessages((prev) => [...prev, tempUserMessage, assistantPlaceholder])

    const updateAssistant = (update: (message: Message) => Message) => {
      setMessages((prev) =>
        prev.map((message) => (message.id === assistantId ? update(message) : message)),
      )
    }

    // If the stream never produced a token we can safely retry through the
    // non-streaming endpoint; if it did, the backend may already have
    // persisted a partial answer, so we never re-send.
    let receivedToken = false
    // Inactivity watchdog: if the SSE promise never settles (hung server,
    // lost connection without a close), abort and recover instead of leaving
    // the composer permanently locked and the bubble empty forever.
    let watchdogFired = false
    let watchdogId: ReturnType<typeof setTimeout> | null = null
    const armWatchdog = () => {
      if (watchdogId) clearTimeout(watchdogId)
      watchdogId = setTimeout(() => {
        watchdogFired = true
        abortController.abort()
      }, 45000)
    }

    const abortController = new AbortController()
    streamAbortRef.current = abortController
    armWatchdog()

    try {
      await streamChatMessage(
        currentSessionId,
        userMessageText,
        {
          onToken: (token) => {
            receivedToken = true
            armWatchdog()
            updateAssistant((message) => ({ ...message, content: message.content + token }))
          },
          onMeta: (citations) => {
            updateAssistant((message) => ({ ...message, citations: citations as Citation[] }))
          },
          onDone: (messageId) => {
            // Reconcile the optimistic placeholder with the persisted message.
            if (messageId) {
              updateAssistant((message) => ({ ...message, id: messageId }))
            }
          },
          onError: (errorMessage) => {
            updateAssistant((message) =>
              message.content
                ? message
                : {
                    ...message,
                    content: `Sorry, an error occurred while analyzing the codebase. (${errorMessage})`,
                  },
            )
          },
        },
        abortController.signal,
      )
      fetchSessions()
    } catch (streamError) {
      if (abortController.signal.aborted && !watchdogFired) {
        // Navigated away or project switched: nothing to update.
        return
      }
      if (!watchdogFired) {
        console.error("Streaming failed:", streamError)
      } else {
        console.error("Stream stalled with no activity; aborted by watchdog")
      }
      if (!receivedToken) {
        try {
          const response = await apiClient.post(
            // The streaming prep phase already persisted the question, so
            // declare userPersisted to keep the conversation free of duplicates.
            `/sessions/${currentSessionId}/messages?userPersisted=true`,
            { content: userMessageText },
          )
          updateAssistant((message) => ({
            ...message,
            content: response.data.answer,
            citations: response.data.citations,
          }))
          fetchSessions()
        } catch (error) {
          console.error("Failed to send message:", error)
          updateAssistant((message) => ({
            ...message,
            content: "Sorry, an error occurred while analyzing the codebase. Please try again.",
          }))
        }
      }
    } finally {
      if (watchdogId) clearTimeout(watchdogId)
      if (streamAbortRef.current === abortController) {
        streamAbortRef.current = null
      }
      // Self-heal: never leave an empty assistant bubble behind. If nothing
      // ever arrived for this send (and it was not reconciled to a persisted
      // message), drop the placeholder so the stream shows no empty ✦ block.
      setMessages((prev) => {
        const target = prev.find((message) => message.id === assistantId)
        return target && target.content === ""
          ? prev.filter((message) => message.id !== assistantId)
          : prev
      })
      setLoading(false)
    }
  }

  const handleKeyPress = (e: React.KeyboardEvent) => {
    if (e.key === "Enter" && !e.shiftKey) {
      e.preventDefault()
      sendQuery(input)
    }
  }

  const formatDate = (dateString: string) => {
    const date = new Date(dateString)
    return date.toLocaleDateString("en-US", {
      month: "short",
      day: "numeric",
    })
  }

  const isToolActive = ["docs", "explain", "debug", "improve"].includes(activeTab)

  // Capability-based starter actions that work for any codebase.
  const quickPrompts = [
    {
      title: "Understand the architecture",
      desc: "How the main components fit together",
      prompt: "Explain the overall architecture of this codebase and how the main components interact.",
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
      prompt: "Trace how a typical request flows through this application, from the entry point to the response.",
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
      prompt: "Where is the main business logic of this project implemented? Walk me through the key files.",
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
      prompt: "List the primary API endpoints and explain what services they interact with.",
      icon: (
        <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
          <polygon points="13 2 3 14 12 14 11 22 21 10 12 10 13 2"></polygon>
        </svg>
      ),
    },
  ]

  const getToolTitle = () => {
    switch (activeTab) {
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

  return (
    <div className="chat-container">
      {/* Collapsible Left Sidebar */}
      <aside className={`chat-sidebar ${sidebarCollapsed ? "collapsed" : ""}`}>
      {/* Always-visible, resizable Left Sidebar */}
      <aside
        ref={sidebarRef}
        className="chat-sidebar"
        style={{ width: sidebarWidth }}
      >
        {/* Drag-to-resize handle */}
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
                : getToolTitle()}
            </span>
            {activeTab === "chat" && sessions.length > 0 && (
              <span className="session-count-badge">{sessions.length}</span>
            )}
          </div>
          {activeTab === "chat" && (
            <button
              className="btn-new-chat"
              onClick={createNewSession}
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
            <div className="session-list">
              {sessions.length === 0 ? (
                <div className="sidebar-empty-hint">
                  <p>No conversations yet.</p>
                  <button className="btn-start-chat-hint" onClick={createNewSession}>
                    Create your first chat
                  </button>
                </div>
              ) : (
                sessions.map((session) => (
                  <div
                    key={session.id}
                    className={`session-item ${session.id === activeSession ? "active" : ""}`}
                    onClick={() => selectSession(session.id)}
                  >
                    <div className="session-icon">
                      <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
                        <path d="M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z"></path>
                      </svg>
                    </div>
                    <div className="session-info">
                      {renamingSessionId === session.id ? (
                        <input
                          className="session-title-input"
                          value={renameTitle}
                          autoFocus
                          onChange={(event) => setRenameTitle(event.target.value)}
                          onClick={(event) => event.stopPropagation()}
                          onKeyDown={(event) => {
                            if (event.key === "Enter") saveSessionTitle(session.id)
                            if (event.key === "Escape") setRenamingSessionId(null)
                          }}
                          onBlur={() => setRenamingSessionId(null)}
                        />
                      ) : (
                        <div
                          className="session-title"
                          title={session.title}
                          onDoubleClick={(event) => {
                            event.stopPropagation()
                            startRenaming(session)
                          }}
                        >
                          {session.title}
                        </div>
                      )}
                      <div className="session-date">{formatDate(session.updatedAt)}</div>
                    </div>
                    <div className="session-actions">
                      <button
                        type="button"
                        className="session-action-btn delete"
                        aria-label={`Delete ${session.title}`}
                        title="Delete chat"
                        onClick={(event) => {
                          event.stopPropagation()
                          deleteSession(session.id)
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
            </div>
          ) : activeTab === "files" ? (
            <div className="file-tree-container">
              <FileTree
                tree={fileTree}
                expandedDirs={expandedDirs}
                onToggleDir={toggleDir}
                onFileClick={handleFileClick}
              />
            </div>
          ) : activeTab === "search" ? (
            <SearchPanel projectId={projectId!} onFileClick={handleFileClick} />
          ) : activeTab === "agent" ? (
            <AgentPanel projectId={projectId!} onCitationClick={handleCitationClick} />
          ) : activeTab === "docs" ? (
            <DocsPanel projectId={projectId!} />
          ) : activeTab === "explain" ? (
            <ExplainPanel projectId={projectId!} onCitationClick={handleCitationClick} />
          ) : activeTab === "debug" ? (
            <DebugPanel projectId={projectId!} onCitationClick={handleCitationClick} />
          ) : (
            <ImprovePanel projectId={projectId!} />
          )}
        </div>
      </aside>

      {/* Main Workspace */}
      <main className="chat-main">
        {/* Modern App Header */}
        <header className="chat-header">
          <div className="header-left">
            <button
              className="btn-header-back"
              onClick={() => navigate("/dashboard")}
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
              onClick={() => setActiveTab("chat")}
            >
              <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
                <path d="M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z"></path>
              </svg>
              <span>Chat</span>
            </button>

            <button
              className={`nav-pill ${activeTab === "files" ? "active" : ""}`}
              onClick={() => {
                setActiveTab("files")
                if (sidebarCollapsed) setSidebarCollapsed(false)
              }}
            >
              <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
                <path d="M22 19a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h5l2 3h9a2 2 0 0 1 2 2z"></path>
              </svg>
              <span>Files</span>
            </button>

            <button
              className={`nav-pill ${activeTab === "search" ? "active" : ""}`}
              onClick={() => {
                setActiveTab("search")
                if (sidebarCollapsed) setSidebarCollapsed(false)
              }}
            >
              <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
                <circle cx="11" cy="11" r="8"></circle>
                <line x1="21" y1="21" x2="16.65" y2="16.65"></line>
              </svg>
              <span>Search</span>
            </button>

            <button
              className={`nav-pill ${activeTab === "agent" ? "active" : ""}`}
              onClick={() => {
                setActiveTab("agent")
                if (sidebarCollapsed) setSidebarCollapsed(false)
              }}
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
                <span>{isToolActive ? getToolTitle() : "Tools"}</span>
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
                  <button
                    className={`tools-menu-item ${activeTab === "docs" ? "active" : ""}`}
                    role="menuitem"
                    onClick={() => {
                      setActiveTab("docs")
                      setToolsMenuOpen(false)
                      if (sidebarCollapsed) setSidebarCollapsed(false)
                    }}
                  >
                    <span className="tool-menu-icon">
                      <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
                        <path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"></path>
                        <polyline points="14 2 14 8 20 8"></polyline>
                        <line x1="16" y1="13" x2="8" y2="13"></line>
                        <line x1="16" y1="17" x2="8" y2="17"></line>
                        <polyline points="10 9 9 9 8 9"></polyline>
                      </svg>
                    </span>
                    <div className="tool-menu-text">
                      <span className="tool-menu-name">Documentation</span>
                      <span className="tool-menu-desc">Generate docs & README</span>
                    </div>
                  </button>

                  <button
                    className={`tools-menu-item ${activeTab === "explain" ? "active" : ""}`}
                    role="menuitem"
                    onClick={() => {
                      setActiveTab("explain")
                      setToolsMenuOpen(false)
                      if (sidebarCollapsed) setSidebarCollapsed(false)
                    }}
                  >
                    <span className="tool-menu-icon">
                      <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
                        <circle cx="12" cy="12" r="10"></circle>
                        <path d="M9.09 9a3 3 0 0 1 5.83 1c0 2-3 3-3 3"></path>
                        <line x1="12" y1="17" x2="12.01" y2="17"></line>
                      </svg>
                    </span>
                    <div className="tool-menu-text">
                      <span className="tool-menu-name">Explain Code</span>
                      <span className="tool-menu-desc">Understand complex functions</span>
                    </div>
                  </button>

                  <button
                    className={`tools-menu-item ${activeTab === "debug" ? "active" : ""}`}
                    role="menuitem"
                    onClick={() => {
                      setActiveTab("debug")
                      setToolsMenuOpen(false)
                      if (sidebarCollapsed) setSidebarCollapsed(false)
                    }}
                  >
                    <span className="tool-menu-icon">
                      <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
                        <path d="M14.7 6.3a1 1 0 0 0 0 1.4l1.6 1.6a1 1 0 0 0 1.4 0l3.77-3.77a6 6 0 0 1-7.94 7.94l-6.91 6.91a2.12 2.12 0 0 1-3-3l6.91-6.91a6 6 0 0 1 7.94-7.94l-3.76 3.76z"></path>
                      </svg>
                    </span>
                    <div className="tool-menu-text">
                      <span className="tool-menu-name">Debug Assistant</span>
                      <span className="tool-menu-desc">Trace errors &amp; stack traces</span>
                    </div>
                  </button>

                  <button
                    className={`tools-menu-item ${activeTab === "improve" ? "active" : ""}`}
                    role="menuitem"
                    onClick={() => {
                      setActiveTab("improve")
                      setToolsMenuOpen(false)
                      if (sidebarCollapsed) setSidebarCollapsed(false)
                    }}
                  >
                    <span className="tool-menu-icon">
                      <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
                        <polyline points="9 11 12 14 22 4"></polyline>
                        <path d="M21 12v7a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h11"></path>
                      </svg>
                    </span>
                    <div className="tool-menu-text">
                      <span className="tool-menu-name">Code Review</span>
                      <span className="tool-menu-desc">Find bugs &amp; improvements</span>
                    </div>
                  </button>
                </div>
              )}
            </div>
          </div>

          <div className="header-right">
            <button
              className="btn-icon-header"
              onClick={() => setSidebarCollapsed(!sidebarCollapsed)}
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

        {/* Conversation Stream or Purposeful Empty State */}
        <div className="chat-body">
          {messages.length === 0 ? (
            <div className="empty-chat-container">
              <div className="empty-chat-hero">
                <div className="empty-hero-badge">
                  <span className="badge-spark">✦</span>
                  <span>Codebase Intelligence</span>
                </div>
                <h2 className="empty-hero-title">
                  Chat with <span className="project-highlight">{projectName || "this codebase"}</span>
                </h2>
                <p className="empty-hero-subtitle">
                  Ask architecture questions, trace method flows, inspect security logic, or search code semantics.
                </p>
              </div>

              {/* Actionable Prompt Cards */}
              <div className="empty-prompts-grid">
                {quickPrompts.map((item, idx) => (
                  <button
                    key={idx}
                    className="prompt-card"
                    onClick={() => sendQuery(item.prompt)}
                  >
                    <div className="prompt-card-top">
                      <span className="prompt-card-icon">{item.icon}</span>
                      <span className="prompt-card-arrow">↗</span>
                    </div>
                    <div className="prompt-card-title">{item.title}</div>
                    <div className="prompt-card-desc">{item.desc}</div>
                  </button>
                ))}
              </div>

              {/* Quick Feature Chips */}
              <div className="empty-features-strip">
                <span className="features-label">Contextual Tools:</span>
                <button className="feature-chip" onClick={() => setActiveTab("files")}>
                  📁 Browse Files
                </button>
                <button className="feature-chip" onClick={() => setActiveTab("search")}>
                  🔍 Code Search
                </button>
                <button className="feature-chip" onClick={() => setActiveTab("agent")}>
                  ⚡ Deep Agent
                </button>
                <button className="feature-chip" onClick={() => setActiveTab("docs")}>
                  📝 Generate README
                </button>
              </div>
            </div>
          ) : (
            <div className="messages-stream">
              {messages.map((message) => (
                <div key={message.id} className={`message-row ${message.role}`}>
                  <div className="message-avatar">
                    {message.role === "assistant" ? "✦" : "👤"}
                  </div>
                  <div className="message-bubble">
                    <div className="message-content">{message.content}</div>
                    {message.citations && message.citations.length > 0 && (
                      <div className="citations-container">
                        <span className="citations-header">Sources &amp; Citations:</span>
                        <div className="citations-list">
                          {message.citations.map((citation, idx) => (
                            <button
                              key={`${message.id}-citation-${idx}`}
                              className="citation-chip"
                              onClick={() => setSelectedCitation(citation)}
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
              ))}
              {loading && messages[messages.length - 1]?.content === "" && (
                <div className="message-row assistant loading-row">
                  <div className="message-avatar">✦</div>
                  <div className="message-bubble typing-bubble">
                    <span className="typing-dot"></span>
                    <span className="typing-dot"></span>
                    <span className="typing-dot"></span>
                  </div>
                </div>
              )}
              <div ref={messagesEndRef} />
            </div>
          )}
        </div>

        {/* Modern AI Chat Composer */}
        <div className="chat-composer-wrapper">
          <div className="chat-composer-card">
            <textarea
              ref={textareaRef}
              className="chat-composer-input"
              value={input}
              onChange={(e) => setInput(e.target.value)}
              onKeyDown={handleKeyPress}
              placeholder={`Ask anything about ${projectName || "this codebase"}...`}
              rows={1}
            />

            <div className="composer-bottom-bar">
              <div className="composer-hints">
              </div>

              <div className="composer-actions">
                <span className="keyboard-shortcut-hint">
                  Press <kbd>↵</kbd> to send
                </span>
                <button
                  className="btn-send-message"
                  onClick={() => sendQuery(input)}
                  disabled={!input.trim() || loading}
                  aria-label="Send message"
                  title="Send message"
                >
                  {loading ? (
                    <span className="loading-spinner-tiny"></span>
                  ) : (
                    <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round" strokeLinejoin="round">
                      <line x1="12" y1="19" x2="12" y2="5"></line>
                      <polyline points="5 12 12 5 19 12"></polyline>
                    </svg>
                  )}
                </button>
              </div>
            </div>
          </div>
        </div>
      </main>

      <CitationModal
        citation={selectedCitation}
        onClose={() => setSelectedCitation(null)}
      />

      <FilePreview
        file={selectedFile}
        onClose={() => setSelectedFile(null)}
      />
    </div>
  )
}

export default ChatPage


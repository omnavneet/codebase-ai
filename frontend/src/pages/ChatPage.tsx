import React, { useEffect, useRef, useState } from "react"
import { useNavigate, useParams } from "react-router-dom"
import apiClient from "../services/apiClient"
import CitationModal from "../components/CitationModal"
import FilePreview from "../components/FilePreview"
import { useChat } from "./chat/useChat"
import ChatSidebar from "./chat/ChatSidebar"
import ChatHeader from "./chat/ChatHeader"
import ChatEmptyState from "./chat/ChatEmptyState"
import MessageList from "./chat/MessageList"
import ChatComposer from "./chat/ChatComposer"
import type { Citation, TabId } from "./chat/types"
import "./Chat.css"

interface FileNode {
  name: string
  path: string
  type: "file" | "directory"
  fileId?: string
  children?: FileNode[]
}

/**
 * Composition root for the chat workspace. All chat data (sessions, messages,
 * streaming, optimistic updates) lives in the useChat hook; this component
 * owns page-level concerns only: project identity, the file tree and file
 * preview, citation modal state, tab selection and sidebar geometry (collapse
 * + drag-to-resize). Layout is delegated to the Chat* components in ./chat.
 */
const ChatPage: React.FC = () => {
  const { projectId } = useParams<{ projectId: string }>()
  const navigate = useNavigate()

  const chat = useChat(projectId)

  const [projectName, setProjectName] = useState("")
  const [projectStatus, setProjectStatus] = useState("")
  const [activeTab, setActiveTab] = useState<TabId>("chat")
  const [fileTree, setFileTree] = useState<FileNode[]>([])
  const [expandedDirs, setExpandedDirs] = useState<Set<string>>(new Set())
  const [selectedFile, setSelectedFile] = useState<{
    path: string
    content: string
  } | null>(null)
  const [selectedCitation, setSelectedCitation] = useState<Citation | null>(
    null,
  )
  const [sidebarCollapsed, setSidebarCollapsed] = useState(
    // Start collapsed on small screens where the sidebar overlays content
    typeof window !== "undefined" &&
      window.matchMedia("(max-width: 900px)").matches,
  )
  // Drag-to-resize sidebar
  const [sidebarWidth, setSidebarWidth] = useState(260)
  const resizeHandleRef = useRef<HTMLDivElement>(null)
  // The scrollable conversation region. Owned here so MessageList can read the
  // user's scroll position when deciding whether to follow the answer.
  const chatBodyRef = useRef<HTMLDivElement>(null)

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

  useEffect(() => {
    if (!projectId) return
    // Async fetch — setState only runs after the request resolves.
    // eslint-disable-next-line react-hooks/set-state-in-effect
    fetchProjectInfo()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [projectId])

  useEffect(() => {
    if (activeTab === "files" && projectId) {
      // Async fetch — setState only runs after the request resolves.
      // eslint-disable-next-line react-hooks/set-state-in-effect
      fetchFileTree()
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [activeTab, projectId])

  // Sidebar drag-to-resize, attached once on mount. The handle lives inside
  // the <aside> it resizes (rendered by ChatSidebar).
  useEffect(() => {
    const handle = resizeHandleRef.current
    if (!handle) return

    const MIN_WIDTH = 180
    const MAX_WIDTH = 520
    let isResizing = false

    const onMouseDown = (event: MouseEvent) => {
      event.preventDefault()
      isResizing = true
      handle.classList.add("dragging")
      document.body.style.cursor = "col-resize"
      document.body.style.userSelect = "none"
    }

    const onMouseMove = (event: MouseEvent) => {
      if (!isResizing) return
      const sidebarLeft =
        handle.parentElement?.getBoundingClientRect().left ?? 0
      const nextWidth = Math.min(
        MAX_WIDTH,
        Math.max(MIN_WIDTH, event.clientX - sidebarLeft),
      )
      setSidebarWidth(nextWidth)
    }

    const onMouseUp = () => {
      if (!isResizing) return
      isResizing = false
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

  const handleFileClick = async (file: { fileId?: string }) => {
    if (!file.fileId) return
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

  const handleCitationClick = (filePath: string) => {
    apiClient
      .get(`/projects/${projectId}/files/by-path`, {
        params: { path: filePath },
      })
      .then((response) => {
        setSelectedFile({ path: filePath, content: response.data.content })
      })
      .catch((error) => console.error("Failed to fetch file:", error))
  }

  // Selecting a non-chat nav item auto-expands the sidebar so its panel is
  // actually visible.
  const handleSelectTab = (tab: TabId) => {
    setActiveTab(tab)
    if (tab !== "chat" && sidebarCollapsed) setSidebarCollapsed(false)
  }

  const handleSelectSession = (sessionId: string) => {
    chat.selectSession(sessionId)
    setActiveTab("chat")
  }

  const handleCreateSession = () => {
    chat.createNewSession()
    setActiveTab("chat")
    if (sidebarCollapsed) setSidebarCollapsed(false)
  }

  // Guard AFTER all hooks so hook ordering stays stable across renders.
  if (!projectId) {
    return (
      <div className="chat-container">
        <main className="chat-main">
          <div className="empty-chat-container">
            <p className="empty-hero-subtitle">Invalid project link.</p>
            <button
              className="btn-header-back"
              onClick={() => navigate("/dashboard")}
            >
              ← Back to Projects
            </button>
          </div>
        </main>
      </div>
    )
  }
  return (
    <div className="chat-container">
      <ChatSidebar
        projectId={projectId}
        activeTab={activeTab}
        sessions={chat.sessions}
        activeSession={chat.activeSession}
        onSelectSession={handleSelectSession}
        onCreateSession={handleCreateSession}
        onDeleteSession={chat.deleteSession}
        renamingSessionId={chat.renamingSessionId}
        renameTitle={chat.renameTitle}
        onRenameTitleChange={chat.setRenameTitle}
        onStartRenaming={chat.startRenaming}
        onSaveRenaming={chat.saveSessionTitle}
        onCancelRenaming={() => chat.setRenamingSessionId(null)}
        fileTree={fileTree}
        expandedDirs={expandedDirs}
        onToggleDir={toggleDir}
        onFileClick={handleFileClick}
        onCitationClick={handleCitationClick}
        collapsed={sidebarCollapsed}
        width={sidebarWidth}
        resizeHandleRef={resizeHandleRef}
      />

      {/* Main Workspace */}
      <main className="chat-main">
        <ChatHeader
          projectName={projectName}
          projectStatus={projectStatus}
          activeTab={activeTab}
          onSelectTab={handleSelectTab}
          sidebarCollapsed={sidebarCollapsed}
          onToggleSidebar={() => setSidebarCollapsed(!sidebarCollapsed)}
          onBack={() => navigate("/dashboard")}
        />

        {/* Conversation Stream or Purposeful Empty State */}
        <div className="chat-body" ref={chatBodyRef}>
          {activeTab === "chat" &&
            (chat.messages.length === 0 ? (
              <ChatEmptyState
                projectName={projectName}
                onPrompt={chat.sendQuery}
                onNavigate={handleSelectTab}
              />
            ) : (
              <MessageList
                messages={chat.messages}
                loading={chat.loading}
                onCitationClick={setSelectedCitation}
                scrollContainerRef={chatBodyRef}
              />
            ))}
        </div>

        {/* Modern AI Chat Composer */}
        <ChatComposer
          value={chat.input}
          onChange={chat.setInput}
          onSend={() => chat.sendQuery(chat.input)}
          loading={chat.loading}
        />
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

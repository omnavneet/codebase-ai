import React, { useEffect, useRef, useState } from "react"
import { useNavigate, useParams } from "react-router-dom"
import apiClient from "../services/apiClient"
import CitationModal from "../components/CitationModal"
import FilePreview from "../components/FilePreview"
import type { FileNode } from "../components/FileTree"
import SearchPanel from "../components/SearchPanel"
import AgentPanel from "../components/AgentPanel"
import DocsPanel from "../components/DocsPanel"
import ExplainPanel from "../components/ExplainPanel"
import DebugPanel from "../components/DebugPanel"
import ImprovePanel from "../components/ImprovePanel"
import WorkspaceMode from "../components/WorkspaceMode"
import { getApiErrorMessage } from "../utils/apiError"
import { useChat } from "./chat/useChat"
import ChatSidebar from "./chat/ChatSidebar"
import ChatHeader from "./chat/ChatHeader"
import ChatEmptyState from "./chat/ChatEmptyState"
import MessageList from "./chat/MessageList"
import ChatComposer from "./chat/ChatComposer"
import FilesMode from "./chat/FilesMode"
import { WORKSPACE_MODES } from "./chat/workspaceModes"
import type { Citation, TabId } from "./chat/types"
import "./Chat.css"

/**
 * Modes that keep the conversation list docked. Chat and Files are both about
 * looking at something while still being one keystroke away from a chat, so the
 * sidebar earns its space. The rest are focused tasks that want the width, so
 * the sidebar folds and the workspace takes over completely.
 */
const SIDEBAR_MODES: TabId[] = ["chat", "files"]

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
  const [filesLoading, setFilesLoading] = useState(false)
  const [filesError, setFilesError] = useState("")
  // The id is kept alongside the content so the viewer can retry the exact
  // same request when the fetch fails.
  const [selectedFile, setSelectedFile] = useState<{
    fileId?: string
    path: string
    content: string
  } | null>(null)
  // True while a file's content is in flight. The viewer opens immediately with
  // the path it already knows plus a skeleton, so the click is never silent.
  const [fileLoading, setFileLoading] = useState(false)
  const [fileError, setFileError] = useState("")
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

  // Below this width the sidebar overlays the workspace rather than sitting
  // beside it, so it has to behave as a dismissable drawer.
  const [isCompact, setIsCompact] = useState(
    () =>
      typeof window !== "undefined" &&
      window.matchMedia("(max-width: 900px)").matches,
  )

  useEffect(() => {
    const query = window.matchMedia("(max-width: 900px)")
    const handleChange = () => setIsCompact(query.matches)
    query.addEventListener("change", handleChange)
    return () => query.removeEventListener("change", handleChange)
  }, [])

  const closeDrawerIfCompact = () => {
    if (isCompact) setSidebarCollapsed(true)
  }

  // Escape dismisses the overlay drawer. On desktop this is a no-op because
  // the sidebar is docked and always visible.
  useEffect(() => {
    if (!isCompact || sidebarCollapsed) return
    const handleKeyDown = (event: KeyboardEvent) => {
      if (event.key === "Escape") setSidebarCollapsed(true)
    }
    document.addEventListener("keydown", handleKeyDown)
    return () => document.removeEventListener("keydown", handleKeyDown)
  }, [isCompact, sidebarCollapsed])

  // Ctrl/Cmd+K opens search from anywhere. It is the gesture people already
  // know from Linear, Notion and VS Code, and search is the one mode worth
  // interrupting anything else to reach.
  useEffect(() => {
    const handleKeyDown = (event: KeyboardEvent) => {
      if ((event.metaKey || event.ctrlKey) && event.key.toLowerCase() === "k") {
        event.preventDefault()
        setActiveTab("search")
        // Search wants the full width, so the sidebar folds out of its way.
        setSidebarCollapsed(true)
      }
    }
    document.addEventListener("keydown", handleKeyDown)
    return () => document.removeEventListener("keydown", handleKeyDown)
  }, [])

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
    setFilesLoading(true)
    setFilesError("")
    try {
      const response = await apiClient.get(`/projects/${projectId}/files`)
      setFileTree(response.data)
    } catch (error) {
      console.error("Failed to fetch file tree:", error)
      setFileTree([])
      setFilesError(
        getApiErrorMessage(
          error,
          "Something went wrong while loading this project’s files.",
        ),
      )
    } finally {
      setFilesLoading(false)
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
    // Files are only worth fetching for the mode that shows them, and only
    // once — the toolbar's Refresh button re-fetches on demand.
    if (activeTab === "files" && projectId && fileTree.length === 0) {
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
  /**
   * Opens the viewer immediately with the path that is already known and fills
   * the content in when it arrives, so a click is never silent while the
   * request is in flight.
   */
  const openFile = async (file: { fileId?: string; path?: string }) => {
    if (!file.fileId || !projectId) return
    setSelectedFile({
      fileId: file.fileId,
      path: file.path ?? "",
      content: "",
    })
    setFileError("")
    setFileLoading(true)
    try {
      const response = await apiClient.get(
        `/projects/${projectId}/files/${file.fileId}/content`,
      )
      setSelectedFile({
        fileId: file.fileId,
        path: response.data.path ?? file.path ?? "",
        content: response.data.content,
      })
    } catch (error) {
      console.error("Failed to fetch file content:", error)
      setFileError(getApiErrorMessage(error, "This file could not be opened."))
    } finally {
      setFileLoading(false)
    }
  }

  const closeFile = () => {
    setSelectedFile(null)
    setFileError("")
  }

  /** Citations arrive as paths rather than ids, so they resolve by path. */
  const openFileByPath = async (filePath: string) => {
    if (!projectId) return
    setSelectedFile({ path: filePath, content: "" })
    setFileError("")
    setFileLoading(true)
    try {
      const response = await apiClient.get(
        `/projects/${projectId}/files/by-path`,
        { params: { path: filePath } },
      )
      setSelectedFile({ path: filePath, content: response.data.content })
    } catch (error) {
      console.error("Failed to fetch file:", error)
      setFileError("This file is no longer part of the indexed project.")
    } finally {
      setFileLoading(false)
    }
  }

  const retryFile = () => {
    if (!selectedFile) return
    if (selectedFile.fileId) {
      void openFile({
        fileId: selectedFile.fileId,
        path: selectedFile.path,
      })
    } else {
      void openFileByPath(selectedFile.path)
    }
  }

  /**
   * Switching modes. The sidebar follows the workspace instead of fighting it:
   * it stays for the modes that use it, folds for the ones that want the width,
   * and on compact layouts choosing a mode also dismisses the drawer it was
   * opened from.
   */
  const handleSelectTab = (tab: TabId) => {
    setActiveTab(tab)
    setSidebarCollapsed(isCompact || !SIDEBAR_MODES.includes(tab))
  }

  const handleSelectSession = (sessionId: string) => {
    chat.selectSession(sessionId)
    setActiveTab("chat")
    // On phones the drawer would otherwise stay open over the conversation the
    // user just chose.
    closeDrawerIfCompact()
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
  // Only chat and files have anything to show in the side column; every other
  // mode uses the whole workspace.
  const showSidebar = SIDEBAR_MODES.includes(activeTab)

  return (
    <div className="chat-container">
      {/* The toolbar spans the whole shell — never the main column alone —
          so its horizontal position is identical whether or not the current
          mode renders a sidebar. Only the grid columns below react to it. */}
      <ChatHeader
        projectName={projectName}
        projectStatus={projectStatus}
        activeTab={activeTab}
        onSelectTab={handleSelectTab}
        sidebarCollapsed={sidebarCollapsed}
        onToggleSidebar={() => setSidebarCollapsed(!sidebarCollapsed)}
        showSidebarToggle={showSidebar}
        onBack={() => navigate("/dashboard")}
      />

      {/* Drawer scrim: only exists while the sidebar overlays the workspace. */}
      {isCompact && showSidebar && !sidebarCollapsed && (
        <button
          type="button"
          className="sidebar-backdrop"
          aria-label="Close navigation"
          onClick={() => setSidebarCollapsed(true)}
        />
      )}

      {showSidebar && (
        <ChatSidebar
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
          collapsed={sidebarCollapsed}
          width={sidebarWidth}
          resizeHandleRef={resizeHandleRef}
        />
      )}

      {/* Main Workspace — one shell, many modes */}
      <main className={`chat-main ${showSidebar ? "" : "sidebar-free"}`}>
        {activeTab === "chat" ? (
          <>
            {/* Conversation Stream or Purposeful Empty State */}
            <div className="chat-body" ref={chatBodyRef}>
              {chat.messages.length === 0 ? (
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
              )}
            </div>

            {/* Modern AI Chat Composer */}
            <ChatComposer
              value={chat.input}
              onChange={chat.setInput}
              onSend={() => chat.sendQuery(chat.input)}
              loading={chat.loading}
            />
          </>
        ) : (
          <div className="workspace-body">
            {/* Keyed by mode so each switch replays the subtle enter
                transition instead of changing content in place. */}
            <WorkspaceMode
              key={activeTab}
              title={WORKSPACE_MODES[activeTab].title}
              description={WORKSPACE_MODES[activeTab].description}
              icon={WORKSPACE_MODES[activeTab].icon}
            >
              {activeTab === "files" && (
                <FilesMode
                  tree={fileTree}
                  loading={filesLoading}
                  error={filesError}
                  onRetry={fetchFileTree}
                  onFileClick={openFile}
                />
              )}
              {activeTab === "search" && (
                <SearchPanel projectId={projectId} onFileClick={openFile} />
              )}
              {activeTab === "agent" && (
                <AgentPanel
                  projectId={projectId}
                  onCitationClick={openFileByPath}
                />
              )}
              {activeTab === "docs" && <DocsPanel projectId={projectId} />}
              {activeTab === "explain" && (
                <ExplainPanel
                  projectId={projectId}
                  onCitationClick={openFileByPath}
                />
              )}
              {activeTab === "debug" && (
                <DebugPanel
                  projectId={projectId}
                  onCitationClick={openFileByPath}
                />
              )}
              {activeTab === "improve" && <ImprovePanel projectId={projectId} />}
            </WorkspaceMode>
          </div>
        )}
      </main>

      <CitationModal
        citation={selectedCitation}
        onClose={() => setSelectedCitation(null)}
        onOpenFile={(path) => {
          // Swap the snippet for the whole file instead of stacking a second
          // dialog on top of the first.
          setSelectedCitation(null)
          void openFileByPath(path)
        }}
      />

      <FilePreview
        file={selectedFile}
        loading={fileLoading}
        error={fileError}
        onRetry={retryFile}
        onClose={closeFile}
      />
    </div>
  )
}

export default ChatPage

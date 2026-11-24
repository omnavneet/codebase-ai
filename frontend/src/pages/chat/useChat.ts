import { useEffect, useRef, useState } from "react"
import apiClient from "../../services/apiClient"
import { streamChatMessage } from "../../services/streamChat"
import { streamAgent, type AgentStreamResult } from "../../services/streamAgent"
import { useToast } from "../../context/ToastContext"
import type { Citation, Investigation, Message, Session } from "./types"

/** Inactivity budget for one send: watchdog abort and stale-lock recovery. */
const SEND_IDLE_MS = 45000

const parseInvestigation = (data: AgentStreamResult): Investigation => ({
  answer: data.answer,
  trace: data.trace ?? [],
  iterations: data.iterations ?? 0,
  filesRead: data.files_read ?? [],
  searchesPerformed: data.searches_performed ?? [],
  truncated: data.truncated,
})

/**
 * Owns all chat data: conversations, messages, the composer input and the
 * send/stream lifecycle (watchdog, sync fallback, orphaned-lock recovery).
 * Presentation stays in the components; debugging starts here.
 *
 * Conversations are project-scoped. A "new chat" is local-only until the
 * first message is sent (no empty rows); the active conversation id then
 * travels with every chat and Deep investigate request. The last active
 * conversation per project is remembered in localStorage.
 */
export function useChat(projectId: string | undefined) {
  const { toast } = useToast()
  const [conversations, setConversations] = useState<Session[]>([])
  const [activeConversation, setActiveConversation] = useState<string | null>(null)
  const [messages, setMessages] = useState<Message[]>([])
  const [input, setInput] = useState("")
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState("")
  const [conversationsLoading, setConversationsLoading] = useState(false)
  const [conversationsError, setConversationsError] = useState("")
  const [historyLoading, setHistoryLoading] = useState(false)
  const [historyError, setHistoryError] = useState("")
  const [renamingSessionId, setRenamingSessionId] = useState<string | null>(null)
  const [renameTitle, setRenameTitle] = useState("")

  // Context indicator state
  const [contextPercent, setContextPercent] = useState<number | null>(null)
  const [contextBudget, setContextBudget] = useState<number>(60000)

  // Tracks the in-flight chat stream so it can be cancelled when leaving the
  // page or switching projects.
  const streamAbortRef = useRef<AbortController | null>(null)
  // Timestamp of the last sign of life (send start / token / fallback) of the
  // current send. Used to detect an orphaned busy-lock: component state can
  // survive hot-reloads while the stream closure that owns it is gone, which
  // would otherwise block the composer forever.
  const historyAbortRef = useRef<AbortController | null>(null)
  const pendingRequestRef = useRef<RunRequest | null>(null)
  // Temp ("new chat") conversations vanish on project switch; only real
  // (server-assigned, non-"new-") ids are remembered across reloads.
  const storageKey = projectId ? `codebase-ai:conversation:${projectId}` : null

  // A new chat starts life as a local row with a "new-" id: the server only
  // hears about it when the first message is sent. Everything downstream
  // (ensureConversation, the sends) treats a "new-" id the same way.
  const isLocalNewChat = (id: string | null) =>
    id != null && id.startsWith("new-")

  // Abort any in-flight chat stream when leaving the page or switching to a
  // different project, so streamed tokens never reach an unmounted component.
  // The per-project state reset happens in the cleanup so it runs before the
  // next project's effects fire.
  useEffect(() => {
    return () => {
      streamAbortRef.current?.abort()
      historyAbortRef.current?.abort()
      streamAbortRef.current = null
      pendingRequestRef.current = null
      setLoading(false)
      setHistoryLoading(false)
      setHistoryError("")
      setConversations([])
      setActiveConversation(null)
      setMessages([])
      setError("")
    }
  }, [projectId])

  const parseCitations = (
    citations: string | unknown[] | undefined,
  ): Citation[] | undefined => {
    if (!citations) return undefined
    if (Array.isArray(citations)) return citations as Citation[]

    try {
      const parsed: unknown = JSON.parse(citations)
      return Array.isArray(parsed) ? (parsed as Citation[]) : undefined
    } catch {
      return undefined
    }
  }

  const parseMessage = (message: Message) => {
    const parsed = { ...message, citations: parseCitations(message.citations) }
    if (!message.runMetadata) return parsed
    try {
      const data = JSON.parse(message.runMetadata) as AgentStreamResult
      if (typeof data.answer === "string") {
        parsed.investigation = parseInvestigation(data)
        parsed.deep = true
      }
    } catch {
      // An older result may only have answer text.
    }
    return parsed
  }

  const rememberConversation = (id: string) => {
    if (storageKey && !isLocalNewChat(id)) {
      localStorage.setItem(storageKey, id)
    }
  }

  // Local-only "new chat": nothing is created on the server until the first
  // message is sent (no empty clutter). The caller must forward the id when
  // selecting it in the list.
  const startNewChat = (id?: string) => {
    const localId = id ?? `new-${Date.now()}`
    streamAbortRef.current?.abort()
    streamAbortRef.current = null
    pendingRequestRef.current = null
    historyAbortRef.current?.abort()
    setHistoryLoading(false)
    setHistoryError("")
    setLoading(false)
    setActiveConversation(localId)
    setMessages([])
    setError("")
    setRenamingSessionId(null)
    return localId
  }

  const createNewSession = () => {
    startNewChat()
  }

  const selectConversation = async (conversationId: string) => {
    // Switching mid-stream aborts the run; whatever was saved stays saved.
    streamAbortRef.current?.abort()
    streamAbortRef.current = null
    pendingRequestRef.current = null
    historyAbortRef.current?.abort()
    setHistoryError("")
    setMessages([])
    setLoading(false)
    setActiveConversation(conversationId)
    setError("")
    setContextPercent(null)
    rememberConversation(conversationId)
    if (isLocalNewChat(conversationId) || !projectId) {
      setHistoryLoading(false)
      return
    }
    const controller = new AbortController()
    historyAbortRef.current = controller
    setHistoryLoading(true)
    try {
      const response = await apiClient.get<Message[]>(
        `/projects/${projectId}/conversations/${conversationId}/messages`,
        { signal: controller.signal },
      )
      if (!controller.signal.aborted) setMessages(response.data.map(parseMessage))
    } catch {
      if (!controller.signal.aborted) setHistoryError("Could not load this conversation.")
    } finally {
      if (historyAbortRef.current === controller) {
        historyAbortRef.current = null
        setHistoryLoading(false)
      }
    }
  }

  const selectSession = (sessionId: string) => {
    void selectConversation(sessionId)
  }

  // Reload the list only. Never touches the active conversation: this runs
  // after a send, a rename or a delete, where re-selecting would throw away
  // the conversation the user is looking at.
  const refreshConversations = async () => {
    if (!projectId) return
    try {
      const response = await apiClient.get(`/projects/${projectId}/conversations`)
      setConversations(response.data as Session[])
    } catch (error) {
      console.error("Failed to refresh conversations:", error)
    }
  }

  const fetchConversations = async () => {
    if (!projectId) return
    setConversationsLoading(true)
    setConversationsError("")
    try {
      const response = await apiClient.get(`/projects/${projectId}/conversations`)
      const list: Session[] = response.data
      setConversations(list)
      // Resume the remembered conversation when it still exists; otherwise
      // fall back to the newest remaining, or an empty new chat.
      const remembered = storageKey ? localStorage.getItem(storageKey) : null
      const stillThere = remembered && list.some((c) => c.id === remembered)
      if (stillThere && remembered) {
        void selectConversation(remembered)
      } else if (list.length > 0) {
        void selectConversation(list[0].id)
      } else {
        startNewChat()
      }
    } catch (error) {
      console.error("Failed to fetch conversations:", error)
      setConversationsError(
        error instanceof Error && error.message
          ? error.message
          : "Could not load your chats.",
      )
    } finally {
      setConversationsLoading(false)
    }
  }

  useEffect(() => {
    if (!projectId) return
    // Async fetch — setState only runs after the request resolves.
    // eslint-disable-next-line react-hooks/set-state-in-effect
    fetchConversations()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [projectId])

  const deleteSession = async (sessionId: string) => {
    if (isLocalNewChat(sessionId)) {
      // A local "new chat" was never sent to the server: just drop it and
      // fall back to the newest remaining, or a fresh empty chat.
      const remaining = conversations.filter((c) => c.id !== sessionId)
      if (activeConversation === sessionId) {
        if (remaining.length > 0) {
          void selectConversation(remaining[0].id)
        } else {
          startNewChat()
        }
      }
      return
    }
    if (!projectId) return
    try {
      await apiClient.delete(`/projects/${projectId}/conversations/${sessionId}`)
      const remaining = conversations.filter((c) => c.id !== sessionId)
      setConversations(remaining)
      if (activeConversation === sessionId) {
        if (remaining.length > 0) {
          void selectConversation(remaining[0].id)
        } else {
          startNewChat()
        }
        setError("")
        setRenamingSessionId(null)
      }
    } catch (error) {
      console.error("Failed to delete session:", error)
      // The row only leaves the list on success, so this toast is what tells
      // the user the delete did not happen and the chat is still there.
      toast({
        tone: 'error',
        title: 'Could not delete the chat',
        description: 'Nothing was removed — try again in a moment.',
      })
    }
  }

  const startRenaming = (session: Session) => {
    setRenamingSessionId(session.id)
    setRenameTitle(session.title)
  }

  const saveSessionTitle = async (sessionId: string) => {
    const title = renameTitle.trim()
    if (!title) {
      setRenamingSessionId(null)
      return
    }
    if (isLocalNewChat(sessionId)) {
      // No server row yet: keep it local; the server hears the title (and
      // the chat) when the first message is sent.
      setRenamingSessionId(null)
      return
    }
    if (!projectId) return
    try {
      const response = await apiClient.patch(
        `/projects/${projectId}/conversations/${sessionId}`,
        { title },
      )
      setConversations((currentConversations) =>
        currentConversations.map((conversation) =>
          conversation.id === sessionId ? response.data : conversation,
        ),
      )
      setRenamingSessionId(null)
    } catch (error) {
      console.error("Failed to rename session:", error)
      // Keep the row in rename mode so the typed title is not thrown away.
      toast({
        tone: 'error',
        title: 'Could not rename the chat',
        description: 'Your text is still here — try saving again.',
      })
    }
  }

  // Returns the active conversation id, transparently creating the chat on
  // the server at the first message when it is still a local "new chat".
  // Returns null when creation fails.
  const ensureConversation = async (signal: AbortSignal): Promise<string | null> => {
    if (activeConversation && !isLocalNewChat(activeConversation)) {
      return activeConversation
    }
    if (!projectId) return null
    try {
      const response = await apiClient.post(`/projects/${projectId}/conversations`, undefined, { signal })
      if (signal.aborted) return null
      const created = response.data
      setConversations((prev) => [created, ...prev])
      setActiveConversation(created.id)
      rememberConversation(created.id)
      return created.id
    } catch (error) {
      if (signal.aborted) return null
      console.error("Failed to create conversation:", error)
      // The send bails out right after this, so without a toast the user
      // would press Enter and see nothing at all happen.
      toast({
        tone: 'error',
        title: 'Could not start the conversation',
        description: 'Check your connection and press Enter to try again.',
      })
      return null
    }
  }


  type RunRequest = {
    requestId: string
    question: string
    deep: boolean
    replyToMessageId?: string
  }

  const runQuery = async (request: RunRequest, retrying = false) => {
    if (!projectId || !request.question || streamAbortRef.current || conversationsLoading || historyLoading || historyError) return
    const controller = new AbortController()
    streamAbortRef.current = controller
    setLoading(true)
    setError("")
    let timer: ReturnType<typeof setTimeout> | undefined
    let timedOut = false
    const current = () => streamAbortRef.current === controller
    const armWatchdog = () => {
      clearTimeout(timer)
      timer = setTimeout(() => {
        timedOut = true
        controller.abort()
      }, SEND_IDLE_MS)
    }
    const userId = request.replyToMessageId ?? "temp-user-" + request.requestId
    const assistantId = "temp-assistant-" + request.requestId
    const createdAt = new Date().toISOString()
    let committed = false
    let baseline = messages

    try {
      const conversationId = await ensureConversation(controller.signal)
      if (!conversationId || !current()) return
      setInput("")
      pendingRequestRef.current = request

      if (retrying) baseline = baseline.filter(message => message.id !== assistantId)
      if (request.replyToMessageId && baseline.at(-1)?.role === "assistant") {
        setMessages(baseline.slice(0, -1))
      } else {
        const hasQuestion = retrying || request.replyToMessageId
        setMessages(hasQuestion ? baseline : [
          ...baseline, { id: userId, role: "user", content: request.question, createdAt },
        ])
      }
      setMessages(previous => [
        ...previous, { id: assistantId, role: "assistant", content: "", createdAt, deep: request.deep },
      ])

      const updateAssistant = (update: (message: Message) => Message) => {
        if (current()) setMessages(previous => previous.map(message =>
          message.id === assistantId ? update(message) : message,
        ))
      }
      const acknowledge = (id?: string) => {
        armWatchdog()
        if (current() && id) setMessages(previous => previous.map(message =>
          message.id === userId ? { ...message, id } : message,
        ))
      }
      const reportError = (message: string) => {
        if (current()) setError(message)
      }
      const options = {
        requestId: request.requestId,
        replyToMessageId: request.replyToMessageId,
        regenerate: Boolean(request.replyToMessageId),
      }

      armWatchdog()
      if (request.deep) {
        await streamAgent(projectId, "/investigate/stream", {
          question: request.question, conversationId, ...options,
        }, {
          onMeta: acknowledge,
          onStep: armWatchdog,
          onDone: data => {
            committed = true
            const investigation = parseInvestigation(data)
            updateAssistant(message => ({
              ...message, content: investigation.answer, investigation, citations: parseCitations(data.citations),
            }))
          },
          onError: reportError,
        }, controller.signal)
      } else {
        await streamChatMessage(projectId, conversationId, request.question, {
          onToken: token => {
            armWatchdog()
            updateAssistant(message => ({ ...message, content: message.content + token }))
          },
          onMeta: (citations, savedUserId) => {
            acknowledge(savedUserId)
            updateAssistant(message => ({ ...message, citations: parseCitations(citations) }))
          },
          onDone: messageId => {
            committed = Boolean(messageId)
            if (!committed) reportError("The answer could not be saved. Please try again.")
            updateAssistant(message => ({ ...message, id: messageId || message.id }))
          },
          onContext: context => {
            if (current() && context && context.budgetChars > 0) {
              setContextPercent(Math.round(context.usedChars / context.budgetChars * 100))
              setContextBudget(context.budgetChars)
            }
          },
          onError: reportError,
        }, controller.signal, options)
      }

      if (committed && current()) {
        pendingRequestRef.current = null
        // Reconcile persisted IDs and Deep metadata even if the terminal
        // result was replayed after the original connection was interrupted.
        try {
          const response = await apiClient.get<Message[]>(
            "/projects/" + projectId + "/conversations/" + conversationId + "/messages",
            { signal: controller.signal },
          )
          if (current()) setMessages(response.data.map(parseMessage))
        } catch {
          // The terminal event already confirmed the save.
        }
        if (current()) void refreshConversations()
      }
    } catch (streamError) {
      if (current() && (!controller.signal.aborted || timedOut)) {
        setError(timedOut ? "The answer timed out. Try the same request again." :
          streamError instanceof Error ? streamError.message : "The answer was interrupted.")
      }
    } finally {
      clearTimeout(timer)
      if (current()) {
        streamAbortRef.current = null
        if (!committed) {
          if (request.replyToMessageId) setMessages(baseline)
          else setMessages(previous => previous.filter(message =>
            message.id !== assistantId || message.content || message.stopped,
          ))
        }
        setLoading(false)
      }
    }
  }

  const stop = () => {
    streamAbortRef.current?.abort()
    setMessages(previous => previous.map((message, index) =>
      index === previous.length - 1 && message.role === "assistant"
        ? { ...message, stopped: true }
        : message,
    ))
  }

  const retry = () => {
    if (pendingRequestRef.current) void runQuery(pendingRequestRef.current, true)
  }

  const regenerate = async (options?: { deep?: boolean }) => {
    const lastUser = [...messages].reverse().find(message => message.role === "user")
    if (!lastUser) return
    if (pendingRequestRef.current) {
      retry()
      return
    }
    if (lastUser.id.startsWith("temp-")) return
    await runQuery({
      requestId: crypto.randomUUID(), question: lastUser.content,
      deep: Boolean(options?.deep), replyToMessageId: lastUser.id,
    })
  }

  const sendQuery = async (queryText: string, options?: { deep?: boolean }) =>
    runQuery({ requestId: crypto.randomUUID(), question: queryText.trim(), deep: Boolean(options?.deep) })

  // Retry the conversations fetch after a load failure.
  const retryConversations = () => {
    void fetchConversations()
  }

  return {
    // The sidebar renders conversations, not the legacy session list.
    sessions: conversations,
    activeSession: activeConversation,
    messages,
    input,
    setInput,
    loading,
    error,
    historyLoading,
    historyError,
    retryHistory: () => { if (activeConversation) void selectConversation(activeConversation) },
    conversationsLoading,
    conversationsError,
    renamingSessionId,
    renameTitle,
    setRenameTitle,
    setRenamingSessionId,
    fetchSessions: fetchConversations,
    retryConversations,
    createNewSession,
    selectSession,
    deleteSession,
    startRenaming,
    saveSessionTitle,
    sendQuery,
    stop,
    regenerate,
    retry,
    contextPercent,
    contextBudget,
  }
}

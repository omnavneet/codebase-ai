import { useEffect, useRef, useState } from "react"
import apiClient from "../../services/apiClient"
import { streamChatMessage } from "../../services/streamChat"
import { streamAgent } from "../../services/streamAgent"
import { useToast } from "../../context/ToastContext"
import type { Citation, Investigation, Message, Session } from "./types"

/** Inactivity budget for one send: watchdog abort and stale-lock recovery. */
const SEND_IDLE_MS = 45000

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
  const [renamingSessionId, setRenamingSessionId] = useState<string | null>(null)
  const [renameTitle, setRenameTitle] = useState("")

  // Tracks the in-flight chat stream so it can be cancelled when leaving the
  // page or switching projects.
  const streamAbortRef = useRef<AbortController | null>(null)
  // Timestamp of the last sign of life (send start / token / fallback) of the
  // current send. Used to detect an orphaned busy-lock: component state can
  // survive hot-reloads while the stream closure that owns it is gone, which
  // would otherwise block the composer forever.
  const lastSendActivityRef = useRef(0)
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
      setConversations([])
      setActiveConversation(null)
      setMessages([])
      setError("")
    }
  }, [projectId])

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
    setLoading(false)
    setActiveConversation(conversationId)
    setError("")
    rememberConversation(conversationId)
    if (isLocalNewChat(conversationId) || !projectId) {
      setMessages([])
      return
    }
    try {
      const response = await apiClient.get(
        `/projects/${projectId}/conversations/${conversationId}/messages`,
      )
      setMessages(
        response.data.map(
          (message: Message & { citations?: string | Citation[] }) => ({
            ...message,
            citations: parseCitations(message.citations),
          }),
        ),
      )
    } catch (error) {
      console.error("Failed to fetch messages:", error)
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
  const ensureConversation = async (): Promise<string | null> => {
    if (activeConversation && !isLocalNewChat(activeConversation)) {
      return activeConversation
    }
    if (!projectId) return null
    try {
      const response = await apiClient.post(`/projects/${projectId}/conversations`)
      const created = response.data
      setConversations((prev) => [created, ...prev])
      setActiveConversation(created.id)
      rememberConversation(created.id)
      return created.id
    } catch (error) {
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

  // One question runs through either path: "append" adds the question plus a
  // fresh answer slot; "regenerate" re-asks the stored question and replaces
  // the last answer in place — no duplicate user message, in the UI or in the
  // backend history.
  const runQuery = async (
    queryText: string,
    options: { deep?: boolean } | undefined,
    mode: "append" | "regenerate",
  ) => {
    // Recover from an orphaned busy-lock (e.g. state preserved across a
    // hot-reload while the stream closure that owned it is gone): if no send
    // activity has been recorded for a while, the lock is stale — release it
    // instead of blocking the composer forever.
    const lockIsStale =
      loading && Date.now() - lastSendActivityRef.current > SEND_IDLE_MS
    if (!queryText.trim() || (loading && !lockIsStale)) return
    if (lockIsStale) setLoading(false)

    const currentConversationId = await ensureConversation()
    if (!currentConversationId) return

    const userMessageText = queryText.trim()
    setInput("")
    setLoading(true)
    setError("")
    lastSendActivityRef.current = Date.now()

    // The answer slot the stream fills in token by token. While its content
    // is empty it renders as the typing indicator — no separate indicator row.
    let assistantId: string
    // Snapshot of the answer a regenerate replaces: restored if the re-run
    // fails before its first token, matching the backend, which only
    // overwrites the stored answer once new content actually exists.
    let replacedAnswer: Message | null = null

    if (mode === "regenerate") {
      const last = messages[messages.length - 1]
      if (last?.role === "assistant") {
        assistantId = last.id
        replacedAnswer = last
        setMessages((prev) =>
          prev.map((message) =>
            message.id === last.id
              ? {
                  ...message,
                  content: "",
                  citations: undefined,
                  investigation: undefined,
                  stopped: false,
                  deep: options?.deep,
                }
              : message,
          ),
        )
      } else {
        // The previous attempt failed before any token: only the answer slot
        // is missing, so the question stays exactly where it is.
        assistantId = "temp-assistant-" + Date.now()
        setMessages((prev) => [
          ...prev,
          {
            id: assistantId,
            role: "assistant",
            content: "",
            createdAt: new Date().toISOString(),
            deep: options?.deep,
          },
        ])
      }
    } else {
      // Optimistically add the user message plus an empty assistant message
      // that the stream fills in token by token.
      const tempUserMessage: Message = {
        id: "temp-user-" + Date.now(),
        role: "user",
        content: userMessageText,
        createdAt: new Date().toISOString(),
      }
      assistantId = "temp-assistant-" + Date.now()
      const assistantPlaceholder: Message = {
        id: assistantId,
        role: "assistant",
        content: "",
        createdAt: new Date().toISOString(),
        deep: options?.deep,
      }
      setMessages((prev) => [...prev, tempUserMessage, assistantPlaceholder])
    }

    const updateAssistant = (update: (message: Message) => Message) => {
      setMessages((prev) =>
        prev.map((message) =>
          message.id === assistantId ? update(message) : message,
        ),
      )
    }

    // Never leave an empty assistant bubble behind on abort or error; a
    // failed regenerate restores the answer it replaced instead.
    const finalizeAssistantBubble = () => {
      const snapshot = replacedAnswer
      setMessages((prev) => {
        const target = prev.find((message) => message.id === assistantId)
        if (!target || target.content !== "") return prev
        // A re-run with no new text falls back to the answer it replaced —
        // matching the backend, which only overwrites once content exists.
        if (snapshot) {
          return prev.map((message) =>
            message.id === assistantId ? snapshot : message,
          )
        }
        // A slot stopped before its first token stays visible (with its
        // note) so the question keeps a Regenerate target instead of
        // inviting a duplicate re-send from the composer.
        if (target.stopped) return prev
        return prev.filter((message) => message.id !== assistantId)
      })
    }

    // Deep investigate uses the bounded agent loop (POST /agent/investigate)
    // streamed as step events. The optimistic assistant placeholder fills in
    // as steps arrive, then the terminal `done` carries the full result with
    // its trace — rendered inline in the message list.
    if (options?.deep) {
      const controller = new AbortController()
      streamAbortRef.current = controller
      const traceSteps: string[] = []

      try {
        await streamAgent(
        projectId ?? "",
        "/investigate/stream",
        { question: userMessageText, conversationId: currentConversationId },
        {
          onStep: (message) => {
            traceSteps.push(message)
          },
          onDone: (data) => {
            const investigation: Investigation = {
              answer: (data.answer as string) ?? "",
              trace: (data.trace as string[]) ?? traceSteps,
              iterations: (data.iterations as number) ?? 0,
              filesRead: (data.files_read as string[]) ?? [],
              searchesPerformed: (data.searches_performed as string[]) ?? [],
              truncated: data.truncated as boolean | undefined,
            }
            updateAssistant((message) => ({
              ...message,
              content: investigation.answer,
              investigation,
              deep: true,
            }))
          },
          onError: (message) => {
            // The stream's own reason, shown with Retry below; the question
            // and any partial text stay right where they are.
            setError(message)
          },
        },
        controller.signal,
        )
      } catch (error) {
        const aborted =
          controller.signal.aborted ||
          (error instanceof DOMException && error.name === "AbortError")
        if (!aborted) {
          console.error("Deep investigate failed:", error)
          updateAssistant((message) =>
            message.content
              ? message
              : {
                  ...message,
                  content:
                    "The investigation could not be completed right now. Your question is safe in the conversation above; press Enter in the composer to try again.",
                },
          )
          // Return the failed question to the composer so retrying is one
          // keystroke — unless the user has already started typing something
          // new, which always wins.
          setInput((current) => (current.trim() ? current : userMessageText))
        }
      } finally {
        if (streamAbortRef.current === controller) {
          streamAbortRef.current = null
        }
        finalizeAssistantBubble()
        setLoading(false)
      }

      refreshConversations()
      return
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
      }, SEND_IDLE_MS)
    }

    const abortController = new AbortController()
    streamAbortRef.current = abortController
    armWatchdog()

    try {
      await streamChatMessage(
        projectId ?? "",
        currentConversationId,
        userMessageText,
        {
          onToken: (token) => {
            receivedToken = true
            armWatchdog()
            lastSendActivityRef.current = Date.now()
            updateAssistant((message) => ({
              ...message,
              content: message.content + token,
            }))
          },
          onMeta: (citations) => {
            updateAssistant((message) => ({
              ...message,
              citations: citations as Citation[],
            }))
          },
          onDone: (messageId) => {
            // Reconcile the optimistic placeholder with the persisted message.
            if (messageId) {
              updateAssistant((message) => ({ ...message, id: messageId }))
            }
          },
          onError: (message) => {
            // The stream's own reason becomes the error bar with Retry; any
            // partial answer already shown is kept untouched.
            setError(message)
          },
        },
        abortController.signal,
        { regenerate: mode === "regenerate", conversationId: currentConversationId },
      )
      refreshConversations()
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
        lastSendActivityRef.current = Date.now()
        try {
          const response = await apiClient.post(
            // The streaming prep phase already persisted the question, so
            // declare userPersisted to keep the conversation free of duplicates.
            `/projects/${projectId}/conversations/${currentConversationId}/messages?userPersisted=true`,
            {
              content: userMessageText,
              // A re-ask must not append a second copy of the question or a
              // second answer to the stored history either.
              ...(mode === "regenerate" ? { regenerate: true } : {}),
            },
          )
          updateAssistant((message) => ({
            ...message,
            content: response.data.answer,
            citations: response.data.citations,
          }))
          refreshConversations()
        } catch (error) {
          console.error("Failed to send message:", error)
          updateAssistant((message) => ({
            ...message,
            content:
              'The answer could not be generated right now. Your question is safe in the conversation above; press Enter in the composer to try again.',
          }))
          // Return the failed question to the composer so retrying is one
          // keystroke — unless the user has already started typing something
          // new, which always wins.
          setInput((current) => (current.trim() ? current : userMessageText))
        }
      }
    } finally {
      if (watchdogId) clearTimeout(watchdogId)
      if (streamAbortRef.current === abortController) {
        streamAbortRef.current = null
      }
      // Self-heal: never leave an empty assistant bubble behind. If nothing
      // ever arrived for this send (and it was not reconciled to a persisted
      // message), drop the placeholder — or restore the answer a failed
      // regenerate replaced.
      finalizeAssistantBubble()
      setLoading(false)
    }
  }

  /**
   * Stop the in-flight answer: abort the stream, unlock the composer
   * immediately, and keep whatever already streamed — flagged "Stopped".
   */
  const stop = () => {
    if (!loading) return
    streamAbortRef.current?.abort()
    streamAbortRef.current = null
    setLoading(false)
    setMessages((prev) =>
      prev.map((message, index) =>
        message.role === "assistant" && index === prev.length - 1
          ? { ...message, stopped: true }
          : message,
      ),
    )
  }

  // Re-ask the last question, replacing the last answer. Backs both the
  // Regenerate action and the stream-error Retry.
  const regenerate = async (options?: { deep?: boolean }) => {
    const lastUser = [...messages].reverse().find((message) => message.role === "user")
    if (!lastUser) return
    await runQuery(lastUser.content, options, "regenerate")
  }

  const sendQuery = async (queryText: string, options?: { deep?: boolean }) =>
    runQuery(queryText, options, "append")

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
  }
}

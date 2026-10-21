import { useEffect, type RefObject } from "react"
import type { TabId } from "./types"

const TAB_KEYS = new Map<string, TabId>([
  ["1", "chat"],
  ["2", "files"],
  ["3", "search"],
  ["4", "analyze"],
])

/**
 * True when the key landed in a place where the character matters: a text
 * field, a textarea or a rich-text region. The shortcuts stay out of the way
 * there — Esc is the only exception.
 */
const isTextControl = (element: Element | null): boolean => {
  if (!element) return false
  const tag = element.tagName.toLowerCase()
  if (tag === "input" || tag === "textarea") return true
  const editable = element.closest("[contenteditable]")
  return editable !== null && editable.getAttribute("contenteditable") !== "false"
}

export interface UseKeyboardShortcutsOptions {
  /** The mode currently on screen. */
  activeTab: TabId
  /** Switches to another mode (and keeps the URL in step). */
  onTabChange: (tab: TabId) => void
  /** The chat input, so "/" can put the caret in it. */
  chatInputRef: RefObject<HTMLTextAreaElement | null> | null
  /** Closes the topmost modal, popover or drawer. True when it closed one. */
  onCloseTopLayer?: () => boolean
}

/**
 * Workspace keyboard shortcuts — plain DOM, no library.
 *
 *   1 – 4  switch to Chat / Files / Search / Analyze
 *   /      focus the chat input (switching to Chat first when needed)
 *   Esc    close the open modal, popover or drawer
 *
 * Everything except Esc is ignored while the caret is in an input, a textarea
 * or a contenteditable region, so typing "1" into the composer types a "1".
 */
export function useKeyboardShortcuts({
  activeTab,
  onTabChange,
  chatInputRef,
  onCloseTopLayer,
}: UseKeyboardShortcutsOptions) {
  useEffect(() => {
    const handleKeyDown = (event: KeyboardEvent) => {
      if (event.key === "Escape") {
        const closed = onCloseTopLayer?.() ?? false
        if (closed) {
          event.preventDefault()
          // Keep the other Esc listeners (the Dialog component's own) from
          // closing a second layer on the same keypress.
          event.stopImmediatePropagation()
        }
        return
      }

      // Leave browser and OS combinations alone (Ctrl+1, Cmd+/, Alt+4…).
      if (event.ctrlKey || event.metaKey || event.altKey) return

      if (isTextControl(event.target as Element | null)) return

      const tab = TAB_KEYS.get(event.key)
      if (tab) {
        event.preventDefault()
        onTabChange(tab)
        return
      }

      if (event.key === "/") {
        event.preventDefault()
        if (activeTab !== "chat") onTabChange("chat")
        // The composer only exists in the Chat tab, so wait for it to render
        // before reaching for the caret.
        let attempts = 0
        const focusComposer = () => {
          const node = chatInputRef?.current
          if (node) {
            node.focus()
            return
          }
          if (attempts < 10) {
            attempts += 1
            window.requestAnimationFrame(focusComposer)
          }
        }
        window.requestAnimationFrame(focusComposer)
      }
    }

    document.addEventListener("keydown", handleKeyDown)
    return () => document.removeEventListener("keydown", handleKeyDown)
  }, [activeTab, onTabChange, chatInputRef, onCloseTopLayer])
}

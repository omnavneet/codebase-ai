import React, { useState, useRef, useEffect, useCallback } from "react"
import apiClient from "../../services/apiClient"

const MAX_CHARS = 1500

const PRESETS = [
  {
    label: "Concise",
    text: "Answer in at most 5 sentences. Lead with the answer. Use code only when it is essential.",
  },
  {
    label: "Code reviewer",
    text: "Act as a careful code reviewer. Point out bugs, risks and unclear logic with file:line references, ordered by severity. Suggest minimal fixes.",
  },
  {
    label: "Explain like a teammate",
    text: "Explain like a friendly senior teammate who knows this codebase. Give the big picture first, then the details. Plain language, short examples.",
  },
] as const

interface AnswerStyleEditorProps {
  projectId: string
  isOpen: boolean
  onClose: () => void
}

const AnswerStyleEditor: React.FC<AnswerStyleEditorProps> = ({
  projectId,
  isOpen,
  onClose,
}) => {
  const [instructions, setInstructions] = useState<string>("")
  const [defaultInstructions, setDefaultInstructions] = useState<string>("")
  const [loading, setLoading] = useState(false)
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState<string>("")
  const [hasUnsaved, setHasUnsaved] = useState(false)
  const textareaRef = useRef<HTMLTextAreaElement>(null)

  const fetchSettings = useCallback(async () => {
    if (!projectId) return
    setLoading(true)
    setError("")
    try {
      const response = await apiClient.get(`/projects/${projectId}/settings`)
      const value = response.data?.chatInstructions ?? null
      setDefaultInstructions(value ?? "")
      setInstructions(value ?? "")
      setHasUnsaved(false)
    } catch {
      setError("Failed to load settings")
    } finally {
      setLoading(false)
    }
  }, [projectId])

  useEffect(() => {
    if (!isOpen) return
    // eslint-disable-next-line react-hooks/set-state-in-effect
    void fetchSettings()
  }, [isOpen, fetchSettings])

  useEffect(() => {
    if (isOpen && textareaRef.current) {
      textareaRef.current.focus()
    }
  }, [isOpen, loading])

  const applyPreset = (text: string) => {
    setInstructions(text)
    setHasUnsaved(text !== (defaultInstructions ?? ""))
  }

  const handleSave = async () => {
    if (!projectId) return
    const trimmed = instructions.trim()
    if (trimmed.length > MAX_CHARS) {
      setError(`Instructions must be at most ${MAX_CHARS} characters`)
      return
    }
    setSaving(true)
    setError("")
    try {
      await apiClient.put(`/projects/${projectId}/settings`, {
        chatInstructions: trimmed || undefined,
      })
      setDefaultInstructions(trimmed)
      setHasUnsaved(false)
    } catch {
      setError("Failed to save settings")
    } finally {
      setSaving(false)
    }
  }

  const handleReset = async () => {
    if (!projectId) return
    setSaving(true)
    setError("")
    try {
      await apiClient.put(`/projects/${projectId}/settings`, {
        chatInstructions: null,
      })
      setInstructions("")
      setDefaultInstructions("")
      setHasUnsaved(false)
    } catch {
      setError("Failed to reset settings")
    } finally {
      setSaving(false)
    }
  }

  if (!isOpen) return null

  const charCount = instructions.length
  const isUsingDefault = !defaultInstructions

  return (
    <div className="style-editor-overlay" onClick={onClose}>
      <div
        className="style-editor-panel"
        onClick={(e) => e.stopPropagation()}
        role="dialog"
        aria-modal="true"
        aria-label="Answer style"
      >
        <div className="style-editor-header">
          <h2 className="style-editor-title">Answer style</h2>
          <button
            type="button"
            className="style-editor-close"
            onClick={onClose}
            aria-label="Close"
          >
            ×
          </button>
        </div>

        {loading ? (
          <div className="style-editor-loading">
            <span className="style-editor-spinner" aria-hidden="true" />
            <span>Loading settings…</span>
          </div>
        ) : error ? (
          <div className="style-editor-error">
            <span className="style-editor-error-icon" aria-hidden="true">⚠</span>
            <span>{error}</span>
          </div>
        ) : (
          <>
            {isUsingDefault && (
              <p className="style-editor-default-hint">Using default style</p>
            )}

            <textarea
              ref={textareaRef}
              className="style-editor-textarea"
              value={instructions}
              onChange={(e) => {
                setInstructions(e.target.value)
                setHasUnsaved(e.target.value !== defaultInstructions)
              }}
              placeholder="Describe how you'd like answers to be formatted, toned, or scoped…"
              maxLength={MAX_CHARS}
              rows={6}
              spellCheck={false}
            />

            <div className="style-editor-counter">
              <span className={charCount > MAX_CHARS * 0.9 ? "style-editor-counter-warning" : ""}>
                {charCount}/{MAX_CHARS}
              </span>
            </div>

            <div className="style-editor-presets">
              {PRESETS.map((preset) => (
                <button
                  key={preset.label}
                  type="button"
                  className="style-editor-preset"
                  onClick={() => applyPreset(preset.text)}
                >
                  {preset.label}
                </button>
              ))}
            </div>

            <div className="style-editor-actions">
              <button
                type="button"
                className="style-editor-btn style-editor-btn-secondary"
                onClick={handleReset}
                disabled={saving || (!hasUnsaved && !defaultInstructions)}
              >
                Reset
              </button>
              <button
                type="button"
                className="style-editor-btn style-editor-btn-primary"
                onClick={handleSave}
                disabled={saving || charCount > MAX_CHARS}
              >
                {saving ? "Saving…" : "Save"}
              </button>
            </div>
          </>
        )}
      </div>
    </div>
  )
}

export default AnswerStyleEditor

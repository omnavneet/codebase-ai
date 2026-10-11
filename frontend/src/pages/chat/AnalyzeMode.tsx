import React, { useState } from "react"
import ExplainPanel from "../../components/ExplainPanel"
import DebugPanel from "../../components/DebugPanel"
import ImprovePanel from "../../components/ImprovePanel"
import DocstringsPanel from "../../components/DocstringsPanel"
import { ANALYZE_SUB_MODES } from "./workspaceModes"
import type { AnalyzeSubMode } from "./types"

interface AnalyzeModeProps {
  projectId: string
  onCitationClick: (filePath: string, startLine?: number, endLine?: number) => void
}

/**
 * Groups Explain / Debug / Review / Docstrings under one Analyze tab.
 * Debug stays description-first; the other three are file-first.
 * Backend paths are unchanged — Review still hits /agent/improve-code.
 */
const AnalyzeMode: React.FC<AnalyzeModeProps> = ({ projectId, onCitationClick }) => {
  const [subMode, setSubMode] = useState<AnalyzeSubMode>("explain")
  const subModes = Object.keys(ANALYZE_SUB_MODES) as AnalyzeSubMode[]

  return (
    <div className="analyze-mode">
      <div className="analyze-subnav" role="tablist" aria-label="Analyze modes">
        {subModes.map((mode) => (
          <button
            key={mode}
            type="button"
            role="tab"
            aria-selected={subMode === mode}
            className={`analyze-subtab ${subMode === mode ? "active" : ""}`}
            onClick={() => setSubMode(mode)}
            title={ANALYZE_SUB_MODES[mode].description}
          >
            {ANALYZE_SUB_MODES[mode].title}
          </button>
        ))}
      </div>

      <div className="analyze-body" role="tabpanel">
        {subMode === "explain" && (
          <ExplainPanel projectId={projectId} onCitationClick={onCitationClick} />
        )}
        {subMode === "debug" && (
          <DebugPanel projectId={projectId} onCitationClick={onCitationClick} />
        )}
        {subMode === "review" && <ImprovePanel projectId={projectId} />}
        {subMode === "docstrings" && <DocstringsPanel projectId={projectId} />}
      </div>
    </div>
  )
}

export default AnalyzeMode

import React from 'react'

interface WorkspaceModeProps {
  /** Mode name, e.g. "Semantic search". Rendered as the workspace heading. */
  title: string
  /** One line explaining what the mode is for. */
  description: string
  icon: React.ReactNode
  children: React.ReactNode
}

/**
 * Frame for every non-chat mode of the workspace.
 *
 * Chat is a transcript and wants the full width; the modes are bounded tasks
 * (search, review, generate docs), so they get a centred column and a
 * contextual heading. That heading is what answers "where am I and what can I
 * do here" the moment the user switches modes.
 */
const WorkspaceMode: React.FC<WorkspaceModeProps> = ({
  title,
  description,
  icon,
  children,
}) => (
  <section className="workspace-mode view-enter" aria-labelledby="workspace-mode-title">
    <header className="workspace-mode-header">
      <span className="workspace-mode-icon" aria-hidden="true">
        {icon}
      </span>
      <div className="workspace-mode-heading">
        <h2 className="workspace-mode-title" id="workspace-mode-title">
          {title}
        </h2>
        <p className="workspace-mode-description">{description}</p>
      </div>
    </header>

    <div className="workspace-mode-body">{children}</div>
  </section>
)

export default WorkspaceMode

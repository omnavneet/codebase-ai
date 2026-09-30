import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import './index.css'
// Workspace layer: the mode frame, the Files browser, the file tree and the
// shared panel kit. Loaded after the primitives so it can build on them.
import './styles/workspace.css'
// Tool-mode results: search hits, findings, generated documentation.
import './styles/panels.css'
import App from './App.tsx'

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <App />
  </StrictMode>,
)

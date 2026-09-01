import React, { useState, useEffect, useRef } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import apiClient from '../services/apiClient';
import CitationModal from '../components/CitationModal';
import './Chat.css';

interface Session {
  id: string;
  title: string;
  updatedAt: string;
}

interface Citation {
  file_path?: string;
  start_line: number;
  end_line: number;
  content?: string;
}

interface Message {
  id: string;
  role: 'user' | 'assistant';
  content: string;
  citations?: Citation[];
  createdAt: string;
}

const ChatPage: React.FC = () => {
  const { projectId } = useParams<{ projectId: string }>();
  const navigate = useNavigate();
  
  const [sessions, setSessions] = useState<Session[]>([]);
  const [activeSession, setActiveSession] = useState<string | null>(null);
  const [messages, setMessages] = useState<Message[]>([]);
  const [input, setInput] = useState('');
  const [loading, setLoading] = useState(false);
  const [projectName, setProjectName] = useState('');
  const [selectedCitation, setSelectedCitation] = useState<Citation | null>(null);
  const [renamingSessionId, setRenamingSessionId] = useState<string | null>(null);
  const [renameTitle, setRenameTitle] = useState('');
  
  const messagesEndRef = useRef<HTMLDivElement>(null);
  const textareaRef = useRef<HTMLTextAreaElement>(null);

  useEffect(() => {
    fetchProjectInfo();
    fetchSessions();
  }, [projectId]);

  useEffect(() => {
    scrollToBottom();
  }, [messages]);

  const scrollToBottom = () => {
    messagesEndRef.current?.scrollIntoView({ behavior: 'smooth' });
  };

  const fetchProjectInfo = async () => {
    try {
      const response = await apiClient.get(`/projects/${projectId}`);
      setProjectName(response.data.name);
    } catch (error) {
      console.error('Failed to fetch project:', error);
    }
  };

  const fetchSessions = async () => {
    try {
      const response = await apiClient.get(`/projects/${projectId}/sessions`);
      setSessions(response.data);
    } catch (error) {
      console.error('Failed to fetch sessions:', error);
    }
  };

  const createNewSession = async () => {
    try {
      const response = await apiClient.post(`/projects/${projectId}/sessions`);
      const newSession = response.data;
      setSessions([newSession, ...sessions]);
      setActiveSession(newSession.id);
      setMessages([]);
    } catch (error) {
      console.error('Failed to create session:', error);
    }
  };

  const selectSession = async (sessionId: string) => {
    setActiveSession(sessionId);
    try {
      const response = await apiClient.get(`/sessions/${sessionId}/messages`);
      setMessages(response.data.map((message: Message & { citations?: string | Citation[] }) => ({
        ...message,
        citations: parseCitations(message.citations),
      })));
    } catch (error) {
      console.error('Failed to fetch messages:', error);
    }
  };

  const deleteSession = async (sessionId: string) => {
    try {
      await apiClient.delete(`/sessions/${sessionId}`);
      setSessions(currentSessions => currentSessions.filter(session => session.id !== sessionId));
      if (activeSession === sessionId) {
        setActiveSession(null);
        setMessages([]);
      }
    } catch (error) {
      console.error('Failed to delete session:', error);
    }
  };

  const startRenaming = (session: Session) => {
    setRenamingSessionId(session.id);
    setRenameTitle(session.title);
  };

  const saveSessionTitle = async (sessionId: string) => {
    const title = renameTitle.trim();
    if (!title) return;

    try {
      const response = await apiClient.patch(`/sessions/${sessionId}`, { title });
      setSessions(currentSessions => currentSessions.map(session =>
        session.id === sessionId ? response.data : session
      ));
      setRenamingSessionId(null);
    } catch (error) {
      console.error('Failed to rename session:', error);
    }
  };

  const parseCitations = (citations: string | Citation[] | undefined): Citation[] | undefined => {
    if (!citations) return undefined;
    if (Array.isArray(citations)) return citations;

    try {
      const parsed: unknown = JSON.parse(citations);
      return Array.isArray(parsed) ? parsed as Citation[] : undefined;
    } catch {
      return undefined;
    }
  };

  const sendMessage = async () => {
    if (!input.trim() || loading) return;

    if (!activeSession) {
      await createNewSession();
      return;
    }

    const userMessage = input.trim();
    setInput('');
    setLoading(true);

    // Optimistically add user message
    const tempUserMessage: Message = {
      id: 'temp-user',
      role: 'user',
      content: userMessage,
      createdAt: new Date().toISOString(),
    };
    setMessages([...messages, tempUserMessage]);

    try {
      const response = await apiClient.post(
        `/sessions/${activeSession}/messages`,
        { content: userMessage }
      );

      const assistantMessage: Message = {
        id: 'temp-assistant',
        role: 'assistant',
        content: response.data.answer,
        citations: response.data.citations,
        createdAt: new Date().toISOString(),
      };
      setMessages((prev) => [...prev, assistantMessage]);
      
      // Refresh sessions to update titles
      fetchSessions();
    } catch (error) {
      console.error('Failed to send message:', error);
      // Add error message
      const errorMessage: Message = {
        id: 'temp-error',
        role: 'assistant',
        content: 'Sorry, something went wrong. Please try again.',
        createdAt: new Date().toISOString(),
      };
      setMessages((prev) => [...prev, errorMessage]);
    } finally {
      setLoading(false);
    }
  };

  const handleKeyPress = (e: React.KeyboardEvent) => {
    if (e.key === 'Enter' && !e.shiftKey) {
      e.preventDefault();
      sendMessage();
    }
  };

  const formatDate = (dateString: string) => {
    const date = new Date(dateString);
    return date.toLocaleDateString('en-US', {
      month: 'short',
      day: 'numeric',
    });
  };

  return (
    <div className="chat-container">
      <aside className="chat-sidebar">
        <div className="sidebar-header">
          <span className="sidebar-title">Chats</span>
          <button className="new-chat-button" onClick={createNewSession}>
            + New
          </button>
        </div>
        <div className="session-list">
          {sessions.map((session) => (
            <div
              key={session.id}
              className={`session-item ${session.id === activeSession ? 'active' : ''}`}
              onClick={() => selectSession(session.id)}
            >
              {renamingSessionId === session.id ? (
                <input
                  className="session-title-input"
                  value={renameTitle}
                  autoFocus
                  onChange={event => setRenameTitle(event.target.value)}
                  onClick={event => event.stopPropagation()}
                  onKeyDown={event => {
                    if (event.key === 'Enter') saveSessionTitle(session.id);
                    if (event.key === 'Escape') setRenamingSessionId(null);
                  }}
                  onBlur={() => setRenamingSessionId(null)}
                />
              ) : (
                <div className="session-title" onDoubleClick={event => {
                  event.stopPropagation();
                  startRenaming(session);
                }}>{session.title}</div>
              )}
              <div className="session-date">{formatDate(session.updatedAt)}</div>
              <button
                type="button"
                className="session-delete-button"
                aria-label={`Delete ${session.title}`}
                onClick={event => {
                  event.stopPropagation();
                  deleteSession(session.id);
                }}
              >
                ×
              </button>
            </div>
          ))}
        </div>
      </aside>

      <main className="chat-main">
        <header className="chat-header">
          <div className="chat-project-name">{projectName}</div>
          <button className="back-button" onClick={() => navigate('/dashboard')}>
            ← Back
          </button>
        </header>

        {messages.length === 0 ? (
          <div className="empty-chat">
            <div className="empty-chat-icon">💬</div>
            <div className="empty-chat-text">Start a conversation</div>
            <div className="empty-chat-subtext">
              Ask questions about your codebase
            </div>
          </div>
        ) : (
          <div className="messages-container">
            {messages.map((message, index) => (
              <div key={index} className={`message ${message.role}`}>
                <div>{message.content}</div>
                {message.citations && message.citations.length > 0 && (
                  <div className="citations-container">
                    {message.citations.map((citation, idx) => (
                      <button
                        key={idx}
                        className="citation-chip"
                        onClick={() => setSelectedCitation(citation)}
                      >
                        {citation.file_path || `Lines ${citation.start_line}-${citation.end_line}`}
                      </button>
                    ))}
                  </div>
                )}
              </div>
            ))}
            <div ref={messagesEndRef} />
          </div>
        )}

        <div className="chat-input-container">
          <textarea
            ref={textareaRef}
            className="chat-input"
            value={input}
            onChange={(e) => setInput(e.target.value)}
            onKeyPress={handleKeyPress}
            placeholder="Ask about your codebase..."
            rows={1}
          />
          <button
            className="send-button"
            onClick={sendMessage}
            disabled={!input.trim() || loading}
          >
            {loading ? '...' : 'Send'}
          </button>
        </div>
      </main>

      <CitationModal
        citation={selectedCitation}
        onClose={() => setSelectedCitation(null)}
      />
    </div>
  );
};

export default ChatPage;
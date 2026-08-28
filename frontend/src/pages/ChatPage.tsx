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

interface Message {
  id: string;
  role: 'user' | 'assistant';
  content: string;
  citations?: any[];
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
  const [selectedCitation, setSelectedCitation] = useState(null);
  
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
      setMessages(response.data);
    } catch (error) {
      console.error('Failed to fetch messages:', error);
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
              <div className="session-title">{session.title}</div>
              <div className="session-date">{formatDate(session.updatedAt)}</div>
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
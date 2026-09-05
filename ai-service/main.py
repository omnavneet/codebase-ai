from fastapi import FastAPI, HTTPException
from fastapi.middleware.cors import CORSMiddleware
from pydantic import BaseModel
from typing import List
import os
from dotenv import load_dotenv
from embedding_service import EmbeddingService
from chat_service import ChatService
from agent import CodebaseAgent
from agent_tools import AgentTools

# Load environment variables
load_dotenv()

# Initialize FastAPI
app = FastAPI(title="Codebase AI Service")

# CORS
app.add_middleware(
    CORSMiddleware,
    allow_origins=["http://localhost:8080"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

# Initialize services
embedding_service = EmbeddingService()
chat_service = ChatService()

# Initialize agent (reuses the shared embedding model and Groq client)
agent_tools = AgentTools(
    db_config={
        "host": os.getenv("DB_HOST", "localhost"),
        "port": os.getenv("DB_PORT", "5432"),
        "dbname": os.getenv("DB_NAME", "codebase_ai"),
        "user": os.getenv("DB_USER", "admin"),
        "password": os.getenv("DB_PASSWORD", ""),
    },
    embedding_service=embedding_service,
    upload_dir=os.getenv("UPLOAD_DIR", "./uploads"),
)
agent = CodebaseAgent(agent_tools, chat_service.client)

# Request/Response models
class EmbedRequest(BaseModel):
    texts: List[str]

class EmbedResponse(BaseModel):
    embeddings: List[List[float]]

class ChatRequest(BaseModel):
    question: str
    context: List[dict]

class ChatResponse(BaseModel):
    answer: str
    citations: List[dict]

@app.get("/health")
async def health():
    return {"status": "UP"}

@app.post("/embed", response_model=EmbedResponse)
async def embed(request: EmbedRequest):
    try:
        embeddings = embedding_service.generate_embeddings(request.texts)
        return EmbedResponse(embeddings=embeddings)
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))

@app.post("/chat", response_model=ChatResponse)
async def chat(request: ChatRequest):
    try:
        answer = chat_service.generate_answer(request.question, request.context)
        
        citations = [
            {
                "file_path": ctx["file_path"],
                "start_line": ctx["start_line"],
                "end_line": ctx["end_line"]
            }
            for ctx in request.context
        ]
        
        return ChatResponse(answer=answer, citations=citations)
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))

class AgentRequest(BaseModel):
    question: str
    project_id: str

class AgentResponse(BaseModel):
    answer: str
    trace: List[str]
    iterations: int
    files_read: List[str]
    searches_performed: List[str]
    truncated: bool = False

@app.post("/agent/investigate", response_model=AgentResponse)
async def investigate(request: AgentRequest):
    try:
        result = agent.investigate(request.question, request.project_id)
        return AgentResponse(**result)
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))


if __name__ == "__main__":
    import uvicorn
    uvicorn.run(app, host="0.0.0.0", port=8000)
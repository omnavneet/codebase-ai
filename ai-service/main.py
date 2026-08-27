from fastapi import FastAPI, HTTPException
from fastapi.middleware.cors import CORSMiddleware
from pydantic import BaseModel
from typing import List, Optional
import os
from dotenv import load_dotenv
from embedding_service import EmbeddingService
from chat_service import ChatService

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

if __name__ == "__main__":
    import uvicorn
    uvicorn.run(app, host="0.0.0.0", port=8000)
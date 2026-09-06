from fastapi import FastAPI, HTTPException
from fastapi.middleware.cors import CORSMiddleware
from pydantic import BaseModel
from typing import List, Optional
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

# NOTE: embed/chat/investigate call blocking LLM/model code, so they are
# defined as sync endpoints — FastAPI runs those in its threadpool instead
# of blocking the event loop.

@app.post("/embed", response_model=EmbedResponse)
def embed(request: EmbedRequest):
    try:
        embeddings = embedding_service.generate_embeddings(request.texts)
        return EmbedResponse(embeddings=embeddings)
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))

@app.post("/chat", response_model=ChatResponse)
def chat(request: ChatRequest):
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
def investigate(request: AgentRequest):
    try:
        result = agent.investigate(request.question, request.project_id)
        return AgentResponse(**result)
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))

class GenerateDocsRequest(BaseModel):
    file_path: str
    project_id: str
    symbol: Optional[str] = None  # function/class name if specific

class GenerateDocsResponse(BaseModel):
    file_path: str
    documentation: str
    language: str
    symbol: Optional[str] = None

class GenerateReadmeRequest(BaseModel):
    project_id: str

class GenerateReadmeResponse(BaseModel):
    readme: str

@app.post("/agent/generate-docs", response_model=GenerateDocsResponse)
def generate_docs(request: GenerateDocsRequest):
    """Generate doc comments (JavaDoc/JSDoc/docstring/...) for a file or symbol."""
    try:
        file_content = agent_tools.read_file(request.file_path, request.project_id)

        if "error" in file_content:
            raise HTTPException(status_code=404, detail=file_content["error"])

        language = detect_language(request.file_path)
        doc_format = get_doc_format(language)

        if request.symbol:
            prompt = f"""Generate {doc_format} documentation for {request.symbol} in this file:

File: {request.file_path}
Language: {language}

Code:
{file_content['content'][:5000]}

Generate appropriate documentation based on the actual implementation.
Include:
- Description of what it does
- Parameters/inputs
- Return values/outputs
- Exceptions/errors
- Important notes

Format as {doc_format} comments. Return only the documentation."""
        else:
            prompt = f"""Generate {doc_format} documentation for this entire file:

File: {request.file_path}
Language: {language}

Code:
{file_content['content'][:5000]}

Generate appropriate file-level documentation including:
- File purpose
- Main functions/classes
- Dependencies
- Usage example if relevant

Format as {doc_format} comments. Return only the documentation."""

        response = chat_service.client.chat.completions.create(
            model=os.getenv("LLM_MODEL"),
            messages=[
                {"role": "system", "content": "You are a documentation expert. Generate clear, concise code documentation."},
                {"role": "user", "content": prompt}
            ],
            temperature=0.3
        )

        return GenerateDocsResponse(
            file_path=request.file_path,
            documentation=response.choices[0].message.content or "",
            language=language,
            symbol=request.symbol
        )

    except HTTPException:
        raise
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))

@app.post("/agent/generate-readme", response_model=GenerateReadmeResponse)
def generate_readme(request: GenerateReadmeRequest):
    """Investigate the project with the agent, then write a README from the findings."""
    try:
        files = agent_tools.list_files(request.project_id)

        question = f"""Analyze this project and gather information for a README.md.

Project structure:
{chr(10).join(files[:50])}

Investigate:
- What is the project's purpose?
- What technology stack does it use?
- What are the main entry points?
- How is the project organized?
- What are the key features?
- How to set up and run the project?"""

        result = agent.investigate(question, request.project_id)

        readme_prompt = f"""Based on this investigation, write a README.md:

Investigation findings:
{result['answer']}

Write a professional README.md with:
# Project Title
## Description
## Features
## Tech Stack
## Project Structure
## Getting Started
## Usage
## API/Architecture Overview (if relevant)

Return only the README content."""

        response = chat_service.client.chat.completions.create(
            model=os.getenv("LLM_MODEL"),
            messages=[
                {"role": "system", "content": "You are a technical writer. Create practical documentation."},
                {"role": "user", "content": readme_prompt}
            ],
            temperature=0.3
        )

        return GenerateReadmeResponse(readme=response.choices[0].message.content or "")

    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))

def detect_language(file_path: str) -> str:
    """Detect language from file extension"""
    ext = file_path.rsplit(".", 1)[-1].lower() if "." in file_path else ""

    language_map = {
        "java": "java",
        "py": "python",
        "js": "javascript",
        "ts": "typescript",
        "jsx": "javascript",
        "tsx": "typescript",
        "go": "go",
        "rb": "ruby",
        "rs": "rust"
    }

    return language_map.get(ext, "unknown")

def get_doc_format(language: str) -> str:
    """Get documentation comment format for language"""
    format_map = {
        "java": "JavaDoc",
        "python": "docstring",
        "javascript": "JSDoc",
        "typescript": "JSDoc",
        "go": "GoDoc",
        "ruby": "RDoc",
        "rust": "RustDoc"
    }

    return format_map.get(language, "standard comment")


if __name__ == "__main__":
    import uvicorn
    uvicorn.run(app, host="0.0.0.0", port=8000)
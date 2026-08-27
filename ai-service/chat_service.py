from groq import Groq
import os
from dotenv import load_dotenv

load_dotenv()

class ChatService:
    def __init__(self):
        self.client = Groq(api_key=os.getenv("GROQ_API_KEY"))
        self.model = os.getenv("LLM_MODEL")
    
    def generate_answer(self, question, context):
        """Generate answer using Groq"""
        
        # Build context string
        context_str = "\n\n".join([
            f"File: {ctx['file_path']}\n"
            f"Lines: {ctx['start_line']}-{ctx['end_line']}\n"
            f"Code:\n{ctx['content']}"
            for ctx in context
        ])
        
        # Build prompt
        prompt = f"""You are a code assistant. Answer questions about the codebase.

Context from codebase:
{context_str}

Question: {question}

Answer the question based on the context provided. 
If the answer is not in the context, say so.
Include relevant file paths and line numbers in your answer."""

        # Call Groq
        response = self.client.chat.completions.create(
            model=self.model,
            messages=[
                {"role": "system", "content": "You are a helpful code assistant."},
                {"role": "user", "content": prompt}
            ],
            temperature=0.3,
            max_tokens=1000
        )
        
        return response.choices[0].message.content